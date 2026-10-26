package com.aurora.carsentry

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import android.util.Base64

/**
 * 远程观看：把车机的本地 HTTP 服务「隧道化」到公网中继上。
 *
 * 为什么不是推 RTMP/WebRTC 到服务器：那要额外的转码与带宽，而且车机上行很小。
 * 用 HTTP over WebSocket 隧道，车机主动外连，手机端拿到的是一个普通 HTTPS 地址，
 * **iOS 那边一行播放代码都不用改**（AVPlayer 照常拉 m3u8/ts）。
 *
 * 链路：
 *   手机 → https://中继/v1/{room}/live/front/index.m3u8
 *   中继 → 通过 WebSocket 把请求塞给车机
 *   车机 → 转给 127.0.0.1:8710 的本地服务，拿到响应原样回传
 *
 * 好处是车机不需要公网 IP、不需要端口映射、不需要 root。
 */
class RelayTunnel(
    private val baseUrl: String,
    private val room: String,
    private val key: String,
    private val localPort: Int,
    private val onStatus: (connected: Boolean, detail: String) -> Unit,
) {

    companion object {
        private const val TAG = "RelayTunnel"
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L

        // 帧类型
        private const val T_REQ_HEAD = 0x01
        private const val T_REQ_BODY = 0x02
        private const val T_REQ_END = 0x03
        private const val T_RESP_HEAD = 0x04
        private const val T_RESP_BODY = 0x05
        private const val T_RESP_END = 0x06
        private const val T_ERROR = 0x07
    }

    @Volatile var connected: Boolean = false
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: Socket? = null
    private var ws: WebSocket? = null
    private var pool = Executors.newFixedThreadPool(4)

    fun start() {
        if (running) return
        running = true
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(4)
        thread = Thread({ loop() }, "relay-tunnel")
        thread?.start()
    }

    fun stop() {
        running = false
        try {
            ws?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        pool.shutdownNow()
        thread?.interrupt()
        thread = null
        connected = false
        onStatus(false, "已停止")
    }

    // ---------------------------------------------------------------------

    private fun loop() {
        var backoff = MIN_BACKOFF_MS

        while (running) {
            try {
                connectAndServe()
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "tunnel closed: ${e.message}")
                onStatus(false, e.message ?: "连接中断")
            } finally {
                closeQuietly()
            }

            if (!running) break
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }

        Log.i(TAG, "tunnel exit")
    }

    private fun connectAndServe() {
        val uri = parseUrl(baseUrl)
        val secure = uri.scheme.equals("wss", true) || uri.scheme.equals("https", true)
        val host = uri.host ?: throw IllegalArgumentException("中继地址缺少主机")
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80

        val basePath = uri.path?.takeIf { it.isNotBlank() && it != "/" } ?: ""
        val path = "/relay?room=${urlEncode(room)}&key=${urlEncode(key)}&role=car"

        Log.i(TAG, "connecting ${if (secure) "wss" else "ws"}://$host:$port$basePath$path")

        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 8_000)
        raw.soTimeout = 0
        raw.tcpNoDelay = true

        val sock: Socket = if (secure) {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
            val params = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = params
            ssl.startHandshake()
            ssl
        } else {
            raw
        }

        socket = sock

        val client = WebSocket(
            input = BufferedInputStream(sock.getInputStream(), 64 * 1024),
            output = sock.getOutputStream(),
            host = if (port == 443 || port == 80) host else "$host:$port",
            path = basePath + path,
        )
        client.handshake()
        ws = client

        connected = true
        lastError = null
        onStatus(true, "已连接中继")

        val pending = HashMap<Int, PendingRequest>()

        while (running) {
            val frame = client.readBinary() ?: break
            if (frame.size < 5) continue

            val type = frame[0].toInt() and 0xFF
            val reqId = readInt(frame, 1)

            when (type) {
                T_REQ_HEAD -> {
                    val json = String(frame, 5, frame.size - 5, Charsets.UTF_8)
                    pending[reqId] = PendingRequest(reqId, json)
                }

                T_REQ_BODY -> {
                    val request = pending[reqId] ?: continue
                    request.body.write(frame, 5, frame.size - 5)
                }

                T_REQ_END -> {
                    val request = pending.remove(reqId) ?: continue
                    pool.execute { serve(request) }
                }
            }
        }

        throw IllegalStateException("中继连接已断开")
    }

    // ---------------------------------------------------------------------
    // 转发到本地服务
    // ---------------------------------------------------------------------

    private class PendingRequest(val id: Int, val headJson: String) {
        val body = ByteArrayOutputStream()
    }

    private fun serve(request: PendingRequest) {
        val id = request.id
        try {
            val head = JSONObject(request.headJson)
            val method = head.optString("method", "GET")
            val path = head.optString("path", "/")
            val headers = HashMap<String, String>()
            head.optJSONObject("headers")?.let { obj ->
                obj.keys().forEach { k -> headers[k] = obj.optString(k, "") }
            }

            val body = request.body.toByteArray()

            val response = forward(method, path, headers, body)

            val headJson = JSONObject().apply {
                put("status", response.status)
                val h = JSONObject()
                response.headers.forEach { (k, v) -> h.put(k, v) }
                put("headers", h)
            }

            sendFrame(T_RESP_HEAD, id, headJson.toString().toByteArray())

            if (response.body.isNotEmpty()) {
                // HLS 分片可能几百 KB，切片发
                var offset = 0
                val chunk = 64 * 1024
                while (offset < response.body.size) {
                    val size = minOf(chunk, response.body.size - offset)
                    val slice = ByteArray(size)
                    System.arraycopy(response.body, offset, slice, 0, size)
                    sendFrame(T_RESP_BODY, id, slice)
                    offset += size
                }
            }

            sendFrame(T_RESP_END, id, ByteArray(0))
        } catch (e: Exception) {
            Log.w(TAG, "serve #$id failed: ${e.message}")
            try {
                sendFrame(T_ERROR, id, (e.message ?: "代理失败").toByteArray())
                sendFrame(T_RESP_END, id, ByteArray(0))
            } catch (_: Exception) {
            }
        }
    }

    private class LocalResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    /**
     * 直接跟自己进程里的 HttpServer 说话。
     *
     * 不用 HttpURLConnection：它对「Content-Length 已知 + Connection: close」的
     * 场景反而更啰嗦，而我们完全清楚服务端的响应格式。
     */
    private fun forward(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): LocalResponse {
        Socket().use { client ->
            client.connect(InetSocketAddress("127.0.0.1", localPort), 5_000)
            client.soTimeout = 15_000

            val sb = StringBuilder()
            sb.append("$method $path HTTP/1.1\r\n")
            sb.append("Host: 127.0.0.1\r\n")
            sb.append("Connection: close\r\n")
            headers.forEach { (name, value) ->
                // 只转必要头，Host / Connection 由我们自己定
                if (name.equals("host", true) || name.equals("connection", true)) return@forEach
                sb.append(name).append(": ").append(value).append("\r\n")
            }
            if (body.isNotEmpty()) {
                sb.append("Content-Length: ").append(body.size).append("\r\n")
            }
            sb.append("\r\n")

            val out = client.getOutputStream()
            out.write(sb.toString().toByteArray())
            if (body.isNotEmpty()) out.write(body)
            out.flush()

            val input = BufferedInputStream(client.getInputStream(), 64 * 1024)
            val head = readHeaderBlock(input) ?: throw IllegalStateException("本地服务无响应")

            val lines = head.split("\r\n").filter { it.isNotEmpty() }
            val status = lines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 502

            val responseHeaders = LinkedHashMap<String, String>()
            var contentLength = -1
            lines.drop(1).forEach { line ->
                val index = line.indexOf(':')
                if (index > 0) {
                    val name = line.substring(0, index).trim()
                    val value = line.substring(index + 1).trim()
                    responseHeaders[name] = value
                    if (name.equals("Content-Length", true)) contentLength = value.toIntOrNull() ?: -1
                }
            }

            val data = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == contentLength) buf else buf.copyOf(read)
            } else {
                input.readBytes()
            }

            return LocalResponse(status, responseHeaders, data)
        }
    }

    private fun readHeaderBlock(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var last4 = 0
        while (buffer.size() < 32 * 1024) {
            val b = input.read()
            if (b < 0) break
            buffer.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        return if (buffer.size() == 0) null else buffer.toString("UTF-8")
    }

    // ---------------------------------------------------------------------
    // 帧发送
    // ---------------------------------------------------------------------

    private fun sendFrame(type: Int, reqId: Int, payload: ByteArray) {
        val frame = ByteArray(5 + payload.size)
        frame[0] = type.toByte()
        frame[1] = ((reqId shr 24) and 0xFF).toByte()
        frame[2] = ((reqId shr 16) and 0xFF).toByte()
        frame[3] = ((reqId shr 8) and 0xFF).toByte()
        frame[4] = (reqId and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 5, payload.size)
        ws?.sendBinary(frame)
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun closeQuietly() {
        connected = false
        try {
            ws?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        ws = null
        socket = null
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun parseUrl(url: String): URI {
        val normalized = when {
            url.startsWith("ws://") || url.startsWith("wss://") -> url
            url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
            url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
            else -> "wss://$url"
        }
        return URI(normalized)
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    // ---------------------------------------------------------------------
    // 极简 WebSocket 客户端（只做 binary + ping/pong，够用且不引依赖）
    // ---------------------------------------------------------------------

    private class WebSocket(
        private val input: InputStream,
        private val output: OutputStream,
        private val host: String,
        private val path: String,
    ) {

        fun handshake() {
            val keyBytes = ByteArray(16)
            SecureRandom().nextBytes(keyBytes)
            val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("User-Agent: CarSentinel/1.0\r\n")
                append("\r\n")
            }
            output.write(request.toByteArray())
            output.flush()

            val head = readHttpHead(input) ?: throw IllegalStateException("中继无响应")
            if (!head.contains(" 101 ")) {
                val first = head.lineSequence().firstOrNull() ?: ""
                throw IllegalStateException("中继拒绝连接：$first")
            }
        }

        fun sendBinary(payload: ByteArray) {
            writeFrame(0x2, payload)
        }

        /** @return null 表示收到关闭帧 */
        fun readBinary(): ByteArray? {
            while (true) {
                val first = input.read()
                if (first < 0) return null
                val second = input.read()
                if (second < 0) return null

                val opcode = first and 0x0F
                val masked = (second and 0x80) != 0
                var length = (second and 0x7F).toLong()

                if (length == 126L) {
                    length = ((input.read() shl 8) or input.read()).toLong()
                } else if (length == 127L) {
                    var value = 0L
                    for (i in 0 until 8) value = (value shl 8) or input.read().toLong()
                    length = value
                }

                if (length < 0 || length > 8L * 1024 * 1024) {
                    throw IllegalStateException("帧长度异常：$length")
                }

                val maskKey = if (masked) {
                    val k = ByteArray(4)
                    readFully(input, k, 4)
                    k
                } else {
                    null
                }

                val payload = ByteArray(length.toInt())
                readFully(input, payload, payload.size)
                if (maskKey != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x2, 0x1, 0x0 -> return payload
                    0x8 -> return null
                    0x9 -> writeFrame(0xA, payload)  // ping → pong
                    else -> continue
                }
            }
        }

        fun close() {
            try {
                writeFrame(0x8, ByteArray(0))
            } catch (_: Exception) {
            }
        }

        private fun writeFrame(opcode: Int, payload: ByteArray) {
            val header = ByteArrayOutputStream()
            header.write(0x80 or opcode)

            val length = payload.size
            when {
                length < 126 -> header.write(0x80 or length)
                length < 65536 -> {
                    header.write(0x80 or 126)
                    header.write((length shr 8) and 0xFF)
                    header.write(length and 0xFF)
                }
                else -> {
                    header.write(0x80 or 127)
                    for (i in 7 downTo 0) header.write(((length.toLong() shr (8 * i)) and 0xFF).toInt())
                }
            }

            val mask = ByteArray(4)
            SecureRandom().nextBytes(mask)
            header.write(mask, 0, 4)

            val masked = ByteArray(length)
            for (i in 0 until length) {
                masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }

            synchronized(output) {
                output.write(header.toByteArray())
                output.write(masked)
                output.flush()
            }
        }

        private fun readHttpHead(stream: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            var last4 = 0
            while (buffer.size() < 16 * 1024) {
                val b = stream.read()
                if (b < 0) break
                buffer.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
            }
            return if (buffer.size() == 0) null else buffer.toString("UTF-8")
        }

        private fun readFully(stream: InputStream, buffer: ByteArray, length: Int) {
            var read = 0
            while (read < length) {
                val n = stream.read(buffer, read, length - read)
                if (n <= 0) throw IllegalStateException("连接被关闭")
                read += n
            }
        }
    }
}

package com.aurora.carsentry

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * RTSP 拉流（H.264 over RTP/TCP interleaved）。
 *
 * 为什么值得单独做一个源：360 环视盒子、后装 4G 记录仪、车规 IPC
 * 几乎全都只暴露一个 `rtsp://…` 地址，Camera2 完全看不到它们。
 * 这类流本身就是 H.264，**不需要解码重编码**，直接把 RTP 拆成 Annex-B
 * 再封 TS 就行，画质零损失。
 *
 * 用 TCP interleaved 而不是 UDP：车机 WiFi 环境下 UDP 丢包会让画面卡死，
 * TCP 至少能靠重传撑住，代价是延迟高一点。
 */
class RtspSource(
    private val url: String,
    private val extra: Map<String, String> = emptyMap(),
) : VideoSource {

    companion object {
        private const val TAG = "RtspSource"
        private const val USER_AGENT = "CarSentinel/1.0"
        private const val READ_TIMEOUT_MS = 15_000
    }

    override val kind: String = SourceType.RTSP

    override val describe: String
        get() = "RTSP · " + url.substringAfter("://").take(48)

    @Volatile private var running = false
    private var thread: Thread? = null
    private var keepAlive: Thread? = null
    private var socket: Socket? = null
    private var output: OutputStream? = null

    private var cseq = 0
    private var sessionId: String? = null

    override fun start(context: Context, sink: VideoSink) {
        if (running) return
        running = true

        thread = Thread({
            try {
                connect(sink)
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "rtsp failed: ${e.message}")
                    sink.onFatal("RTSP 连接失败：${e.message}")
                }
            } finally {
                sink.onStatus(false, "--", 0, 0)
                closeAll()
            }
        }, "rtsp")

        thread?.start()
    }

    override fun stop() {
        running = false
        closeAll()
        keepAlive?.interrupt()
        keepAlive = null
        thread?.interrupt()
        thread = null
    }

    // ---------------------------------------------------------------------
    // 主流程
    // ---------------------------------------------------------------------

    private fun connect(sink: VideoSink) {
        val uri = URI(url)
        val host = uri.host ?: throw IllegalArgumentException("RTSP 地址缺少主机名")
        val port = if (uri.port > 0) uri.port else 554
        val path = (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val baseUri = "rtsp://$host:$port$path"
        val auth = uri.userInfo?.let {
            "Authorization: Basic " + Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) + "\r\n"
        } ?: ""

        Log.i(TAG, "connecting $baseUri")

        val client = Socket()
        client.connect(InetSocketAddress(host, port), 6_000)
        client.soTimeout = READ_TIMEOUT_MS
        client.tcpNoDelay = true
        socket = client

        val input = BufferedInputStream(client.getInputStream(), 64 * 1024)
        val out = client.getOutputStream()
        output = out

        // 1. OPTIONS —— 顺便确认服务端活着
        val options = request(input, out, "OPTIONS", baseUri, auth, null)
        if (options == null || !options.statusLine.contains("200")) {
            throw IllegalStateException("OPTIONS 返回 ${options?.statusLine ?: "无响应"}")
        }

        // 2. DESCRIBE
        val describe = request(
            input, out, "DESCRIBE", baseUri, auth,
            "Accept: application/sdp\r\n"
        ) ?: throw IllegalStateException("DESCRIBE 无响应")

        if (!describe.statusLine.contains("200")) {
            throw IllegalStateException("DESCRIBE 失败：${describe.statusLine}")
        }

        val sdp = describe.body ?: throw IllegalStateException("SDP 为空")
        val track = parseSdp(sdp, baseUri) ?: throw IllegalStateException("SDP 里没有 H.264 视频轨")

        // 参数集（sprop-parameter-sets）先塞给组装器，保证第一个 IDR 能独立解码
        val assembler = H264Util.AccessUnitAssembler { data, key ->
            sink.onFrame(data, System.nanoTime() / 1000, key, System.currentTimeMillis() / 1000)
            frames++
        }

        val rtp = RtpDepacketizer(
            onNal = { buf, offset, len -> assembler.push(buf, offset, len) },
            onAccessUnitEnd = { assembler.flush() },
        )

        if (track.sps != null && track.pps != null) {
            rtp.injectParameterSets(track.sps, track.pps)
        }

        // 3. SETUP
        val setup = request(
            input, out, "SETUP", track.controlUrl, auth,
            "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n"
        ) ?: throw IllegalStateException("SETUP 无响应")

        if (!setup.statusLine.contains("200")) {
            throw IllegalStateException("SETUP 失败：${setup.statusLine}")
        }

        sessionId = setup.headers["session"]?.substringBefore(';')?.trim()
        val interleaved = setup.headers["transport"]
            ?.substringAfter("interleaved=", "")
            ?.substringBefore(';')
            ?.trim()

        val channels = interleaved?.split('-')?.mapNotNull { it.toIntOrNull() }
        val rtpChannel = channels?.getOrNull(0) ?: 0

        Log.i(TAG, "session=$sessionId rtpChannel=$rtpChannel")

        // 4. PLAY
        val playHeaders = StringBuilder()
        sessionId?.let { playHeaders.append("Session: $it\r\n") }
        playHeaders.append("Range: npt=0.000-\r\n")

        val play = request(input, out, "PLAY", baseUri, auth, playHeaders.toString())
            ?: throw IllegalStateException("PLAY 无响应")

        if (!play.statusLine.contains("200")) {
            throw IllegalStateException("PLAY 失败：${play.statusLine}")
        }

        sink.onStatus(true, track.resolution.ifBlank { "H.264" }, 25, 0)
        startKeepAlive(input, out, baseUri, auth)

        // 5. 收数据：TCP interleaved 格式为 '$' + channel(1B) + length(2B BE) + RTP
        val header = ByteArray(4)
        val buffer = ByteArray(96 * 1024)

        while (running) {
            if (!readFully(input, header, 4)) break
            if (header[0] != '$'.code.toByte()) {
                // 不是 interleaved 帧：可能是服务端插播的响应，丢到行边界
                skipLine(input)
                continue
            }
            val channel = header[1].toInt() and 0xFF
            val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (length <= 0 || length > buffer.size) {
                if (!skipBytes(input, length)) break
                continue
            }
            if (!readFully(input, buffer, length)) break
            if (channel == rtpChannel) {
                try {
                    rtp.push(buffer, 0, length)
                } catch (e: Exception) {
                    Log.w(TAG, "rtp parse: ${e.message}")
                }
            }
        }

        assembler.flush()
        Log.i(TAG, "rtsp stream ended, frames=$frames")
    }

    private var frames = 0L

    // ---------------------------------------------------------------------
    // RTSP 请求 / 响应
    // ---------------------------------------------------------------------

    private class Response(
        val statusLine: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    private fun request(
        input: InputStream,
        out: OutputStream,
        method: String,
        uri: String,
        auth: String,
        extraHeaders: String?,
    ): Response? {
        val id = ++cseq
        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: $id\r\n")
        sb.append("User-Agent: $USER_AGENT\r\n")
        sb.append(auth)
        sessionId?.let { sb.append("Session: $it\r\n") }
        if (extraHeaders != null) sb.append(extraHeaders)
        sb.append("\r\n")

        out.write(sb.toString().toByteArray())
        out.flush()

        return readResponse(input)
    }

    private fun readResponse(input: InputStream): Response? {
        // 跳过可能夹在中间的 interleaved 帧
        var guard = 0
        while (guard++ < 8) {
            input.mark(1)
            val first = input.read()
            if (first < 0) return null
            if (first != '$'.code) {
                input.reset()
                break
            }
            val ch = input.read()
            val len = (input.read() shl 8) or input.read()
            if (len < 0) return null
            skipBytes(input, len)
            if (ch < 0) return null
        }

        val head = ByteArrayOutputStream()
        var last4 = 0
        while (head.size() < 32 * 1024) {
            val b = input.read()
            if (b < 0) break
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        if (head.size() == 0) return null

        val lines = head.toString("UTF-8").split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val headers = HashMap<String, String>()
        var contentLength = 0
        lines.drop(1).forEach { line ->
            val index = line.indexOf(':')
            if (index > 0) {
                val key = line.substring(0, index).trim().lowercase()
                val value = line.substring(index + 1).trim()
                headers[key] = value
                if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
            }
        }

        var body: String? = null
        if (contentLength > 0 && contentLength < 1024 * 1024) {
            val data = ByteArray(contentLength)
            if (readFully(input, data, contentLength)) {
                body = String(data, Charsets.UTF_8)
            }
        }

        return Response(lines.first(), headers, body)
    }

    // ---------------------------------------------------------------------
    // SDP
    // ---------------------------------------------------------------------

    private class Track(val controlUrl: String, val resolution: String, val sps: ByteArray?, val pps: ByteArray?)

    private fun parseSdp(sdp: String, baseUri: String): Track? {
        var inVideo = false
        var sawH264 = false
        var control: String? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var resolution = ""

        sdp.split("\n").map { it.trim() }.forEach { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("m=") -> {
                    inVideo = line.startsWith("m=video")
                }

                inVideo && line.startsWith("a=rtpmap:") && line.contains("H264", ignoreCase = true) -> {
                    sawH264 = true
                }

                inVideo && line.startsWith("a=fmtp:") && line.contains("sprop-parameter-sets") -> {
                    val value = line.substringAfter("sprop-parameter-sets=").substringBefore(';').trim()
                    val parts = value.split(',')
                    if (parts.size >= 2) {
                        try {
                            sps = Base64.decode(parts[0], Base64.DEFAULT)
                            pps = Base64.decode(parts[1], Base64.DEFAULT)
                        } catch (e: Exception) {
                            Log.w(TAG, "sprop decode failed: ${e.message}")
                        }
                    }
                }

                inVideo && line.startsWith("a=control:") && control == null -> {
                    control = line.substringAfter("a=control:").trim()
                }

                inVideo && line.startsWith("a=framesize:") -> {
                    // a=framesize:96 1280-720
                    val spec = line.substringAfter(' ').trim()
                    resolution = spec.replace('-', '×')
                }
            }
        }

        if (!sawH264) return null

        val controlUrl = when {
            control == null || control == "*" -> baseUri
            control!!.startsWith("rtsp://") -> control!!
            else -> baseUri.trimEnd('/') + "/" + control!!.trimStart('/')
        }

        return Track(controlUrl, resolution, sps, pps)
    }

    // ---------------------------------------------------------------------
    // 保活 & 收尾
    // ---------------------------------------------------------------------

    private fun startKeepAlive(input: InputStream, out: OutputStream, uri: String, auth: String) {
        keepAlive?.interrupt()
        keepAlive = Thread({
            while (running) {
                try {
                    Thread.sleep(20_000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (!running) return@Thread
                try {
                    // 服务端不需要读响应，但必须发，否则 session 超时
                    val id = ++cseq
                    val sb = StringBuilder()
                    sb.append("OPTIONS $uri RTSP/1.0\r\n")
                    sb.append("CSeq: $id\r\n")
                    sb.append("User-Agent: $USER_AGENT\r\n")
                    sb.append(auth)
                    sessionId?.let { sb.append("Session: $it\r\n") }
                    sb.append("\r\n")
                    out.write(sb.toString().toByteArray())
                    out.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "keepalive failed: ${e.message}")
                    return@Thread
                }
            }
        }, "rtsp-keepalive")
        keepAlive?.isDaemon = true
        keepAlive?.start()
    }

    private fun closeAll() {
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        output = null
        socket = null
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun skipBytes(input: InputStream, length: Int): Boolean {
        var remaining = length
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (n <= 0) return false
            remaining -= n
        }
        return true
    }

    private fun skipLine(input: InputStream) {
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) return
        }
    }
}

// =============================================================================
// RTP → NAL
// =============================================================================

/**
 * H.264 的 RTP 拆包（RFC 6184）：单 NAL / STAP-A / FU-A 三种形态。
 *
 * 车机和盒子上基本都是 FU-A（大帧被切片），所以这条路必须稳：
 * FU-A 的起始片里重建 NAL header（FU indicator 的 F|NRI + FU header 的 type）。
 */
class RtpDepacketizer(
    private val onNal: (ByteArray, Int, Int) -> Unit,
    private val onAccessUnitEnd: () -> Unit,
) {

    private val fuBuffer = ByteArrayOutputStream(64 * 1024)
    private var fuActive = false

    /** 把 SDP 里带的参数集先吐出去，保证第一帧 IDR 可独立解码 */
    fun injectParameterSets(sps: ByteArray, pps: ByteArray) {
        onNal(sps, 0, sps.size)
        onNal(pps, 0, pps.size)
    }

    fun push(packet: ByteArray, offset: Int, length: Int) {
        if (length < 12) return

        val byte0 = packet[offset].toInt() and 0xFF
        val byte1 = packet[offset + 1].toInt() and 0xFF
        val marker = (byte1 and 0x80) != 0
        val cc = byte0 and 0x0F

        var headerLength = 12 + cc * 4
        if ((byte0 and 0x10) != 0 && length >= headerLength + 4) {
            val extensionWords = ((packet[offset + headerLength + 2].toInt() and 0xFF) shl 8) or
                (packet[offset + headerLength + 3].toInt() and 0xFF)
            headerLength += 4 + extensionWords * 4
        }
        if (headerLength >= length) return

        val payloadStart = offset + headerLength
        val payloadLength = length - headerLength
        if (payloadLength <= 0) return

        val nalHeader = packet[payloadStart].toInt() and 0xFF
        val type = nalHeader and 0x1F

        when {
            // 单个 NAL
            type in 1..23 -> {
                onNal(packet, payloadStart, payloadLength)
            }

            // STAP-A：一个包里塞多个小 NAL
            type == 24 -> {
                var pos = payloadStart + 1
                val end = payloadStart + payloadLength
                while (pos + 2 <= end) {
                    val size = ((packet[pos].toInt() and 0xFF) shl 8) or
                        (packet[pos + 1].toInt() and 0xFF)
                    pos += 2
                    if (size <= 0 || pos + size > end) break
                    onNal(packet, pos, size)
                    pos += size
                }
            }

            // FU-A：分片
            type == 28 -> {
                if (payloadLength < 2) return
                val fuHeader = packet[payloadStart + 1].toInt() and 0xFF
                val start = (fuHeader and 0x80) != 0
                val end = (fuHeader and 0x40) != 0
                val nalType = fuHeader and 0x1F

                if (start) {
                    fuBuffer.reset()
                    fuActive = true
                    // 重建 NAL header：F 和 NRI 来自 FU indicator
                    val rebuilt = (nalHeader and 0xE0) or nalType
                    fuBuffer.write(rebuilt)
                }

                if (fuActive) {
                    fuBuffer.write(packet, payloadStart + 2, payloadLength - 2)
                    if (end) {
                        val nal = fuBuffer.toByteArray()
                        fuBuffer.reset()
                        fuActive = false
                        onNal(nal, 0, nal.size)
                    }
                }
            }

            // 25/26/27 是交错/分片扩展，车机上不会出现，直接忽略
            else -> {
            }
        }

        if (marker) onAccessUnitEnd()
    }
}

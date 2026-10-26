package com.aurora.carsentry

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * 车机端 HTTP 服务（手写，不引框架）。
 *
 * 只需要支撑 8 类请求：info / recordings / events / control 四个接口，
 * 加 m3u8、ts 两种媒体文件。用 ServerSocket 直接写比引 Ktor 或 NanoHTTPD 更轻，
 * 车机上少一个依赖就少一个坑。
 *
 * 并发模型：固定 8 线程。手机一次最多拉 4 路直播，8 个线程够用。
 */
class HttpServer(
    private val port: Int,
    private val root: File,
    private val onControl: (action: String, cameraId: String?, enabled: Boolean?) -> Unit,
) {

    companion object {
        private const val TAG = "HttpServer"
    }

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(8)

    @Volatile private var running = false

    private val mimeType = mapOf(
        "m3u8" to "application/vnd.apple.mpegurl",
        "ts" to "video/mp2t",
        "json" to "application/json; charset=utf-8",
        "jpg" to "image/jpeg",
    )

    fun start() {
        if (running) return

        Thread({
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                running = true
                Log.i(TAG, "listening on 0.0.0.0:$port")
                while (running) {
                    val client = try {
                        socket.accept()
                    } catch (e: Exception) {
                        break
                    }
                    pool.execute { handle(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "server error: ${e.message}", e)
                running = false
            }
        }, "sentry-http").start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------------
    // 请求处理
    // ---------------------------------------------------------------------

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 8_000
            val input = socket.getInputStream()
            val output = BufferedOutputStream(socket.getOutputStream())

            val header = readHeader(input) ?: return
            val lines = header.split("\r\n")
            if (lines.isEmpty()) return

            val parts = lines[0].split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]

            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))

            when {
                method == "GET" && path == "/api/v1/info" -> {
                    writeBody(output, 200, "application/json; charset=utf-8", SentryState.snapshotJson().toString().toByteArray())
                }

                method == "GET" && path == "/api/v1/recordings" -> {
                    writeBody(output, 200, "application/json; charset=utf-8", recordingsJson(query).toByteArray())
                }

                method == "GET" && path == "/api/v1/events" -> {
                    val since = query["since"]?.toLongOrNull()
                    writeBody(output, 200, "application/json; charset=utf-8", eventsJson(since).toByteArray())
                }

                method == "POST" && path == "/api/v1/control" -> {
                    val body = readBody(input, header)
                    handleControl(body, output)
                }

                method == "GET" && (path.startsWith("/live/") || path.startsWith("/rec/")) -> {
                    serveFile(output, path, header)
                }

                else -> {
                    writeBody(output, 404, "application/json; charset=utf-8", """{"error":"not found"}""".toByteArray())
                }
            }

            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "handle error: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun recordingsJson(query: Map<String, String>): String {
        val from = query["from"]?.toLongOrNull()
        val to = query["to"]?.toLongOrNull()
        val camera = query["camera"]
        val limit = query["limit"]?.toIntOrNull() ?: 300

        val list = SentryState.clips().asSequence()
            .filter { from == null || it.startAt >= from }
            .filter { to == null || it.startAt < to }
            .filter { camera == null || camera.isEmpty() || it.cameraId == camera }
            .take(limit)
            .toList()

        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        return JSONObject().put("clips", array).toString()
    }

    private fun eventsJson(since: Long?): String {
        val array = JSONArray()
        SentryState.events(since).forEach { array.put(it.toJson()) }
        return JSONObject().put("events", array).toString()
    }

    private fun handleControl(body: String?, output: OutputStream) {
        if (body.isNullOrBlank()) {
            writeBody(output, 400, "application/json; charset=utf-8", """{"error":"empty body"}""".toByteArray())
            return
        }
        try {
            val json = JSONObject(body)
            val action = json.optString("action", "")
            val cameraId = if (json.has("cameraId") && !json.isNull("cameraId")) json.getString("cameraId") else null
            val enabled = if (json.has("enabled")) json.getBoolean("enabled") else null

            onControl(action, cameraId, enabled)

            writeBody(output, 200, "application/json; charset=utf-8", """{"ok":true}""".toByteArray())
        } catch (e: Exception) {
            writeBody(output, 400, "application/json; charset=utf-8", """{"error":"bad json"}""".toByteArray())
        }
    }

    // ---------------------------------------------------------------------
    // 静态媒体
    // ---------------------------------------------------------------------

    private fun serveFile(output: OutputStream, path: String, header: String) {
        val relative = path.trimStart('/')
        if (relative.contains("..")) {
            writeBody(output, 403, "application/json; charset=utf-8", """{"error":"forbidden"}""".toByteArray())
            return
        }

        val file = File(root, relative)
        if (!file.isFile) {
            writeBody(output, 404, "application/json; charset=utf-8", """{"error":"no such file"}""".toByteArray())
            return
        }

        val ext = file.extension.lowercase()
        val type = mimeType[ext] ?: "application/octet-stream"
        val total = file.length()

        // playlist 不能被缓存，否则手机端一直看到旧分片
        val cacheHeader = if (ext == "m3u8") "Cache-Control: no-cache\r\n" else "Cache-Control: max-age=60\r\n"

        val range = parseRange(header, total)
        if (range != null) {
            val (start, end) = range
            val length = end - start + 1
            val head = buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Type: $type\r\n")
                append("Content-Length: $length\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Range: bytes $start-$end/$total\r\n")
                append(cacheHeader)
                append("Connection: close\r\n\r\n")
            }
            output.write(head.toByteArray())
            file.inputStream().use { stream ->
                var toSkip = start
                while (toSkip > 0) {
                    val skipped = stream.skip(toSkip)
                    if (skipped <= 0) break
                    toSkip -= skipped
                }
                var remaining = length
                val buffer = ByteArray(16 * 1024)
                while (remaining > 0) {
                    val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            return
        }

        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: $total\r\n")
            append("Accept-Ranges: bytes\r\n")
            append(cacheHeader)
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray())
        file.inputStream().use { stream ->
            stream.copyTo(output, 16 * 1024)
        }
    }

    private fun parseRange(header: String, total: Long): Pair<Long, Long>? {
        val line = header.split("\r\n").firstOrNull { it.startsWith("Range:", ignoreCase = true) } ?: return null
        val value = line.substringAfter("bytes=").trim()
        val startText = value.substringBefore('-').trim()
        val endText = value.substringAfter('-').trim()
        val start = startText.toLongOrNull() ?: 0L
        val end = endText.toLongOrNull() ?: (total - 1)
        if (start > end || start >= total) return null
        return start to minOf(end, total - 1)
    }

    // ---------------------------------------------------------------------
    // 底层读写
    // ---------------------------------------------------------------------

    private fun readHeader(input: InputStream): String? {
        val buffer = StringBuilder()
        var last4 = 0
        while (buffer.length < 16 * 1024) {
            val b = input.read()
            if (b < 0) break
            buffer.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        return if (buffer.isEmpty()) null else buffer.toString()
    }

    private fun readBody(input: InputStream, header: String): String? {
        val lengthLine = header.split("\r\n")
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?: return null
        val length = lengthLine.substringAfter(':').trim().toIntOrNull() ?: return null
        if (length <= 0 || length > 64 * 1024) return null
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(data, 0, read, Charsets.UTF_8)
    }

    private fun writeBody(output: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "OK"
        }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray())
        output.write(body)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            map[key] = value
        }
        return map
    }
}

package com.aurora.carsentry

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 原始 H.264 裸流（Annex-B）接入。
 *
 * 后装车机 / 环视盒子的「厂商私有接口」绝大多数最终都会落到这三种形态之一：
 *
 *   tcp://192.168.1.10:9000   厂商 App 或盒子开一个 TCP 端口，推 Annex-B 裸流
 *   udp://0.0.0.0:9000        同上，但走 UDP（每个包一个或几个 NAL）
 *   /data/vendor/cam0.h264    设备节点 / FIFO / 正在写的文件
 *
 * 只要能读到字节，剩下的切帧、拼帧、切片全部由 [H264Util] 处理，
 * 不需要解码重编码 —— 画质零损失，CPU 占用几乎为零。
 */
class RawH264Source(
    private val target: String,
    private val extra: Map<String, String> = emptyMap(),
) : VideoSource {

    companion object {
        private const val TAG = "RawH264Source"
    }

    override val kind: String = SourceType.RAW

    override val describe: String
        get() {
            val fps = extra["fps"]?.toIntOrNull() ?: 25
            val short = when {
                target.startsWith("tcp://") -> "TCP 裸流"
                target.startsWith("udp://") -> "UDP 裸流"
                else -> "文件/管道"
            }
            return "$short · ${fps}fps"
        }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: Socket? = null
    private var udpSocket: DatagramSocket? = null
    private var input: InputStream? = null

    override fun start(context: Context, sink: VideoSink) {
        if (running) return
        running = true

        val fps = (extra["fps"]?.toIntOrNull() ?: 25).coerceIn(1, 60)

        thread = Thread({
            var frameIndex = 0L
            val assembler = H264Util.AccessUnitAssembler { data, key ->
                val ptsUs = frameIndex * 1_000_000L / fps
                frameIndex++
                sink.onFrame(data, ptsUs, key, System.currentTimeMillis() / 1000)
            }
            val reader = H264Util.StreamReader { buf, offset, len ->
                assembler.push(buf, offset, len)
            }

            sink.onStatus(true, "H.264 裸流", fps, 0)

            try {
                when {
                    target.startsWith("tcp://") -> readTcp(target.removePrefix("tcp://"), reader, sink)
                    target.startsWith("udp://") -> readUdp(target.removePrefix("udp://"), reader, sink)
                    else -> readFile(target, reader, sink)
                }
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "read failed: ${e.message}")
                    sink.onFatal("裸流读取中断：${e.message}")
                }
            } finally {
                assembler.flush()
                sink.onStatus(false, "--", 0, 0)
            }
        }, "raw-h264")

        thread?.start()
    }

    override fun stop() {
        running = false
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        thread?.interrupt()
        thread = null
    }

    // ---------------------------------------------------------------------

    private fun readTcp(address: String, reader: H264Util.StreamReader, sink: VideoSink) {
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "9000").toIntOrNull() ?: 9000
        Log.i(TAG, "connecting tcp $host:$port")

        val client = Socket()
        client.connect(InetSocketAddress(host, port), 5_000)
        client.tcpNoDelay = true
        socket = client
        input = client.getInputStream()

        sink.onStatus(true, "TCP $host:$port", 25, 0)
        pump(client.getInputStream(), reader)
    }

    private fun readUdp(address: String, reader: H264Util.StreamReader, sink: VideoSink) {
        val host = address.substringBefore(':').ifBlank { "0.0.0.0" }
        val port = address.substringAfter(':', "9000").toIntOrNull() ?: 9000

        val socket = DatagramSocket(port)
        socket.broadcast = true
        udpSocket = socket
        Log.i(TAG, "udp listening $port")

        sink.onStatus(true, "UDP $port", 25, 0)

        val buffer = ByteArray(64 * 1024)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            reader.feed(packet.data, packet.length)
        }
    }

    private fun readFile(path: String, reader: H264Util.StreamReader, sink: VideoSink) {
        val file = File(path)
        if (!file.exists()) {
            sink.onFatal("文件不存在：$path")
            return
        }

        // 普通文件从末尾追（-1 表示从头读整个历史，适合取证场景）
        val fromEnd = extra["fromEnd"]?.toBoolean() ?: true
        val stream = FileInputStream(file)
        input = stream
        if (fromEnd && file.isFile) {
            try {
                stream.channel.position(file.length())
            } catch (_: Exception) {
            }
        }

        sink.onStatus(true, file.name, 25, 0)
        pump(stream, reader)
    }

    private fun pump(stream: InputStream, reader: H264Util.StreamReader) {
        val buffer = ByteArray(32 * 1024)
        while (running) {
            val read = stream.read(buffer)
            if (read < 0) {
                // TCP 对端关了：给上层一个明确的失败信号，走重连
                if (running) throw IllegalStateException("对端关闭连接")
                return
            }
            if (read > 0) reader.feed(buffer, read)
        }
    }
}

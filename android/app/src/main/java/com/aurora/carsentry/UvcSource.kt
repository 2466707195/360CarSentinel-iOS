package com.aurora.carsentry

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * USB 摄像头（UVC）直连采集。
 *
 * 什么时候需要它：车机把 UVC 摄像头**没有**注册进 Camera2 的时候。
 * 很多后装安卓大屏就是这样 —— 插上 USB 摄像头，系统里根本没有 cameraId，
 * 但 `/dev/bus/usb` 下是有的，只能自己按 UVC 协议去谈。
 *
 * 实现要点（这几条是坑位，改动前先读）：
 *   1. **格式只用 MJPEG**。YUV 未压缩格式在 USB 2.0 上带宽根本不够 720p25。
 *   2. 协商走 PROBE → COMMIT 两段式，`wLength` 先试 34 字节（UVC 1.1/1.5），
 *      失败再退 26 字节（UVC 1.0）。
 *   3. **进程必须选带等时端点的 alternate setting**，选错了 open 会成功但永远没数据。
 *   4. Android 的等时传输不返回「每个包实际多长」，所以这里按
 *      maxPacketSize 切包、剥掉 UVC payload header，再用 JPEG 的
 *      SOI/EOI 标记自愈 —— 一旦错位能自动重新同步，不会一路花屏到底。
 */
class UvcSource(private val deviceName: String) : VideoSource {

    companion object {
        private const val TAG = "UvcSource"
        private const val MAX_DECODE_WIDTH = 960
        private const val URB_PACKETS = 16
        private const val URB_COUNT = 4

        private const val UVC_CLASS = UsbConstants.USB_CLASS_VIDEO // 14

        /** 找出所有 UVC 摄像头，给 UI 选源用 */
        fun list(context: Context): List<Triple<String, String, String>> {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return emptyList()
            return manager.deviceList.values
                .filter { device -> (0 until device.interfaceCount).any { isCameraInterface(device.getInterface(it)) } }
                .map { device ->
                    val name = device.productName ?: device.deviceName
                    Triple(device.deviceName, name, describeDevice(device))
                }
                .sortedBy { it.second }
        }

        fun isCameraInterface(intf: UsbInterface): Boolean =
            intf.interfaceClass == UVC_CLASS

        private fun describeDevice(device: UsbDevice): String = buildString {
            append("VID ${Integer.toHexString(device.vendorId)} PID ${Integer.toHexString(device.productId)}")
            if (device.manufacturerName != null) append(" · ").append(device.manufacturerName)
        }
    }

    override val kind: String = SourceType.UVC

    override val async: Boolean = true

    override fun isAlive(): Boolean = running

    override val describe: String
        get() = "USB · " + deviceName.substringAfterLast('/')

    @Volatile private var running = false
    private var thread: Thread? = null
    private var worker: Thread? = null
    private var connection: UsbDeviceConnection? = null

    private val frameQueue = ArrayBlockingQueue<JpegFrame>(2)

    private class JpegFrame(val data: ByteArray, val ptsUs: Long)

    override fun start(context: Context, sink: VideoSink) {
        if (running) return
        running = true

        thread = Thread({
            try {
                run(context, sink)
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "uvc failed: ${e.message}", e)
                    sink.onFatal("USB 摄像头失败：${e.message}")
                }
            } finally {
                sink.onStatus(false, "--", 0, 0)
                closeQuietly()
            }
        }, "uvc")

        thread?.start()
    }

    override fun stop() {
        running = false
        closeQuietly()
        thread?.interrupt()
        worker?.interrupt()
        thread = null
        worker = null
    }

    // ---------------------------------------------------------------------

    private fun run(context: Context, sink: VideoSink) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull { it.deviceName == deviceName }
            ?: throw IllegalStateException("USB 设备不在线（可能已拔出）")

        if (!UsbPermission.ensure(context, manager, device)) {
            throw IllegalStateException("用户未授权访问该 USB 设备")
        }

        val conn = manager.openDevice(device) ?: throw IllegalStateException("openDevice 失败")
        connection = conn

        val info = UvcDescriptor.parse(conn, device)
            ?: throw IllegalStateException("解析 UVC 描述符失败，可能不是标准 UVC 摄像头")

        Log.i(TAG, "descriptor: $info")

        val vsInterface = device.getInterface(info.interfaceIndex)
        if (!conn.claimInterface(vsInterface, true)) {
            throw IllegalStateException("claimInterface 失败")
        }

        // 关键：必须选「带等时端点」的那个 alternate setting
        val streamInterface = findAlternate(device, info) ?: throw IllegalStateException("找不到可用的视频流端点")
        if (!conn.setInterface(streamInterface)) {
            throw IllegalStateException("setInterface 失败（alt=${streamInterface.alternateSetting}）")
        }

        val endpoint = streamInterface.getEndpoint(0)
        val maxPacket = endpoint.maxPacketSize.coerceAtLeast(512)
        Log.i(TAG, "stream alt=${streamInterface.alternateSetting} ep=0x${Integer.toHexString(endpoint.address)} maxPacket=$maxPacket")

        // 协商格式
        if (!info.negotiate(conn)) {
            throw IllegalStateException("PROBE/COMMIT 协商失败（格式 ${info.formatIndex}/${info.frameIndex}）")
        }

        // 解码 + 编码放到独立线程，别拖慢等时传输的回收
        startWorker(sink, info.width, info.height)

        val width = info.width
        val height = info.height
        sink.onStatus(true, "${width}×$height", info.fps, 0)

        streamLoop(conn, endpoint, maxPacket, sink)
    }

    // ---------------------------------------------------------------------
    // 等时传输
    // ---------------------------------------------------------------------

    private fun streamLoop(
        conn: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        maxPacket: Int,
        sink: VideoSink,
    ) {
        val urbSize = maxPacket * URB_PACKETS
        val requests = ArrayList<UsbRequest>(URB_COUNT)
        val buffers = ArrayList<ByteBuffer>(URB_COUNT)

        repeat(URB_COUNT) {
            val request = UsbRequest()
            if (!request.initialize(conn, endpoint)) {
                throw IllegalStateException("UsbRequest.initialize 失败")
            }
            val buffer = ByteBuffer.allocateDirect(urbSize)
            if (!request.queue(buffer, urbSize)) {
                throw IllegalStateException("初步 queue 失败")
            }
            requests.add(request)
            buffers.add(buffer)
        }

        var lastFrameAt = System.currentTimeMillis()
        var lastWarn = 0L

        val assembler = MjpegAssembler(maxPacket) { data, offset, length ->
            lastFrameAt = System.currentTimeMillis()
            pushFrame(data, offset, length, System.nanoTime() / 1000)
        }

        while (running) {
            val completed = conn.requestWait() ?: break
            val index = requests.indexOf(completed)
            if (index < 0) continue

            val buffer = buffers[index]
            val length = try {
                buffer.position()
            } catch (e: Exception) {
                0
            }

            if (length > 0) {
                buffer.position(0)
                val chunk = ByteArray(length)
                buffer.get(chunk, 0, length)
                assembler.feed(chunk, length)
            }

            buffer.clear()
            if (!completed.queue(buffer, urbSize)) {
                Log.w(TAG, "re-queue 失败，停止")
                break
            }

            // 3 秒收不到一帧基本就是选错了格式或 alt setting
            if (System.currentTimeMillis() - lastFrameAt > 8_000) {
                val now = System.currentTimeMillis()
                if (now - lastWarn > 4_000) {
                    lastWarn = now
                    Log.w(TAG, "8 秒没有收到 JPEG 帧，frames=${assembler.frameCount}")
                    sink.onFatal("USB 摄像头没有数据（可能是格式或端点不匹配）")
                }
                break
            }
        }

        requests.forEach {
            try {
                it.cancel()
                it.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun findAlternate(device: UsbDevice, info: UvcDescriptor): UsbInterface? {
        var best: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UVC_CLASS || intf.interfaceSubclass != 2) continue
            // UsbInterface 上没有 interfaceNumber 这个属性，
            // 接口号是 getId()（Kotlin 里就是 .id），这个坑很隐蔽。
            if (intf.id != info.interfaceIndex) continue
            if (intf.endpointCount == 0) continue

            val ep = intf.getEndpoint(0)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_ISOC) continue

            // 选包最大的那个 alt，通常就是满带宽设置
            if (best == null || ep.maxPacketSize > best.getEndpoint(0).maxPacketSize) {
                best = intf
            }
        }
        return best
    }

    // ---------------------------------------------------------------------
    // 解码 + 编码
    // ---------------------------------------------------------------------

    private fun startWorker(sink: VideoSink, width: Int, height: Int) {
        worker = Thread({
            val encoder = YuvEncoder(fps = 25, bitrate = 1_800_000)
            var frameIndex = 0
            sink.onStatus(true, "${width}×$height", 25, 0)

            while (running) {
                val frame = try {
                    frameQueue.poll(500, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue

                val decoded = JpegDecoder.decode(frame.data, 0, frame.data.size, MAX_DECODE_WIDTH)
                    ?: continue

                try {
                    encoder.encode(
                        decoded.argb,
                        decoded.width,
                        decoded.height,
                        frame.ptsUs,
                        frameIndex % 50 == 0,
                    ) { annexB, pts, key ->
                        sink.onFrame(annexB, pts, key, System.currentTimeMillis() / 1000)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "encode failed: ${e.message}")
                }
                frameIndex++

                if (frameIndex % 50 == 0) {
                    sink.onStatus(true, "${decoded.width}×${decoded.height}", 25, 1800)
                }
            }

            encoder.release()
        }, "uvc-encode")
        worker?.start()
    }

    private fun pushFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long) {
        val copy = ByteArray(length)
        System.arraycopy(data, offset, copy, 0, length)

        // 队列满了丢最旧的：宁可掉帧，不能积压成几秒延迟
        if (!frameQueue.offer(JpegFrame(copy, ptsUs))) {
            frameQueue.poll()
            frameQueue.offer(JpegFrame(copy, ptsUs))
        }
    }

    private fun closeQuietly() {
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
        frameQueue.clear()
    }
}

// =============================================================================
// UVC 描述符解析 + 协商
// =============================================================================

/**
 * UVC 摄像头的能力描述。
 *
 * Android 的 `UsbInterface` 只给标准描述符，类特定描述符（格式 / 分辨率表）
 * 拿不到，只能自己发 `GET_DESCRIPTOR(CONFIGURATION)` 把整块原始描述符读回来再解析。
 * 这是 UVCCamera 那类库的通用做法。
 */
class UvcDescriptor(
    val interfaceIndex: Int,
    val formatIndex: Int,
    val frameIndex: Int,
    val width: Int,
    val height: Int,
    val interval100ns: Int,
    val maxFrameSize: Int,
    val maxPayload: Int,
) {

    companion object {
        private const val TAG = "UvcDescriptor"

        private const val DESC_INTERFACE = 4
        private const val DESC_ENDPOINT = 5
        private const val DESC_CS_INTERFACE = 0x24

        private const val VS_FORMAT_MJPEG = 0x06
        private const val VS_FRAME_MJPEG = 0x07

        // UVC 类请求。放在这里而不是 UvcSource —— 协商逻辑（negotiate/setCur）
        // 属于本类，常量跟着逻辑走，别散在另一个类的伴生对象里。
        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val VS_PROBE_CONTROL = 0x01
        private const val VS_COMMIT_CONTROL = 0x02

        fun parse(conn: UsbDeviceConnection, device: UsbDevice): UvcDescriptor? {
            val buffer = ByteArray(4096)
            // GET_DESCRIPTOR: bmRequestType=0x80, bRequest=0x06, wValue=(2<<8)|0
            val read = conn.controlTransfer(0x80, 0x06, 0x0200, 0, buffer, buffer.size, 2000)
            if (read < 9) {
                Log.w(TAG, "读取配置描述符失败：$read")
                return null
            }

            var interfaceIndex = -1
            var inStreaming = false
            var formatIndex = -1
            var frameIndex = -1
            var width = 0
            var height = 0
            var interval = 0
            var maxFrameSize = 0
            var maxPayload = 0

            var bestArea = 0

            var i = 0
            while (i + 2 < read) {
                val length = buffer[i].toInt() and 0xFF
                if (length < 2) break
                if (i + length > read) break
                val type = buffer[i + 1].toInt() and 0xFF

                when (type) {
                    DESC_INTERFACE -> {
                        val number = buffer[i + 2].toInt() and 0xFF
                        val ifClass = buffer[i + 5].toInt() and 0xFF
                        val ifSub = buffer[i + 6].toInt() and 0xFF
                        inStreaming = ifClass == UVC_CLASS_CONST && ifSub == 2
                        if (inStreaming && interfaceIndex < 0) interfaceIndex = number
                    }

                    DESC_ENDPOINT -> {
                        if (inStreaming) {
                            maxPayload = maxOf(maxPayload, u16(buffer, i + 4))
                        }
                    }

                    DESC_CS_INTERFACE -> {
                        val subtype = buffer[i + 2].toInt() and 0xFF
                        if (inStreaming && subtype == VS_FORMAT_MJPEG && formatIndex < 0) {
                            formatIndex = buffer[i + 3].toInt() and 0xFF
                        } else if (inStreaming && subtype == VS_FRAME_MJPEG) {
                            val idx = buffer[i + 3].toInt() and 0xFF
                            val w = u16(buffer, i + 5)
                            val h = u16(buffer, i + 7)
                            val bufferSize = u32(buffer, i + 17)
                            val defaultInterval = u32(buffer, i + 21)
                            val area = w * h
                            // 选 1280 宽以内、面积最大的那档
                            if (w in 160..1280 && h > 0 && area > bestArea) {
                                bestArea = area
                                frameIndex = idx
                                width = w
                                height = h
                                interval = if (defaultInterval > 0) defaultInterval else 333_333
                                maxFrameSize = bufferSize
                            }
                        }
                    }
                }

                i += length
            }

            if (interfaceIndex < 0 || formatIndex < 0 || frameIndex < 0) {
                Log.w(
                    TAG,
                    "UVC 描述符不完整 interface=$interfaceIndex format=$formatIndex frame=$frameIndex"
                )
                return null
            }

            // 有些设备不填 dwMaxVideoFrameBufferSize，兜一个够用的值
            if (maxFrameSize <= 0) maxFrameSize = width * height * 3
            if (maxPayload <= 0) maxPayload = 3072

            return UvcDescriptor(
                interfaceIndex = interfaceIndex,
                formatIndex = formatIndex,
                frameIndex = frameIndex,
                width = width,
                height = height,
                interval100ns = interval,
                maxFrameSize = maxFrameSize,
                maxPayload = maxPayload,
            )
        }

        private const val UVC_CLASS_CONST = 14

        private fun u16(buf: ByteArray, offset: Int): Int =
            (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

        private fun u32(buf: ByteArray, offset: Int): Int =
            (buf[offset].toInt() and 0xFF) or
                ((buf[offset + 1].toInt() and 0xFF) shl 8) or
                ((buf[offset + 2].toInt() and 0xFF) shl 16) or
                ((buf[offset + 3].toInt() and 0xFF) shl 24)
    }

    val fps: Int
        get() = if (interval100ns > 0) (10_000_000 / interval100ns).coerceIn(1, 60) else 25

    /**
     * PROBE 写入期望参数 → 读回设备认可的参数 → COMMIT。
     * 设备可能把分辨率改小，所以必须以读回值为准。
     */
    fun negotiate(conn: UsbDeviceConnection): Boolean {
        var length = 34
        var result = setCur(conn, VS_PROBE_CONTROL, length)
        if (result < 0) {
            length = 26
            result = setCur(conn, VS_PROBE_CONTROL, length)
        }
        if (result < 0) {
            Log.w(TAG, "SET_CUR(PROBE) 失败：$result")
            return false
        }

        // wIndex：低字节是接口号，高字节是实体 ID（0 表示接口本身）
        val back = ByteArray(length)
        val got = conn.controlTransfer(
            0xA1, GET_CUR, (VS_PROBE_CONTROL shl 8), interfaceIndex, back, length, 2000
        )
        if (got < 0) {
            Log.w(TAG, "GET_CUR(PROBE) 失败：$got")
            return false
        }

        // 设备返回的 frame index 可能与请求不同，这里只记录不强制
        val actualFormat = if (got > 3) back[2].toInt() and 0xFF else formatIndex
        val actualFrame = if (got > 3) back[3].toInt() and 0xFF else frameIndex
        Log.i(TAG, "probe accepted: format=$actualFormat frame=$actualFrame len=$got")

        val commit = setCur(conn, VS_COMMIT_CONTROL, length)
        if (commit < 0) {
            Log.w(TAG, "SET_CUR(COMMIT) 失败：$commit")
            return false
        }
        return true
    }

    private fun setCur(conn: UsbDeviceConnection, control: Int, length: Int): Int {
        val data = ByteArray(length)
        data[0] = 0x00.toByte() // bmHint
        data[1] = 0x00.toByte()
        data[2] = formatIndex.toByte()
        data[3] = frameIndex.toByte()
        // dwFrameInterval (LE)
        data[4] = (interval100ns and 0xFF).toByte()
        data[5] = ((interval100ns shr 8) and 0xFF).toByte()
        data[6] = ((interval100ns shr 16) and 0xFF).toByte()
        data[7] = ((interval100ns shr 24) and 0xFF).toByte()
        // dwMaxVideoFrameSize
        putInt(data, 18, maxFrameSize)
        // dwMaxPayloadTransferSize
        putInt(data, 22, maxPayload)
        // dwClockFrequency
        putInt(data, 26, 15_000_000)

        return conn.controlTransfer(
            0x21,
            SET_CUR,
            (control shl 8),
            interfaceIndex,
            data,
            length,
            2000,
        )
    }

    private fun putInt(data: ByteArray, offset: Int, value: Int) {
        if (offset + 3 >= data.size) return
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    override fun toString(): String =
        "if=$interfaceIndex fmt=$formatIndex frame=$frameIndex ${width}×$height @${fps}fps maxFrame=$maxFrameSize"
}

// =============================================================================
// MJPEG 帧重组
// =============================================================================

/**
 * 从等时传输里攒出完整的 JPEG 帧。
 *
 * UVC 每个 USB 包开头有一个 payload header（长度由首字节给出），剥掉之后
 * 才是 JPEG 数据。Android 不告诉我们每个包实际有多长，所以按 maxPacketSize
 * 推进；一旦错位，靠 `FF D8` / `FF D9` 重新对齐 —— 这是唯一能在 Java 层
 * 稳定工作的做法，代价是极端情况下会丢一帧。
 */
class MjpegAssembler(
    private val maxPacket: Int,
    private val onFrame: (ByteArray, Int, Int) -> Unit,
) {

    private var buffer = ByteArray(1 shl 19)
    private var size = 0
    private var scanPos = 0
    private var frameStart = -1

    var frameCount = 0
        private set

    fun feed(chunk: ByteArray, length: Int) {
        var pos = 0
        while (pos + 2 <= length) {
            val headerLength = chunk[pos].toInt() and 0xFF
            val packetLength = minOf(maxPacket, length - pos)

            if (headerLength < 2 || headerLength >= packetLength) {
                // 错位：直接放弃这个包的头部推测，逐字节找下一个 SOI
                pos++
                continue
            }

            append(chunk, pos + headerLength, packetLength - headerLength)
            pos += packetLength
        }
        extract()
    }

    private fun append(src: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        if (size + length > buffer.size) {
            var capacity = buffer.size
            while (capacity < size + length) capacity = capacity shl 1
            buffer = buffer.copyOf(capacity)
        }
        System.arraycopy(src, offset, buffer, size, length)
        size += length
    }

    private fun extract() {
        var i = scanPos
        while (i + 1 < size) {
            if (buffer[i] == 0xFF.toByte()) {
                val marker = buffer[i + 1].toInt() and 0xFF
                if (marker == 0xD8 && frameStart < 0) {
                    frameStart = i
                    i += 2
                    continue
                }
                if (marker == 0xD9 && frameStart >= 0) {
                    val length = i + 2 - frameStart
                    onFrame(buffer, frameStart, length)
                    frameCount++

                    val remaining = size - (i + 2)
                    if (remaining > 0) {
                        System.arraycopy(buffer, i + 2, buffer, 0, remaining)
                    }
                    size = remaining
                    scanPos = 0
                    frameStart = -1
                    return
                }
            }
            i++
        }

        scanPos = maxOf(0, i - 1)

        // 缓冲失控（一直没找到 EOI）就重置，避免 OOM
        if (size > 4 * 1024 * 1024) {
            size = 0
            scanPos = 0
            frameStart = -1
        }
    }
}

// =============================================================================
// USB 权限
// =============================================================================

/**
 * 申请 USB 设备访问权限。
 *
 * 系统弹出的是「允许访问该 USB 设备？」对话框，结果通过 PendingIntent
 * 以广播形式回来。Android 12+ 必须给 PendingIntent 加 FLAG_MUTABLE，
 * 否则系统填不进 EXTRA_PERMISSION_GRANTED —— 这个坑会让权限永远拿不到。
 */
object UsbPermission {

    private const val TAG = "UsbPermission"
    private const val ACTION = "com.aurora.carsentry.USB_PERMISSION"

    private val lock = Object()
    private val latches = HashMap<String, CountDownLatch>()
    private val results = HashMap<String, Boolean>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            // 两个分支都显式写 <UsbDevice> 类型参数：
            // 不带类型参数的旧 API 会退化成 Parcelable，`device.deviceName` 就编不过了。
            val device: UsbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            } ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

            synchronized(lock) {
                results[device.deviceName] = granted
                latches.remove(device.deviceName)?.countDown()
            }
        }
    }

    fun ensure(context: Context, manager: UsbManager, device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true

        register(context)

        val latch = CountDownLatch(1)
        synchronized(lock) {
            latches[device.deviceName] = latch
            results.remove(device.deviceName)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val intent = Intent(ACTION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, device.deviceName.hashCode(), intent, flags)

        manager.requestPermission(device, pending)

        val granted = try {
            latch.await(20, TimeUnit.SECONDS)
            synchronized(lock) { results[device.deviceName] == true }
        } catch (e: InterruptedException) {
            false
        }

        synchronized(lock) { latches.remove(device.deviceName) }
        Log.i(TAG, "permission for ${device.deviceName}: $granted")
        return granted
    }

    @Synchronized
    private fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter(ACTION)
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        registered = true
    }
}

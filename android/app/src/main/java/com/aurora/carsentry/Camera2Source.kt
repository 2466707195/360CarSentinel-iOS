package com.aurora.carsentry

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * 标准 Camera2 采集：摄像头 → MediaCodec 输入 Surface → 硬编 H.264。
 *
 * 覆盖两类设备：
 *   · 车机自带的倒车/环视摄像头（LENS_FACING_BACK/FRONT）
 *   · **Android 9+ 的外接 USB 摄像头**（LENS_FACING_EXTERNAL）——
 *     带 external camera HAL 的车机会把 UVC 摄像头注册进 Camera2，
 *     这条路是零拷贝的，优先用它；只有 Camera2 里看不到时才需要走 [UvcSource]。
 */
class Camera2Source(
    private val cameraId: String,
    private val labelOverride: String = "",
) : VideoSource {

    companion object {
        private const val TAG = "Camera2Source"
        private const val TARGET_FPS = 25
        private const val IFRAME_INTERVAL = 2
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    override val kind: String = SourceType.CAMERA2

    override val async: Boolean = true

    override fun isAlive(): Boolean = running

    @Volatile private var resolution = "--"
    @Volatile private var running = false

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var sink: VideoSink? = null

    private var width = 640
    private var height = 480
    private val bufferInfo = MediaCodec.BufferInfo()
    private var frameCount = 0
    private var lastRateTs = 0L

    override val describe: String
        get() = buildString {
            append(labelOverride.ifBlank { "camera $cameraId" })
            if (resolution != "--") append(" · ").append(resolution)
        }

    @SuppressLint("MissingPermission")
    override fun start(context: Context, sink: VideoSink) {
        if (running) return
        this.sink = sink
        running = true

        val ht = HandlerThread("cam2-$cameraId")
        ht.start()
        thread = ht
        handler = Handler(ht.looper)

        handler?.post {
            try {
                open(context)
            } catch (e: Exception) {
                Log.e(TAG, "[$cameraId] start failed: ${e.message}", e)
                sink.onFatal("打开摄像头失败：${e.message}")
                release()
            }
        }
    }

    override fun stop() {
        running = false
        handler?.post { release() }
        // 给释放留一点时间再退出线程，否则 MediaCodec 会报 "in use"
        handler?.postDelayed({
            thread?.quitSafely()
            thread = null
            handler = null
        }, 200)
    }

    // ---------------------------------------------------------------------

    private fun open(context: Context) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId)

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        val picked = pickSize(sizes)
        width = picked.width
        height = picked.height
        resolution = "${width}×$height"

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, estimateBitrate(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }

        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        encoder = codec
        inputSurface = surface

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                createSession(camera, surface)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "[$cameraId] disconnected")
                camera.close()
                device = null
                sink?.onFatal("摄像头被占用或已断开")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "[$cameraId] error $error")
                camera.close()
                device = null
                sink?.onFatal("摄像头错误码 $error")
            }
        }, handler)

        handler?.post(drainRunnable)
    }

    private fun createSession(camera: CameraDevice, surface: Surface) {
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            android.util.Range(TARGET_FPS, TARGET_FPS)
                        )
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }
                    try {
                        configured.setRepeatingRequest(request.build(), null, handler)
                        Log.i(TAG, "[$cameraId] streaming $width x $height")
                    } catch (e: Exception) {
                        Log.e(TAG, "[$cameraId] setRepeating failed: ${e.message}")
                        sink?.onFatal("启动预览失败")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "[$cameraId] session configure failed")
                    sink?.onFatal("摄像头会话创建失败")
                }
            },
            handler
        )
    }

    // ---------------------------------------------------------------------
    // 编码输出
    // ---------------------------------------------------------------------

    private val drainRunnable = object : Runnable {
        override fun run() {
            drain()
            if (running) handler?.postDelayed(this, 5)
        }
    }

    private fun drain() {
        val codec = encoder ?: return
        val target = sink ?: return

        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (e: IllegalStateException) {
                return
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) continue

            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && bufferInfo.size > 0) {
                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (!isConfig) {
                    val raw = ByteArray(bufferInfo.size)
                    buffer.position(bufferInfo.offset)
                    buffer.get(raw)
                    val key = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    target.onFrame(
                        H264Util.toAnnexB(raw),
                        bufferInfo.presentationTimeUs,
                        key,
                        System.currentTimeMillis() / 1000,
                    )
                    frameCount++
                    publishRate(target)
                }
            }
            codec.releaseOutputBuffer(index, false)

            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
        }

        if (frameCount > 0 && !onlineReported) {
            onlineReported = true
            target.onStatus(true, resolution, TARGET_FPS, estimateBitrate(width, height) / 1000)
        }
    }

    private var onlineReported = false

    private fun publishRate(target: VideoSink) {
        val now = System.currentTimeMillis()
        if (lastRateTs == 0L) {
            lastRateTs = now
            return
        }
        val delta = now - lastRateTs
        if (delta < 2_000) return
        lastRateTs = now
        target.onStatus(true, resolution, TARGET_FPS, lastKbps)
    }

    /** 由 [ChannelRunner] 在切片结束后回写，用于 UI 显示真实码率 */
    @Volatile private var lastKbps = 0

    fun updateBitrate(kbps: Int) {
        lastKbps = kbps
    }

    // ---------------------------------------------------------------------

    private fun pickSize(sizes: Array<Size>): Size {
        if (sizes.isEmpty()) return Size(640, 480)
        val preferred = listOf(1280 to 720, 1024 to 600, 960 to 540, 800 to 480)
        for ((w, h) in preferred) {
            sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
        }
        return sizes.filter { it.width * it.height <= 1280 * 720 }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(640, 480)
    }

    private fun estimateBitrate(w: Int, h: Int): Int =
        (w.toLong() * h * TARGET_FPS * 0.12).toInt().coerceIn(800_000, 8_000_000)

    private fun release() {
        running = false
        handler?.removeCallbacks(drainRunnable)

        try {
            session?.stopRepeating()
            session?.close()
        } catch (_: Exception) {
        }
        session = null

        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null

        try {
            encoder?.stop()
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null

        onlineReported = false
        sink?.onStatus(false, resolution, 0, 0)
    }

    // ---------------------------------------------------------------------
    // 枚举：给 UI 用
    // ---------------------------------------------------------------------

}

/** Camera2 摄像头枚举，给 UI 的采集源选择用。 */
object CameraEnumerator {

    private const val TAG = "CameraEnumerator"

    data class Found(
        val cameraId: String,
        val facing: Int,
        val title: String,
        val detail: String,
    )

    /** 列出所有 Camera2 摄像头，含外接 USB 摄像头 */
    @SuppressLint("MissingPermission")
    fun list(context: Context): List<Found> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val ids = try {
            manager.cameraIdList
        } catch (e: Exception) {
            return emptyList()
        }

        return ids.mapNotNull { id ->
            try {
                val ch = manager.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: -1
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val size = map?.getOutputSizes(SurfaceTexture::class.java)
                    ?.filter { it.width * it.height <= 1920 * 1080 }
                    ?.maxByOrNull { it.width * it.height }

                val facingText = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "前向"
                    CameraCharacteristics.LENS_FACING_BACK -> "后向"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接 USB"
                    else -> "未知朝向"
                }
                Found(
                    cameraId = id,
                    facing = facing,
                    title = "camera$id · $facingText",
                    detail = size?.let { "${it.width}×${it.height}" } ?: "--",
                )
            } catch (e: Exception) {
                Log.w(TAG, "skip camera $id: ${e.message}")
                null
            }
        }
    }
}

/** 描述位置用的常量，避免各处硬编码 */
object CameraFacing {
    // 用 val 而不是 const val：这些值来自 android.jar 的 Java 静态字段，
    // 虽然实际上也是编译期常量，但 `const val` 对 Java 字段的内联要求更严格，
    // 换成 val 在任何 SDK 版本下都不会有编译风险（性能上没有任何区别）。
    val EXTERNAL = CameraCharacteristics.LENS_FACING_EXTERNAL
    val BACK = CameraCharacteristics.LENS_FACING_BACK
    val FRONT = CameraCharacteristics.LENS_FACING_FRONT
}

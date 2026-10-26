package com.aurora.carsentry

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
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
import java.io.File

/**
 * 一路摄像头：Camera2 采集 → MediaCodec 硬编 H.264 → HlsWriter 切片。
 *
 * 每路独立一个 HandlerThread，互不阻塞。四路同时跑时，
 * 车机上的编码器实例数是 4，注意确认 SoC 支持（多数 8155/8295 没问题）。
 *
 * 分辨率默认压到 720p：四路 1080p 同时编码会吃掉太多带宽和算力，
 * 而且在手机的小宫格里也看不出差别。
 */
class CameraPipeline(
    private val context: Context,
    private val cameraId: String,
    val logicalId: String,
    val displayName: String,
    val position: String,
    private val liveDir: File,
    private val onClipReady: (clipId: String, startAt: Long, duration: Int, bytes: Long) -> Unit,
) {

    companion object {
        private const val TAG = "CameraPipeline"
        private const val TARGET_FPS = 25
        private const val IFRAME_INTERVAL = 2
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    @Volatile var online: Boolean = false
        private set

    @Volatile var frameCount: Long = 0
        private set

    @Volatile var resolutionText: String = "--"
        private set

    @Volatile var bitrateKbps: Int = 0
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var writer: HlsWriter? = null

    private var width = 640
    private var height = 480

    private val bufferInfo = MediaCodec.BufferInfo()
    private val drainRunnable = object : Runnable {
        override fun run() {
            drainEncoder()
            handler?.postDelayed(this, 5)
        }
    }

    // ---------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun start() {
        if (thread != null) return

        val ht = HandlerThread("cam-$logicalId")
        ht.start()
        thread = ht
        handler = Handler(ht.looper)

        handler?.post {
            try {
                openCameraAndEncoder()
            } catch (e: Exception) {
                Log.e(TAG, "[$logicalId] start failed: ${e.message}", e)
                online = false
                releaseInternal()
            }
        }
    }

    fun stop() {
        handler?.post {
            releaseInternal()
            handler?.removeCallbacks(drainRunnable)
            thread?.quitSafely()
        }
        thread = null
        handler = null
    }

    /** 给 /api/v1/info 用的一行状态 */
    fun toCameraInfo(): CameraInfo = CameraInfo(
        id = logicalId,
        name = displayName,
        position = position,
        online = online,
        resolution = resolutionText,
        fps = if (online) TARGET_FPS else 0,
        bitrateKbps = bitrateKbps,
        hlsPath = if (online) "live/$logicalId/index.m3u8" else null,
        snapshotPath = null,
    )

    // ---------------------------------------------------------------------
    // 初始化
    // ---------------------------------------------------------------------

    private fun openCameraAndEncoder() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId)

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaCodecInfo.CodecCapabilities.CLASS) ?: emptyArray()
        val picked = pickSize(sizes)
        width = picked.width
        height = picked.height
        resolutionText = "${width}×${height}"

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, estimateBitrate(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            // 关键：让每个 IDR 帧自带 SPS/PPS，TS 分片才能独立解码
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }

        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        encoder = codec
        inputSurface = surface
        writer = HlsWriter(
            cameraDir = liveDir,
            cameraId = logicalId,
            onClipReady = onClipReady,
        )

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                createSession(camera, surface)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "[$logicalId] camera disconnected")
                camera.close()
                device = null
                online = false
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "[$logicalId] camera error $error")
                camera.close()
                device = null
                online = false
            }
        }, handler)

        handler?.post(drainRunnable)
    }

    private fun createSession(camera: CameraDevice, surface: Surface) {
        val targets = listOf(surface)

        camera.createCaptureSession(
            targets,
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
                    configured.setRepeatingRequest(request.build(), null, handler)
                    online = true
                    Log.i(TAG, "[$logicalId] streaming at ${width}x$height")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "[$logicalId] session configure failed")
                    online = false
                }
            },
            handler
        )
    }

    private fun pickSize(sizes: Array<Size>): Size {
        if (sizes.isEmpty()) return Size(640, 480)
        val preferred = listOf(1280 to 720, 1024 to 600, 960 to 540, 800 to 480)
        for ((w, h) in preferred) {
            sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
        }
        // 退而求其次：取面积不超过 720p 的最大尺寸
        return sizes
            .filter { it.width * it.height <= 1280 * 720 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(640, 480)
    }

    private fun estimateBitrate(w: Int, h: Int): Int {
        // 经验值：0.12 bit per pixel per frame，720p25 ≈ 2.7 Mbps
        return (w * h * TARGET_FPS * 0.12).toInt().coerceIn(800_000, 8_000_000)
    }

    // ---------------------------------------------------------------------
    // 取数据
    // ---------------------------------------------------------------------

    private fun drainEncoder() {
        val codec = encoder ?: return
        val sink = writer ?: return

        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (e: IllegalStateException) {
                return
            }

            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) continue

            val buffer = codec.getOutputBuffer(index) ?: run {
                codec.releaseOutputBuffer(index, false)
                return
            }

            val size = bufferInfo.size
            if (size > 0) {
                val raw = ByteArray(size)
                buffer.position(bufferInfo.offset)
                buffer.get(raw)
                buffer.position(bufferInfo.offset)

                val annexB = H264Util.toAnnexB(raw)
                val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                sink.writeSample(
                    data = annexB,
                    offset = 0,
                    size = annexB.size,
                    ptsUs = bufferInfo.presentationTimeUs,
                    isKeyFrame = isKey,
                    epochSec = System.currentTimeMillis() / 1000,
                )

                frameCount++
                bitrateKbps = ((size * 8L * TARGET_FPS) / 1000).toInt()
            }

            codec.releaseOutputBuffer(index, false)

            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return
            }
        }
    }

    // ---------------------------------------------------------------------
    // 释放
    // ---------------------------------------------------------------------

    private fun releaseInternal() {
        online = false

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
            writer?.finish()
        } catch (_: Exception) {
        }
        writer = null

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
    }
}

/**
 * H.264 码流格式归一化。
 *
 * MediaCodec 在多数设备上输出 Annex-B（00 00 00 01 起始码），
 * 但也有实现输出 AVCC（4 字节长度前缀）。TS 只吃 Annex-B，所以统一转一次。
 */
object H264Util {

    fun toAnnexB(data: ByteArray): ByteArray {
        if (data.size < 5) return data
        if (isAnnexB(data)) return data
        return avccToAnnexB(data)
    }

    private fun isAnnexB(data: ByteArray): Boolean {
        // 前若干字节里找 00 00 01
        val limit = minOf(data.size - 3, 16)
        for (i in 0..limit) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                return true
            }
        }
        return false
    }

    private fun avccToAnnexB(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + 16)
        var pos = 0
        while (pos + 4 <= data.size) {
            val length = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            pos += 4
            if (length <= 0 || pos + length > data.size) break
            out.write(0x00)
            out.write(0x00)
            out.write(0x00)
            out.write(0x01)
            out.write(data, pos, length)
            pos += length
        }
        return if (out.size() == 0) data else out.toByteArray()
    }
}

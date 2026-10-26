package com.aurora.carsentry

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

/**
 * 把「CPU 侧的画面」编成 H.264。
 *
 * Camera2 走 Surface 直通编码（零拷贝，最省），但 UVC(MJPEG) 和测试信号
 * 只有内存里的像素，只能走 ByteBuffer 输入模式。这里用
 * `COLOR_FormatYUV420Flexible` + `getInputImage()`，把 Y/U/V 直接写进
 * 编码器给的 Image plane —— 不用猜 NV12 还是 I420，也不用做格式转换的中间缓冲。
 */
class YuvEncoder(
    private val fps: Int = 25,
    private val bitrate: Int = 1_500_000,
) {

    companion object {
        private const val TAG = "YuvEncoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val IFRAME_INTERVAL = 2
    }

    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private val info = MediaCodec.BufferInfo()

    @Volatile
    var released = false
        private set

    /**
     * @param argb  ARGB_8888 像素，长度 = w*h
     * @return 是否成功入队（编码器满时返回 false，调用方应丢帧）
     */
    fun encode(
        argb: IntArray,
        w: Int,
        h: Int,
        ptsUs: Long,
        forceKeyFrame: Boolean,
        out: (ByteArray, Long, Boolean) -> Unit,
    ): Boolean {
        if (released) return false
        val encoder = ensure(w, h) ?: return false

        val inIndex = try {
            encoder.dequeueInputBuffer(8_000)
        } catch (e: IllegalStateException) {
            return false
        }
        if (inIndex < 0) return false

        val image = try {
            encoder.getInputImage(inIndex)
        } catch (e: Exception) {
            null
        }
        if (image == null) {
            encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
            Log.w(TAG, "getInputImage 返回 null，跳过一帧")
            return false
        }

        fillYuv(image.planes, argb, w, h, image.width, image.height)

        val flags = if (forceKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        val size = image.width * image.height * 3 / 2
        try {
            encoder.queueInputBuffer(inIndex, 0, size, ptsUs, flags)
        } catch (e: IllegalStateException) {
            return false
        }

        drain(encoder, out)
        return true
    }

    fun release() {
        released = true
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }

    // ---------------------------------------------------------------------

    private fun ensure(w: Int, h: Int): MediaCodec? {
        val current = codec
        if (current != null && w == width && h == height) return current

        // 分辨率变了就重建（UVC 重新协商后可能出现）
        if (current != null) {
            try {
                current.stop(); current.release()
            } catch (_: Exception) {
            }
            codec = null
        }

        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(w, h))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            // 每个 IDR 自带 SPS/PPS，TS 分片才能独立解码
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }

        return try {
            val created = MediaCodec.createEncoderByType(MIME)
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            codec = created
            width = w
            height = h
            Log.i(TAG, "encoder ready ${w}x$h @${fps}fps")
            created
        } catch (e: Exception) {
            Log.e(TAG, "create encoder failed: ${e.message}", e)
            null
        }
    }

    private fun bitrateFor(w: Int, h: Int): Int {
        // 经验值：0.12 bit/px/frame。720p25 ≈ 2.7 Mbps
        val estimate = (w.toLong() * h * fps * 0.12).toInt()
        return estimate.coerceIn(bitrate, 8_000_000)
    }

    private fun drain(encoder: MediaCodec, out: (ByteArray, Long, Boolean) -> Unit) {
        while (true) {
            val index = try {
                encoder.dequeueOutputBuffer(info, 0)
            } catch (e: IllegalStateException) {
                return
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) continue

            val buffer = encoder.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (!isConfig) {
                    val raw = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(raw)
                    val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    out(H264Util.toAnnexB(raw), info.presentationTimeUs, key)
                }
            }
            encoder.releaseOutputBuffer(index, false)
        }
    }

    // ---------------------------------------------------------------------
    // ARGB → YUV，直接写进 Image 的 plane
    // ---------------------------------------------------------------------

    private fun fillYuv(
        planes: Array<android.media.Image.Plane>,
        argb: IntArray,
        w: Int,
        h: Int,
        imageW: Int,
        imageH: Int,
    ) {
        if (planes.size < 3) return
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        for (y in 0 until h) {
            val srcRow = y * w
            val dstRow = y * yRowStride
            for (x in 0 until w) {
                val color = argb[srcRow + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val luma = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(dstRow + x * yPixelStride, luma.coerceIn(0, 255).toByte())
            }
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val chromaW = (w + 1) / 2
        val chromaH = (h + 1) / 2
        for (cy in 0 until chromaH) {
            val y0 = (cy * 2) * w
            val y1 = minOf(cy * 2 + 1, h - 1) * w
            val dstRow = cy * uRowStride
            val dstRowV = cy * vRowStride
            for (cx in 0 until chromaW) {
                val x0 = cx * 2
                val x1 = minOf(x0 + 1, w - 1)

                var rSum = 0
                var gSum = 0
                var bSum = 0
                var c = argb[y0 + x0]
                rSum += (c shr 16) and 0xFF; gSum += (c shr 8) and 0xFF; bSum += c and 0xFF
                c = argb[y0 + x1]
                rSum += (c shr 16) and 0xFF; gSum += (c shr 8) and 0xFF; bSum += c and 0xFF
                c = argb[y1 + x0]
                rSum += (c shr 16) and 0xFF; gSum += (c shr 8) and 0xFF; bSum += c and 0xFF
                c = argb[y1 + x1]
                rSum += (c shr 16) and 0xFF; gSum += (c shr 8) and 0xFF; bSum += c and 0xFF

                val r = rSum / 4
                val g = gSum / 4
                val b = bSum / 4

                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                uBuffer.put(dstRow + cx * uPixelStride, u.coerceIn(0, 255).toByte())
                vBuffer.put(dstRowV + cx * vPixelStride, v.coerceIn(0, 255).toByte())
            }
        }

        // imageW/imageH 只用于调试：plane 的 stride 已经包含了 padding
        if (imageW < w || imageH < h) {
            Log.w(TAG, "image ${imageW}x$imageH 小于目标 ${w}x$h")
        }
    }
}

/**
 * MJPEG → ARGB 解码。
 *
 * 用 inSampleSize 把画面压到目标宽度以内：UVC 常见的 1080p MJPEG 直接解成
 * ARGB 再转 YUV 会吃掉太多 CPU，而车里的小宫格也不需要那个分辨率。
 */
object JpegDecoder {

    private const val TAG = "JpegDecoder"

    class Frame(val argb: IntArray, val width: Int, val height: Int)

    fun decode(jpeg: ByteArray, offset: Int, length: Int, maxWidth: Int): Frame? {
        if (length <= 4) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(jpeg, offset, length, bounds)
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxWidth) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg, offset, length, options)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM decoding jpeg")
            null
        } ?: return null

        return try {
            val w = bitmap.width
            val h = bitmap.height
            val argb = IntArray(w * h)
            bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            Frame(argb, w, h)
        } finally {
            bitmap.recycle()
        }
    }
}

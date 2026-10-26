package com.aurora.carsentry

import android.content.Context
import android.util.Log

/**
 * 测试信号源：不碰任何摄像头，直接生成画面编码推出去。
 *
 * 存在的意义是**分层排障**：
 *   · 测试信号能看 → 编码 / 切片 / HTTP / 手机端整条链路是通的，问题在采集源
 *   · 测试信号也看不了 → 别去折腾摄像头，先查网络和服务
 *
 * 装车调试时这比盲猜省太多时间。
 */
class TestPatternSource(private val channelId: String) : VideoSource {

    companion object {
        private const val TAG = "TestPatternSource"
        private const val WIDTH = 640
        private const val HEIGHT = 360
        private const val FPS = 25
    }

    override val kind: String = SourceType.TEST
    override val describe: String = "测试信号 ${WIDTH}×$HEIGHT"

    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(context: Context, sink: VideoSink) {
        if (running) return
        running = true

        thread = Thread({
            val argb = IntArray(WIDTH * HEIGHT)
            val encoder = YuvEncoder(FPS, 1_200_000)
            val startNs = System.nanoTime()
            var frame = 0

            sink.onStatus(true, "${WIDTH}×${HEIGHT}", FPS, 1_200)

            while (running) {
                val phase = frame * 0.02f
                drawPattern(argb, phase)

                val ptsUs = (System.nanoTime() - startNs) / 1000
                val forceKey = frame % (FPS * 2) == 0

                try {
                    encoder.encode(argb, WIDTH, HEIGHT, ptsUs, forceKey) { data, pts, key ->
                        sink.onFrame(data, pts, key, System.currentTimeMillis() / 1000)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[$channelId] encode failed: ${e.message}")
                }

                frame++

                // 简单节流：按帧号推目标时间，落后了就少睡一点
                val targetNs = startNs + frame.toLong() * 1_000_000_000L / FPS
                val sleepMs = (targetNs - System.nanoTime()) / 1_000_000
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }

            encoder.release()
            sink.onStatus(false, "${WIDTH}×${HEIGHT}", 0, 0)
            Log.i(TAG, "[$channelId] stopped")
        }, "test-$channelId")

        thread?.start()
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** 彩条 + 滚动扫描线 + 呼吸渐晕，肉眼一眼能看出「是不是活的」 */
    private fun drawPattern(argb: IntArray, phase: Float) {
        val barWidth = WIDTH / 6
        val scanY = ((phase * 90) % HEIGHT).toInt()

        for (y in 0 until HEIGHT) {
            val vertical = (y * 100 / HEIGHT)
            for (x in 0 until WIDTH) {
                val bar = (x / barWidth).coerceIn(0, 5)
                val base = when (bar) {
                    0 -> 0xFFFFFFFF.toInt()
                    1 -> 0xFFFFD400.toInt()
                    2 -> 0xFF00C8C8.toInt()
                    3 -> 0xFF19C37D.toInt()
                    4 -> 0xFF7C5CFC.toInt()
                    else -> 0xFFD6336C.toInt()
                }
                val r = (base shr 16) and 0xFF
                val g = (base shr 8) and 0xFF
                val b = base and 0xFF

                // 上暗下亮的渐变，模拟真实画面的明暗分布
                val factor = 0.35f + 0.65f * (vertical / 100f)

                var rr = (r * factor).toInt()
                var gg = (g * factor).toInt()
                var bb = (b * factor).toInt()

                // 扫描线
                val distance = if (y > scanY) y - scanY else scanY - y
                if (distance < 3) {
                    rr = (rr * 0.25f).toInt() + 190
                    gg = (gg * 0.25f).toInt() + 190
                    bb = (bb * 0.25f).toInt() + 190
                }

                // 中心十字：确认画面没有被裁切
                val cx = WIDTH / 2
                val cy = HEIGHT / 2
                if ((x - cx in -1..1 && y - cy in -14..14) || (y - cy in -1..1 && x - cx in -20..20)) {
                    rr = 255; gg = 255; bb = 255
                }

                argb[y * WIDTH + x] = (0xFF shl 24) or
                    (rr.coerceIn(0, 255) shl 16) or
                    (gg.coerceIn(0, 255) shl 8) or
                    bb.coerceIn(0, 255)
            }
        }
    }
}

package com.aurora.carsentry

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * 一路机位的执行器：把「采集源」和「切片器」绑起来，负责重连与统计。
 *
 * 采集源可能随时死掉（USB 拔了、RTSP 断流、厂商 socket 关了），
 * 这里做退避重连 —— 车机上没人会去手动点重启。
 */
class ChannelRunner(
    private val context: Context,
    val logicalId: String,
    val displayName: String,
    val position: String,
    private val liveDir: File,
    private val specProvider: () -> SourceSpec,
    private val onClipReady: (clipId: String, startAt: Long, duration: Int, bytes: Long) -> Unit,
    private val onChanged: () -> Unit,
) {

    companion object {
        private const val TAG = "ChannelRunner"
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 20_000L
    }

    @Volatile var online: Boolean = false
        private set

    @Volatile var resolutionText: String = "--"
        private set

    @Volatile var fps: Int = 0
        private set

    @Volatile var bitrateKbps: Int = 0
        private set

    @Volatile var lastError: String? = null
        private set

    /** 当前生效的采集源描述，给 UI 显示「这一路到底接的是什么」 */
    @Volatile var sourceDescription: String = "未配置"
        private set

    private val lock = Any()
    private var writer: HlsWriter? = null
    private var supervisor: Thread? = null
    private var current: VideoSource? = null

    @Volatile private var running = false
    @Volatile private var restartRequested = false

    // 码率统计窗口
    private var windowBytes = 0L
    private var windowStart = 0L

    fun start() {
        if (running) return
        running = true
        supervisor = Thread({ loop() }, "ch-$logicalId")
        supervisor?.start()
    }

    fun stop() {
        running = false
        requestRestart()
        current?.stop()
        synchronized(lock) {
            try {
                writer?.finish()
            } catch (_: Exception) {
            }
            writer = null
        }
        supervisor?.interrupt()
        supervisor = null
        online = false
        fps = 0
        bitrateKbps = 0
    }

    /** 用户改了采集源，或者服务重启时调 */
    fun requestRestart() {
        restartRequested = true
        current?.stop()
    }

    fun toCameraInfo(): CameraInfo = CameraInfo(
        id = logicalId,
        name = displayName,
        position = position,
        online = online,
        resolution = resolutionText,
        fps = fps,
        bitrateKbps = bitrateKbps,
        hlsPath = if (online) "live/$logicalId/index.m3u8" else null,
        snapshotPath = null,
    )

    // ---------------------------------------------------------------------

    private fun loop() {
        var backoff = MIN_BACKOFF_MS

        while (running) {
            restartRequested = false

            val spec = specProvider()
            if (spec.isNone) {
                sourceDescription = "未配置"
                setOffline("这一路没有配置采集源")
                sleep(MIN_BACKOFF_MS)
                continue
            }

            val source = SourceFactory.create(spec, logicalId)
            if (source == null) {
                sourceDescription = SourceType.label(spec.type)
                setOffline("采集源不可用：${spec.type} ${spec.target}")
                sleep(MAX_BACKOFF_MS)
                continue
            }

            sourceDescription = source.describe
            current = source

            synchronized(lock) {
                writer = HlsWriter(
                    cameraDir = liveDir,
                    cameraId = logicalId,
                    onClipReady = onClipReady,
                )
            }

            var fatalReason: String? = null
            var frames = 0L
            val startedAt = System.currentTimeMillis()
            windowBytes = 0
            windowStart = startedAt

            val sink = object : VideoSink {

                override fun onFrame(annexB: ByteArray, ptsUs: Long, keyFrame: Boolean, epochSec: Long) {
                    synchronized(lock) {
                        try {
                            writer?.writeSample(annexB, 0, annexB.size, ptsUs, keyFrame, epochSec)
                        } catch (e: Exception) {
                            Log.w(TAG, "[$logicalId] write failed: ${e.message}")
                        }
                    }
                    frames++
                    windowBytes += annexB.size

                    if (!online) {
                        online = true
                        onChanged()
                    }
                    updateBitrate(startedAt)
                }

                override fun onStatus(online: Boolean, resolution: String, fps: Int, bitrateKbps: Int) {
                    if (resolution != "--") resolutionText = resolution
                    if (fps > 0) this@ChannelRunner.fps = fps
                    if (bitrateKbps > 0) this@ChannelRunner.bitrateKbps = bitrateKbps
                    if (!online && this@ChannelRunner.online) {
                        this@ChannelRunner.online = false
                    }
                }

                override fun onFatal(reason: String) {
                    fatalReason = reason
                }
            }

            try {
                Log.i(TAG, "[$logicalId] start ${source.kind} ${source.describe}")
                source.start(context, sink)

                if (source.async) {
                    while (running && !restartRequested && fatalReason == null && source.isAlive()) {
                        if (!online && frames == 0L && System.currentTimeMillis() - startedAt > 12_000) {
                            fatalReason = "12 秒没有画面"
                            break
                        }
                        sleep(300)
                    }
                }
            } catch (e: Exception) {
                fatalReason = e.message ?: "采集异常"
                Log.w(TAG, "[$logicalId] source exception: ${e.message}")
            }

            try {
                source.stop()
            } catch (_: Exception) {
            }
            // 给异步源一点收尾时间，否则 finish() 之后还可能补进来一帧
            sleep(150)
            current = null

            synchronized(lock) {
                try {
                    writer?.finish()
                } catch (_: Exception) {
                }
                writer = null
            }

            val reason = fatalReason ?: if (restartRequested) "已切换采集源" else "采集已停止"
            setOffline(reason)

            if (!running) break

            val aliveMs = System.currentTimeMillis() - startedAt
            if (aliveMs > 60_000) backoff = MIN_BACKOFF_MS

            Log.i(TAG, "[$logicalId] offline: $reason，${backoff}ms 后重试")
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }

        Log.i(TAG, "[$logicalId] runner exit")
    }

    private fun updateBitrate(startedAt: Long) {
        val now = System.currentTimeMillis()
        val elapsed = now - windowStart
        if (elapsed < 2_000) return
        val kbps = (windowBytes * 8 / elapsed).toInt()
        bitrateKbps = kbps
        windowBytes = 0
        windowStart = now
        if (fps == 0) fps = 25
    }

    private fun setOffline(reason: String?) {
        val wasOnline = online
        online = false
        fps = 0
        bitrateKbps = 0
        if (reason != null) lastError = reason
        if (wasOnline) onChanged()
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    fun statusLine(): String = buildString {
        append(sourceDescription)
        if (lastError != null && !online) append(" · ").append(lastError)
    }

    fun metricsText(): String = if (online) {
        String.format(Locale.US, "%s · %d fps · %.1f Mbps", resolutionText, fps, bitrateKbps / 1000.0)
    } else {
        "--"
    }
}

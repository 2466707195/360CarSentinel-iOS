package com.aurora.carsentry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 前台服务：把「采集 → 编码 → 切片」「HTTP 服务」「远程隧道」绑在一起，
 * 锁屏 / 切后台都不停。
 *
 * 车机上通常设成开机自启、常驻。
 */
class SentryService : Service() {

    companion object {
        private const val TAG = "SentryService"
        private const val CHANNEL_ID = "sentry_service"
        private const val NOTIFICATION_ID = 1001
        const val PORT = 8710

        @Volatile
        var instance: SentryService? = null
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var relayConnected: Boolean = false
            private set

        @Volatile
        var relayDetail: String = "未启用"
            private set
    }

    private var http: HttpServer? = null
    private var nsd: NsdHelper? = null
    private var relay: RelayTunnel? = null
    private val runners = ArrayList<ChannelRunner>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cleanupJob: Job? = null

    private val rootDir: File by lazy { File(filesDir, "sentry") }
    private val registry: SourceRegistry by lazy { SourceRegistry(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在启动…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SentryState.startedAtMs = System.currentTimeMillis()
        // 开机自启时 intent 带的是持久化的设备名；被系统 START_STICKY 重建时 intent 为 null，
        // 也要退回到本地设置，否则 mDNS 广播名会变成空串，手机端就扫不到了。
        SentryState.deviceName = intent?.getStringExtra("deviceName")
            ?: SentryPrefs.deviceName(this)
        SentryState.model = "${Build.MANUFACTURER} ${Build.MODEL}"
        SentryState.httpPort = PORT

        if (intent?.getBooleanExtra("fromBoot", false) == true) {
            Log.i(TAG, "本次启动来自开机广播")
        }

        // 有相机权限但用的是 RTSP / UVC 源时，也不需要相机权限，
        // 所以这里只提示，不阻断整条链路。
        if (!hasCameraPermission()) {
            Log.w(TAG, "没有相机权限：Camera2 类采集源会失败，其他源不受影响")
        }

        registry.ensureDefaults()

        startServer()
        startChannels()
        startRelayIfNeeded()
        startCleanupLoop()

        updateNotification(notificationText())
        ServiceBus.notifyChanged()

        return START_STICKY
    }

    override fun onDestroy() {
        cleanupJob?.cancel()
        runners.forEach { it.stop() }
        runners.clear()
        relay?.stop()
        relay = null
        relayConnected = false
        relayDetail = "未启用"
        http?.stop()
        http = null
        nsd?.unregister()
        nsd = null
        SentryState.startedAtMs = 0
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // HTTP + 发现
    // ---------------------------------------------------------------------

    private fun startServer() {
        if (http != null) return

        rootDir.mkdirs()

        val server = HttpServer(
            port = PORT,
            root = rootDir,
            onControl = ::handleControl,
        )
        server.start()
        http = server

        nsd = NsdHelper(this, PORT, SentryState.deviceName).also { it.register() }
    }

    private fun handleControl(action: String, cameraId: String?, enabled: Boolean?) {
        Log.i(TAG, "control: $action camera=$cameraId enabled=$enabled")

        when (action) {
            "start_record" -> SentryState.recording = true
            "stop_record" -> SentryState.recording = false
            "set_parking_mode" -> SentryState.parkingMode = enabled ?: SentryState.parkingMode
            "snapshot" -> Log.i(TAG, "snapshot requested for $cameraId (not implemented in v1)")
            else -> Log.w(TAG, "unknown action: $action")
        }
        ServiceBus.notifyChanged()
    }

    // ---------------------------------------------------------------------
    // 采集
    // ---------------------------------------------------------------------

    private fun startChannels() {
        if (runners.isNotEmpty()) return

        Channels.all.forEach { channel ->
            val liveDir = File(rootDir, "live/${channel.id}")
            liveDir.mkdirs()

            val runner = ChannelRunner(
                context = this,
                logicalId = channel.id,
                displayName = channel.name,
                position = channel.position,
                liveDir = liveDir,
                specProvider = { registry.specFor(channel.id) },
                onClipReady = { clipId, startAt, duration, bytes ->
                    onClipReady(channel.id, clipId, startAt, duration, bytes)
                },
                onChanged = { ServiceBus.notifyChanged() },
            )
            runners.add(runner)
            runner.start()
        }

        SentryState.setCameras(runners.map { it.toCameraInfo() })
    }

    /** 用户改了某一路的采集源：让那一侧重连（其他路不受影响） */
    fun applySourceChange(channelId: String) {
        val runner = runners.firstOrNull { it.logicalId == channelId } ?: return
        Log.i(TAG, "source changed for $channelId -> ${registry.specFor(channelId)}")
        runner.requestRestart()
        SentryState.setCameras(runners.map { it.toCameraInfo() })
        ServiceBus.notifyChanged()
    }

    /** 重新扫描设备并自动分配（UI 上的「自动分配」） */
    fun autoAssignSources() {
        registry.autoAssign()
        runners.forEach { it.requestRestart() }
        ServiceBus.notifyChanged()
    }

    /** USB 摄像头插拔：只有 UVC / 未配置的机位需要重连，别把 RTSP 也打断 */
    fun onUsbChanged() {
        runners.forEach { runner ->
            val spec = registry.specFor(runner.logicalId)
            if (spec.type == SourceType.UVC || spec.isNone) runner.requestRestart()
        }
        ServiceBus.notifyChanged()
    }

    /** 给 UI 与 API 用的实时状态刷新 */
    fun refreshCameraInfo() {
        if (runners.isEmpty()) return
        SentryState.setCameras(runners.map { it.toCameraInfo() })
    }

    fun runnerStatus(channelId: String): String =
        runners.firstOrNull { it.logicalId == channelId }?.statusLine() ?: "未启动"

    private fun onClipReady(
        logicalId: String,
        clipId: String,
        startAtSec: Long,
        durationSec: Int,
        bytes: Long,
    ) {
        val clip = ClipInfo(
            id = clipId,
            cameraId = logicalId,
            startAt = startAtSec,
            durationSec = durationSec,
            sizeBytes = bytes,
            source = "loop",
            locked = false,
            hlsPath = "rec/$clipId/index.m3u8",
            thumbPath = null,
        )
        SentryState.addClip(clip)
        ServiceBus.notifyChanged()
    }

    // ---------------------------------------------------------------------
    // 远程
    // ---------------------------------------------------------------------

    private fun startRelayIfNeeded() {
        if (!SentryPrefs.relayEnabled(this)) {
            relayDetail = "未启用"
            return
        }
        val url = SentryPrefs.relayUrl(this)
        if (url.isBlank()) {
            relayDetail = "未填中继地址"
            return
        }

        SentryPrefs.ensureRelayIdentity(this)

        val tunnel = RelayTunnel(
            baseUrl = url,
            room = SentryPrefs.relayRoom(this),
            key = SentryPrefs.relayKey(this),
            localPort = PORT,
        ) { connected, detail ->
            relayConnected = connected
            relayDetail = detail
            ServiceBus.notifyChanged()
            updateNotification(notificationText())
        }
        tunnel.start()
        relay = tunnel
        relayDetail = "连接中…"
    }

    fun restartRelay() {
        relay?.stop()
        relay = null
        relayConnected = false
        startRelayIfNeeded()
        ServiceBus.notifyChanged()
    }

    fun relayInfo(): Triple<Boolean, String, String> =
        Triple(SentryPrefs.relayEnabled(this), SentryPrefs.relayUrl(this), relayDetail)

    // ---------------------------------------------------------------------
    // 清理
    // ---------------------------------------------------------------------

    private fun startCleanupLoop() {
        if (cleanupJob != null) return

        cleanupJob = scope.launch {
            while (true) {
                delay(5 * 60_000)
                try {
                    cleanupOldFiles()
                } catch (e: Exception) {
                    Log.w(TAG, "cleanup failed: ${e.message}")
                }
            }
        }
    }

    /** 保留 3 天；锁定的片段不动。 */
    private fun cleanupOldFiles() {
        val cutoffMs = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000

        val recDir = File(rootDir, "rec")
        recDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.lastModified() < cutoffMs) {
                dir.deleteRecursively()
            }
        }

        File(rootDir, "live").listFiles()?.forEach { camDir ->
            camDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".ts") && file.lastModified() < cutoffMs) {
                    file.delete()
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 通知
    // ---------------------------------------------------------------------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationText(): String {
        val online = SentryState.cameras().count { it.online }
        val base = "画面 $online / ${Channels.all.size} 路 · 端口 $PORT"
        return if (SentryPrefs.relayEnabled(this)) {
            "$base · 远程${if (relayConnected) "已连接" else "未连接"}"
        } else {
            base
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}

/** 极简的「状态变了」广播：UI 收一个 tick 就整体重建，几千行代码不值得上完整架构。 */
object ServiceBus {
    val tick = MutableStateFlow(0)

    fun notifyChanged() {
        tick.value = tick.value + 1
    }
}

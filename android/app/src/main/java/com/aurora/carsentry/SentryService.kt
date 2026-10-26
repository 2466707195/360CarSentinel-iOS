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
 * 前台服务：把「采集 → 编码 → 切片」和「HTTP 服务」绑在一起，锁屏 / 切后台都不停。
 *
 * 车机上通常设成开机自启、常驻。
 */
class SentryService : Service() {

    companion object {
        private const val TAG = "SentryService"
        private const val CHANNEL_ID = "sentry_service"
        private const val NOTIFICATION_ID = 1001
        const val PORT = 8710

        /** 逻辑机位 → 显示名。顺序对应 Camera2 的 cameraIdList 下标 */
        val LOGICAL_CHANNELS = listOf(
            Triple("front", "前视", "front"),
            Triple("rear", "后视", "rear"),
            Triple("left", "左视", "left"),
            Triple("right", "右视", "right"),
        )

        @Volatile
        var instance: SentryService? = null
            private set

        @Volatile
        var lastError: String? = null
            private set
    }

    private var http: HttpServer? = null
    private var nsd: NsdHelper? = null
    private val pipelines = ArrayList<CameraPipeline>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cleanupJob: Job? = null

    private val rootDir: File by lazy { File(filesDir, "sentry") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在启动…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SentryState.startedAtMs = System.currentTimeMillis()
        SentryState.deviceName = intent?.getStringExtra("deviceName") ?: SentryState.deviceName
        SentryState.model = "${Build.MANUFACTURER} ${Build.MODEL}"
        SentryState.httpPort = PORT

        startServer()
        startCameraPipelines()
        startCleanupLoop()

        updateNotification(
            if (pipelines.any { it.online }) "画面已上线 · 端口 $PORT" else "等待摄像头…"
        )
        ServiceBus.notifyChanged()

        return START_STICKY
    }

    override fun onDestroy() {
        cleanupJob?.cancel()
        pipelines.forEach { it.stop() }
        pipelines.clear()
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
            "snapshot" -> {
                // v1 不提供即时截图；手机端不依赖它（见 docs/PROTOCOL.md）
                Log.i(TAG, "snapshot requested for $cameraId (not implemented in v1)")
            }
            else -> Log.w(TAG, "unknown action: $action")
        }
        ServiceBus.notifyChanged()
    }

    // ---------------------------------------------------------------------
    // 摄像头
    // ---------------------------------------------------------------------

    private fun startCameraPipelines() {
        if (pipelines.isNotEmpty()) return

        if (!hasCameraPermission()) {
            lastError = "没有相机权限，画面无法采集"
            Log.w(TAG, lastError)
            SentryState.setCameras(SentryState.cameras().ifEmpty { emptyChannels() })
            return
        }

        lastError = null

        val manager = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
        val cameraIds = manager?.cameraIdList?.toList() ?: emptyList()
        Log.i(TAG, "cameraIds = $cameraIds")

        if (cameraIds.isEmpty()) {
            lastError = "这台车机没有可用的 Camera2 摄像头"
            SentryState.setCameras(emptyChannels())
            return
        }

        LOGICAL_CHANNELS.forEachIndexed { index, (logicalId, name, position) ->
            val physicalId = cameraIds.getOrNull(index)
            if (physicalId == null) {
                Log.w(TAG, "no physical camera for $logicalId")
                return@forEachIndexed
            }

            val liveDir = File(rootDir, "live/$logicalId")
            liveDir.mkdirs()

            val pipeline = CameraPipeline(
                context = this,
                cameraId = physicalId,
                logicalId = logicalId,
                displayName = name,
                position = position,
                liveDir = liveDir,
                onClipReady = { clipId, startAt, duration, bytes ->
                    onClipReady(logicalId, clipId, startAt, duration, bytes)
                },
            )
            pipelines.add(pipeline)
            pipeline.start()
        }

        // 先给一份「全部离线」的快照，等 pipeline 自己把 online 打开
        SentryState.setCameras(
            LOGICAL_CHANNELS.map { (logicalId, name, position) ->
                CameraInfo(
                    id = logicalId,
                    name = name,
                    position = position,
                    online = false,
                    resolution = "--",
                    fps = 0,
                    bitrateKbps = 0,
                    hlsPath = null,
                    snapshotPath = null,
                )
            }
        )
    }

    private fun emptyChannels(): List<CameraInfo> = LOGICAL_CHANNELS.map { (logicalId, name, position) ->
        CameraInfo(
            id = logicalId,
            name = name,
            position = position,
            online = false,
            resolution = "--",
            fps = 0,
            bitrateKbps = 0,
            hlsPath = null,
            snapshotPath = null,
        )
    }

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

    /** 给 UI 与 API 用的实时状态刷新 */
    fun refreshCameraInfo() {
        if (pipelines.isEmpty()) return
        SentryState.setCameras(pipelines.map { it.toCameraInfo() })
    }

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

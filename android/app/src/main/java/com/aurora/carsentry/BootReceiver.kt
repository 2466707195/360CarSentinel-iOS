package com.aurora.carsentry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 开机自启。
 *
 * 车机是「点火就通电、熄火就断电」的设备，用户不会每次上车先去点一下 App。
 * 所以只要用户在界面上打开了「开机自启」，这里就在系统广播到达时把前台服务拉起来。
 *
 * 几个容易踩的坑，都已经处理：
 *
 * 1. **Android 12+ 的后台启动前台服务限制**：`BOOT_COMPLETED` 属于系统豁免场景，
 *    可以直接 `startForegroundService`。但个别魔改 ROM 仍会抛
 *    `ForegroundServiceStartNotAllowedException`，所以这里 catch 住并弹出通知，
 *    提示用户手动点一下，而不是静默失败。
 * 2. **开机瞬间读设置**：用 SharedPreferences 同步读，不能用异步的 DataStore。
 * 3. **相机权限**：开机时权限通常还在。万一被撤销，服务起来也是「无画面」，
 *    不如直接不发，给一条可点开的通知更清楚。
 * 4. **MY_PACKAGE_REPLACED**：App 升级后系统不会发 BOOT_COMPLETED，
 *    但会发这条广播，顺手把服务重新拉起来，避免「升级完就断流」。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val CHANNEL_ID = "sentry_boot"
        private const val NOTIFICATION_ID = 1002

        /** 部分国产车机 / 盒子 ROM 用的是厂商自定义的开机广播 */
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.HTC_QUICKBOOT_POWERON",
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isBoot = action in BOOT_ACTIONS
        val isUpgrade = action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot && !isUpgrade) return

        if (!SentryPrefs.autoStart(context)) {
            Log.i(TAG, "$action 收到，但用户在设置里关掉了开机自启")
            return
        }

        // 相机权限没了就别硬起服务，否则前台服务一建起来就报「无画面」
        val cameraGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            Log.w(TAG, "$action 收到，但没有相机权限，跳过自启")
            notify(context, "需要相机权限", "打开「车载哨兵」点一次「启动服务」即可授权，之后就能开机自启")
            return
        }

        val serviceIntent = Intent(context, SentryService::class.java).apply {
            putExtra("fromBoot", true)
            putExtra("deviceName", SentryPrefs.deviceName(context))
        }

        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "$action -> 已拉起 SentryService")
        } catch (e: Exception) {
            // 少数 ROM 会拒绝开机启动前台服务；退化成一条可点开的通知
            Log.w(TAG, "开机自启被系统拒绝：${e.message}")
            notify(
                context,
                "自启被系统拦下了",
                "车机系统不允许后台自启。点这里打开 App，并在系统设置里把它加入「自启动 / 后台白名单」"
            )
        }
    }

    private fun notify(context: Context, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_boot),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val pending = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "通知发不出去：${e.message}")
        }
    }
}

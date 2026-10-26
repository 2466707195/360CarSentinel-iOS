package com.aurora.carsentry

import android.content.Context
import android.content.SharedPreferences

/**
 * 车机端的本地设置。用 SharedPreferences 而不是 DataStore —— 这里只有两三个布尔值，
 * 而且 BootReceiver 要在开机瞬间同步读到，SharedPreferences 是最稳的。
 */
object SentryPrefs {

    private const val FILE = "sentry_prefs"
    private const val KEY_AUTO_START = "auto_start_on_boot"
    private const val KEY_DEVICE_NAME = "device_name"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 开机自启。默认 **开** —— 车机这个使用场景，用户装完就希望它一直活着，
     * 默认关会让「装完就没反应」变成第一印象。
     */
    fun autoStart(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    fun deviceName(ctx: Context): String =
        sp(ctx).getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            ?: defaultDeviceName()

    fun setDeviceName(ctx: Context, name: String) {
        sp(ctx).edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    private fun defaultDeviceName(): String = "车载哨兵"
}

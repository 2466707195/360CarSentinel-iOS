package com.aurora.carsentry

import android.content.Context
import android.content.SharedPreferences

/**
 * 车机端的本地设置。用 SharedPreferences 而不是 DataStore —— 这里只有几个值，
 * 而且 BootReceiver 要在开机瞬间同步读到，SharedPreferences 是最稳的。
 */
object SentryPrefs {

    private const val FILE = "sentry_prefs"

    private const val KEY_AUTO_START = "auto_start_on_boot"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_CHANNEL_SOURCES = "channel_sources"
    private const val KEY_SOURCES_SEEDED = "sources_seeded"

    // 远程访问（中继）
    private const val KEY_RELAY_ENABLED = "relay_enabled"
    private const val KEY_RELAY_URL = "relay_url"
    private const val KEY_RELAY_ROOM = "relay_room"
    private const val KEY_RELAY_KEY = "relay_key"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------
    // 开机自启
    // ---------------------------------------------------------------------

    /**
     * 开机自启。默认 **开** —— 车机这个使用场景，用户装完就希望它一直活着，
     * 默认关会让「装完就没反应」变成第一印象。
     */
    fun autoStart(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    // ---------------------------------------------------------------------
    // 设备名
    // ---------------------------------------------------------------------

    fun deviceName(ctx: Context): String =
        sp(ctx).getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            ?: defaultDeviceName()

    fun setDeviceName(ctx: Context, name: String) {
        sp(ctx).edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    private fun defaultDeviceName(): String = "车载哨兵"

    // ---------------------------------------------------------------------
    // 采集源（JSON）
    // ---------------------------------------------------------------------

    fun channelSources(ctx: Context): String? =
        sp(ctx).getString(KEY_CHANNEL_SOURCES, null)?.takeIf { it.isNotBlank() }

    fun setChannelSources(ctx: Context, json: String) {
        sp(ctx).edit().putString(KEY_CHANNEL_SOURCES, json).apply()
    }

    fun sourcesSeeded(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SOURCES_SEEDED, false)

    fun setSourcesSeeded(ctx: Context, seeded: Boolean) {
        sp(ctx).edit().putBoolean(KEY_SOURCES_SEEDED, seeded).apply()
    }

    // ---------------------------------------------------------------------
    // 远程访问（中继隧道）
    // ---------------------------------------------------------------------

    fun relayEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_RELAY_ENABLED, false)

    fun setRelayEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_RELAY_ENABLED, enabled).apply()
    }

    /** 中继地址，例如 `wss://relay.example.com` 或 `http://1.2.3.4:8787` */
    fun relayUrl(ctx: Context): String =
        sp(ctx).getString(KEY_RELAY_URL, null)?.trim().orEmpty()

    fun setRelayUrl(ctx: Context, url: String) {
        sp(ctx).edit().putString(KEY_RELAY_URL, url.trim()).apply()
    }

    /** 房间号 = 这台车机的唯一标识，手机端填同一个。首次访问即落盘，避免界面每次刷新都变 */
    fun relayRoom(ctx: Context): String {
        val stored = sp(ctx).getString(KEY_RELAY_ROOM, null)?.trim()
        if (!stored.isNullOrBlank()) return stored
        val generated = randomRoom()
        sp(ctx).edit().putString(KEY_RELAY_ROOM, generated).apply()
        return generated
    }

    fun setRelayRoom(ctx: Context, room: String) {
        sp(ctx).edit().putString(KEY_RELAY_ROOM, room.trim()).apply()
    }

    /** 访问密钥，防止别人猜中房间号就能看你的车 */
    fun relayKey(ctx: Context): String {
        val stored = sp(ctx).getString(KEY_RELAY_KEY, null)?.trim()
        if (!stored.isNullOrBlank()) return stored
        val generated = randomKey()
        sp(ctx).edit().putString(KEY_RELAY_KEY, generated).apply()
        return generated
    }

    fun setRelayKey(ctx: Context, key: String) {
        sp(ctx).edit().putString(KEY_RELAY_KEY, key.trim()).apply()
    }

    fun ensureRelayIdentity(ctx: Context) {
        if (sp(ctx).getString(KEY_RELAY_ROOM, null).isNullOrBlank()) setRelayRoom(ctx, randomRoom())
        if (sp(ctx).getString(KEY_RELAY_KEY, null).isNullOrBlank()) setRelayKey(ctx, randomKey())
    }

    private fun randomRoom(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        return (1..6).map { alphabet.random() }.joinToString("")
    }

    private fun randomKey(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..24).map { alphabet.random() }.joinToString("")
    }
}

package com.aurora.carsentry

import org.json.JSONArray
import org.json.JSONObject

// =============================================================================
// 数据模型 —— 与 iOS 端 Core/Models.swift 一一对应
// 字段名全部 camelCase，时间戳统一 epoch 秒。协议见 docs/PROTOCOL.md
// =============================================================================

data class StorageInfo(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val retentionDays: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("totalBytes", totalBytes)
        put("usedBytes", usedBytes)
        put("freeBytes", freeBytes)
        put("retentionDays", retentionDays)
    }
}

data class CameraInfo(
    val id: String,
    val name: String,
    val position: String,
    val online: Boolean,
    val resolution: String,
    val fps: Int,
    val bitrateKbps: Int,
    val hlsPath: String?,
    val snapshotPath: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("position", position)
        put("online", online)
        put("resolution", resolution)
        put("fps", fps)
        put("bitrateKbps", bitrateKbps)
        put("hlsPath", hlsPath ?: JSONObject.NULL)
        put("snapshotPath", snapshotPath ?: JSONObject.NULL)
    }
}

data class ClipInfo(
    val id: String,
    val cameraId: String,
    val startAt: Long,
    val durationSec: Int,
    val sizeBytes: Long,
    val source: String,
    val locked: Boolean,
    val hlsPath: String,
    val thumbPath: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("cameraId", cameraId)
        put("startAt", startAt)
        put("durationSec", durationSec)
        put("sizeBytes", sizeBytes)
        put("source", source)
        put("locked", locked)
        put("hlsPath", hlsPath)
        put("thumbPath", thumbPath ?: JSONObject.NULL)
    }
}

data class EventInfo(
    val id: String,
    val kind: String,
    val level: Int,
    val at: Long,
    val cameraId: String,
    val note: String,
    val clipId: String?,
    var isRead: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("level", level)
        put("at", at)
        put("cameraId", cameraId)
        put("note", note)
        put("clipId", clipId ?: JSONObject.NULL)
        put("isRead", isRead)
    }
}

// =============================================================================
// 全局状态
//
// 只有一个进程、一个服务，用一个带锁的 object 就够了，不引 ViewModel 层。
// UI 用 StateFlow 订阅「已启动 / 四路状态 / 推流开关」这几项。
// =============================================================================

object SentryState {

    @Volatile var deviceName: String = "车载哨兵"
    @Volatile var model: String = "Android 车机"
    @Volatile var firmware: String = "1.0.0"
    @Volatile var startedAtMs: Long = 0L
    @Volatile var parkingMode: Boolean = true
    @Volatile var recording: Boolean = false
    @Volatile var httpPort: Int = 8710

    private val lock = Any()
    private val clips = ArrayList<ClipInfo>()
    private val events = ArrayList<EventInfo>()
    private var cameras: List<CameraInfo> = emptyList()

    fun setCameras(list: List<CameraInfo>) = synchronized(lock) {
        cameras = list
    }

    fun cameras(): List<CameraInfo> = synchronized(lock) { cameras.toList() }

    fun addClip(clip: ClipInfo) = synchronized(lock) {
        clips.add(clip)
        // 只留 3 天，和 storage.retentionDays 对齐
        val cutoff = System.currentTimeMillis() / 1000 - 3 * 86_400
        clips.removeAll { it.startAt < cutoff }
        Unit
    }

    fun clips(): List<ClipInfo> = synchronized(lock) {
        clips.sortedByDescending { it.startAt }
    }

    fun addEvent(event: EventInfo) = synchronized(lock) {
        events.add(0, event)
        if (events.size > 300) {
            events.subList(200, events.size).clear()
        }
        Unit
    }

    fun events(since: Long?): List<EventInfo> = synchronized(lock) {
        if (since == null) events.toList() else events.filter { it.at > since }
    }

    fun markAllRead() = synchronized(lock) {
        events.forEach { it.isRead = true }
        Unit
    }

    fun unreadCount(): Int = synchronized(lock) { events.count { !it.isRead } }

    fun findClip(id: String): ClipInfo? = synchronized(lock) { clips.firstOrNull { it.id == id } }

    fun storage(): StorageInfo {
        val root = android.os.Environment.getDataDirectory()
        val total = root.totalSpace
        val free = root.usableSpace
        return StorageInfo(
            totalBytes = total,
            usedBytes = total - free,
            freeBytes = free,
            retentionDays = 3,
        )
    }

    fun snapshotJson(): JSONObject {
        val cameraArray = JSONArray()
        cameras().forEach { cameraArray.put(it.toJson()) }

        return JSONObject().apply {
            put("deviceName", deviceName)
            put("model", model)
            put("firmware", firmware)
            put(
                "uptimeSec",
                if (startedAtMs == 0L) 0 else (System.currentTimeMillis() - startedAtMs) / 1000
            )
            put("parkingMode", parkingMode)
            put("recording", recording)
            put("storage", storage().toJson())
            put("cameras", cameraArray)
            put("unreadEvents", unreadCount())
        }
    }
}

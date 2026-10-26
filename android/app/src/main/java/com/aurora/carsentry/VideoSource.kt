package com.aurora.carsentry

import android.content.Context
import org.json.JSONObject

/**
 * 采集源的统一抽象。
 *
 * 车机上的摄像头来源极其分散：标准 Camera2、外接 USB(UVC)、厂商塞在
 * RTSP / 私有 socket 里的环视盒子、甚至只有一个能读到裸 H.264 的管道。
 * 每个源只要把画面收敛成「Annex-B 的访问单元 + 时间戳」交给 sink，
 * 后面的切片、HLS、HTTP 全部复用同一套。
 */
interface VideoSource {

    /** 与 [SourceType] 对应，用于日志与 UI */
    val kind: String

    /** 给 UI 看的一行说明，例如「USB · 640×480 MJPEG」 */
    val describe: String

    /**
     * 异步型源：`start()` 立刻返回，之后靠回调持续出帧（Camera2 / UVC）。
     *
     * 阻塞型源（RTSP / 裸流 / 测试信号）的 `start()` 会一直调用到流结束，
     * 返回即代表这一轮采集结束。
     */
    val async: Boolean get() = false

    /** 异步型源必须如实上报，否则重连逻辑会一直等下去 */
    fun isAlive(): Boolean = true

    /**
     * 开始采集。**允许阻塞**（RTSP / 裸流会一直读 socket 直到 stop 被调用），
     * 也允许异步回调（Camera2）。耗时操作请在内部线程做，不要占住调用方线程。
     */
    fun start(context: Context, sink: VideoSink)

    /** 停止采集，必须可重入、且不能抛异常。 */
    fun stop()
}

/** 采集源的出口。实现方需要保证线程安全（多个源回调线程不同）。 */
interface VideoSink {

    /**
     * 一帧完整画面（Annex-B，含 SPS/PPS 的关键帧优先）。
     * @param ptsUs 微秒时间戳，切片按它算时长
     */
    fun onFrame(annexB: ByteArray, ptsUs: Long, keyFrame: Boolean, epochSec: Long)

    fun onStatus(online: Boolean, resolution: String, fps: Int, bitrateKbps: Int)

    /** 源已经无法继续（拔了 USB、RTSP 断开），ChannelRunner 会按退避重建 */
    fun onFatal(reason: String)
}

// =============================================================================
// 采集源描述
// =============================================================================

object SourceType {
    const val NONE = "none"
    const val CAMERA2 = "camera2"
    const val UVC = "uvc"
    const val RTSP = "rtsp"
    const val RAW = "raw"
    const val TEST = "test"

    val all = listOf(CAMERA2, UVC, RTSP, RAW, TEST, NONE)

    fun label(type: String): String = when (type) {
        CAMERA2 -> "Camera2"
        UVC -> "USB(UVC)"
        RTSP -> "RTSP"
        RAW -> "裸流 H.264"
        TEST -> "测试信号"
        else -> "未配置"
    }
}

/**
 * 一个机位的采集源配置。序列化后存进 SharedPreferences。
 *
 * @param type   [SourceType] 之一
 * @param target 源的定位串：
 *               camera2 → cameraId；uvc → UsbDevice.deviceName；
 *               rtsp → rtsp://…；raw → tcp://host:port 或 /path/to/fifo
 * @param label  用户起的名字，只用于显示
 * @param extra  源特有的参数（rtsp 的传输方式、raw 的 fps 等）
 */
data class SourceSpec(
    val type: String = SourceType.NONE,
    val target: String = "",
    val label: String = "",
    val extra: Map<String, String> = emptyMap(),
) {

    val isNone: Boolean get() = type == SourceType.NONE || target.isBlank() && type != SourceType.TEST

    fun extraInt(key: String, fallback: Int): Int = extra[key]?.toIntOrNull() ?: fallback

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("target", target)
        put("label", label)
        val extras = JSONObject()
        extra.forEach { (k, v) -> extras.put(k, v) }
        put("extra", extras)
    }

    companion object {

        val none = SourceSpec()

        fun fromJson(obj: JSONObject?): SourceSpec? {
            if (obj == null) return null
            val type = obj.optString("type", SourceType.NONE)
            val target = obj.optString("target", "")
            val label = obj.optString("label", "")
            val extras = HashMap<String, String>()
            obj.optJSONObject("extra")?.let { o ->
                o.keys().forEach { key -> extras[key] = o.optString(key, "") }
            }
            return SourceSpec(type, target, label, extras)
        }

        fun of(type: String, target: String, label: String = "", extra: Map<String, String> = emptyMap()) =
            SourceSpec(type, target, label, extra)
    }
}

// =============================================================================
// 工厂
// =============================================================================

object SourceFactory {

    fun create(spec: SourceSpec, channelId: String): VideoSource? = when (spec.type) {
        SourceType.CAMERA2 -> if (spec.target.isBlank()) null else Camera2Source(spec.target, spec.label)

        SourceType.UVC -> if (spec.target.isBlank()) null else UvcSource(spec.target)

        SourceType.RTSP -> if (spec.target.isBlank()) null else RtspSource(spec.target, spec.extra)

        SourceType.RAW -> if (spec.target.isBlank()) null else RawH264Source(spec.target, spec.extra)

        SourceType.TEST -> TestPatternSource(channelId)

        else -> null
    }
}

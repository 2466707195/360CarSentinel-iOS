package com.aurora.carsentry

import android.content.Context
import org.json.JSONObject

/** 一路机位的定义。顺序就是手机端宫格的顺序。 */
data class ChannelDef(val id: String, val name: String, val position: String)

object Channels {
    val all = listOf(
        ChannelDef("front", "前视", "front"),
        ChannelDef("rear", "后视", "rear"),
        ChannelDef("left", "左视", "left"),
        ChannelDef("right", "右视", "right"),
    )

    fun byId(id: String): ChannelDef? = all.firstOrNull { it.id == id }
}

/**
 * 采集源的发现与分配。
 *
 * 车机上「cameraId 0/1/2/3 到底对应车上哪个方位」没法自动判断 ——
 * 不同厂商的映射完全不同。所以出厂时按 Camera2 顺序给一个默认值，
 * 剩下的交给用户在界面上改，改完落盘。
 */
class SourceRegistry(private val context: Context) {

    data class Candidate(
        val spec: SourceSpec,
        val title: String,
        val detail: String,
        val group: String,
    )

    // ---------------------------------------------------------------------
    // 发现
    // ---------------------------------------------------------------------

    /** 当前设备上所有能用的采集源 */
    fun candidates(): List<Candidate> {
        val list = ArrayList<Candidate>()

        CameraEnumerator.list(context).forEach { found ->
            list.add(
                Candidate(
                    spec = SourceSpec(SourceType.CAMERA2, found.cameraId, found.title),
                    title = found.title,
                    detail = found.detail,
                    group = "标准 Camera2",
                )
            )
        }

        UvcSource.list(context).forEach { (deviceName, name, detail) ->
            list.add(
                Candidate(
                    spec = SourceSpec(SourceType.UVC, deviceName, name),
                    title = name,
                    detail = "$detail · UVC 直连",
                    group = "USB 摄像头（UVC）",
                )
            )
        }

        list.add(
            Candidate(
                spec = SourceSpec(SourceType.TEST, "test", "测试信号"),
                title = "测试信号",
                detail = "不接摄像头，用来验证编码 / 网络 / 手机端是否正常",
                group = "诊断",
            )
        )

        return list
    }

    /** 常见的手动配置模板，UI 里一键填 */
    fun manualTemplates(): List<SourceSpec> = listOf(
        SourceSpec(SourceType.RTSP, "rtsp://192.168.1.10:554/stream1"),
        SourceSpec(SourceType.RAW, "tcp://192.168.1.10:9000"),
        SourceSpec(SourceType.RAW, "/data/vendor/camera/cam0.h264"),
    )

    // ---------------------------------------------------------------------
    // 读取 / 保存
    // ---------------------------------------------------------------------

    fun allSpecs(): Map<String, SourceSpec> {
        val raw = SentryPrefs.channelSources(context) ?: return emptyMap()
        val map = HashMap<String, SourceSpec>()
        try {
            val root = JSONObject(raw)
            Channels.all.forEach { channel ->
                SourceSpec.fromJson(root.optJSONObject(channel.id))?.let { map[channel.id] = it }
            }
        } catch (e: Exception) {
            // 配置坏了就当作空，下次会自动重新分配
        }
        return map
    }

    fun specFor(channelId: String): SourceSpec = allSpecs()[channelId] ?: SourceSpec.none

    fun assign(channelId: String, spec: SourceSpec) {
        val map = allSpecs().toMutableMap()
        map[channelId] = spec
        save(map)
    }

    private fun save(map: Map<String, SourceSpec>) {
        val root = JSONObject()
        Channels.all.forEach { channel ->
            map[channel.id]?.let { root.put(channel.id, it.toJson()) }
        }
        SentryPrefs.setChannelSources(context, root.toString())
    }

    // ---------------------------------------------------------------------
    // 默认分配
    // ---------------------------------------------------------------------

    /**
     * 首次启动 / 用户点「自动分配」时用：Camera2 按顺序铺满四路；
     * 没有 Camera2 但插了 USB 摄像头时，把 UVC 依次铺上去。
     */
    fun autoAssign(): Map<String, SourceSpec> {
        val cams = CameraEnumerator.list(context)
        val usbs = UvcSource.list(context)
        val map = HashMap<String, SourceSpec>()

        if (cams.isNotEmpty()) {
            // 外接 USB 摄像头排在后面：车机自带的倒车/环视更可能是主画面
            val ordered = cams.sortedBy { if (it.facing == CameraFacing.EXTERNAL) 1 else 0 }
            Channels.all.forEachIndexed { index, channel ->
                val found = ordered.getOrNull(index) ?: return@forEachIndexed
                map[channel.id] = SourceSpec(
                    SourceType.CAMERA2,
                    found.cameraId,
                    found.title,
                )
            }
        }

        usbs.forEach { (deviceName, name, _) ->
            val channel = Channels.all.firstOrNull { map[it.id] == null } ?: return@forEach
            map[channel.id] = SourceSpec(SourceType.UVC, deviceName, name)
        }

        Channels.all.forEach { channel ->
            if (map[channel.id] == null) map[channel.id] = SourceSpec.none
        }

        save(map)
        SentryPrefs.setSourcesSeeded(context, true)
        return map
    }

    /** 只在从没配置过的时候自动分配一次，之后尊重用户的选择 */
    fun ensureDefaults() {
        if (SentryPrefs.sourcesSeeded(context) && SentryPrefs.channelSources(context) != null) return
        autoAssign()
    }

    // ---------------------------------------------------------------------
    // 展示
    // ---------------------------------------------------------------------

    fun summary(spec: SourceSpec): String = when {
        spec.isNone -> "未配置"
        spec.type == SourceType.TEST -> "测试信号"
        spec.label.isNotBlank() -> "${SourceType.label(spec.type)} · ${spec.label}"
        else -> "${SourceType.label(spec.type)} · ${spec.target.take(40)}"
    }
}

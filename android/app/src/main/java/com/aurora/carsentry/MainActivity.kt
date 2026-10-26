package com.aurora.carsentry

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aurora.carsentry.ui.AppTopBar
import com.aurora.carsentry.ui.AuroraBackdrop
import com.aurora.carsentry.ui.AuroraTheme
import com.aurora.carsentry.ui.SectionCard
import com.aurora.carsentry.ui.StatusPill
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startSentry()
            ServiceBus.notifyChanged()
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        ensureCamera()

        setContent {
            AuroraTheme {
                Dashboard(
                    onStart = { ensureCamera() },
                    onStop = { stopSentry() },
                )
            }
        }
    }

    private fun ensureCamera() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        // 用 RTSP / UVC / 裸流的车机其实不需要相机权限，但 Camera2 需要；
        // 这里只在没有权限时申请一次，不阻塞启动。
        if (granted) startSentry() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startSentry() {
        val intent = Intent(this, SentryService::class.java).apply {
            putExtra("deviceName", SentryPrefs.deviceName(this@MainActivity))
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSentry() {
        stopService(Intent(this, SentryService::class.java))
        ServiceBus.notifyChanged()
    }
}

// =============================================================================
// 主界面
// =============================================================================

@Composable
private fun Dashboard(onStart: () -> Unit, onStop: () -> Unit) {

    val tick by ServiceBus.tick.collectAsState()
    val ctx = LocalContext.current
    var autoStart by remember { mutableStateOf(SentryPrefs.autoStart(ctx)) }
    var editingChannel by remember { mutableStateOf<ChannelDef?>(null) }
    var editingRelay by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        SentryService.instance?.refreshCameraInfo()
    }

    val cameras = remember(tick) { SentryState.cameras() }
    val running = SentryService.instance != null
    val onlineCount = cameras.count { it.online }
    val ip = remember(tick) { localIpAddress() ?: "--" }
    val registry = remember { SourceRegistry(ctx) }
    val specs = remember(tick) { registry.allSpecs() }

    Box(Modifier.fillMaxSize()) {
        AuroraBackdrop()

        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = "车载哨兵 · 车机端",
                subtitle = "把车内摄像头的画面实时推给手机",
            )

            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    Modifier
                        .weight(0.44f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    serviceCard(
                        running = running,
                        onlineCount = onlineCount,
                        total = cameras.size,
                        ip = ip,
                        onStart = onStart,
                        onStop = onStop,
                    )
                    sourcesCard(
                        registry = registry,
                        specs = specs,
                        running = running,
                        onEdit = { editingChannel = it },
                        onAutoAssign = { SentryService.instance?.autoAssignSources() },
                    )
                    relayCard(ip = ip, onConfigure = { editingRelay = true })
                    autoStartCard(
                        enabled = autoStart,
                        onChange = {
                            autoStart = it
                            SentryPrefs.setAutoStart(ctx, it)
                        },
                    )
                    connectCard(ip = ip)
                }

                Column(
                    Modifier
                        .weight(0.56f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        cameras.getOrNull(0)?.let { CameraStatusCard(it, Modifier.weight(1f)) }
                            ?: PlaceholderCamera(Modifier.weight(1f))
                        cameras.getOrNull(1)?.let { CameraStatusCard(it, Modifier.weight(1f)) }
                            ?: PlaceholderCamera(Modifier.weight(1f))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        cameras.getOrNull(2)?.let { CameraStatusCard(it, Modifier.weight(1f)) }
                            ?: PlaceholderCamera(Modifier.weight(1f))
                        cameras.getOrNull(3)?.let { CameraStatusCard(it, Modifier.weight(1f)) }
                            ?: PlaceholderCamera(Modifier.weight(1f))
                    }
                    tipsCard()
                }
            }
        }
    }

    editingChannel?.let { channel ->
        SourcePickerDialog(
            channel = channel,
            registry = registry,
            current = specs[channel.id] ?: SourceSpec.none,
            onDismiss = { editingChannel = null },
            onPick = { spec ->
                registry.assign(channel.id, spec)
                SentryService.instance?.applySourceChange(channel.id)
                editingChannel = null
                ServiceBus.notifyChanged()
            },
        )
    }

    if (editingRelay) {
        RelayConfigDialog(
            onDismiss = { editingRelay = false },
            onSaved = {
                editingRelay = false
                SentryService.instance?.restartRelay()
            },
        )
    }
}

// =============================================================================
// 卡片
// =============================================================================

@Composable
private fun serviceCard(
    running: Boolean,
    onlineCount: Int,
    total: Int,
    ip: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "推流服务",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (running) "已启动 · 端口 ${SentryState.httpPort}" else "未启动",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = if (running) "运行中" else "已停止",
                color = if (running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        InfoLine("手机连接地址", "$ip:${SentryState.httpPort}")
        InfoLine("在线机位", "$onlineCount / $total")
        InfoLine("自动发现", "_carsentry._tcp")
        SentryService.lastError?.let { InfoLine("注意", it) }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                enabled = !running,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
            ) {
                Text("启动服务", fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = onStop,
                enabled = running,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                Text("停止", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * 采集源卡片：四路机位各显示「现在接的是什么」。
 *
 * 这一块是整个车机端最需要人介入的地方 —— 没人能替你猜出
 * cameraId 0 到底是前视还是后视，只能让用户点一下改。
 */
@Composable
private fun sourcesCard(
    registry: SourceRegistry,
    specs: Map<String, SourceSpec>,
    running: Boolean,
    onEdit: (ChannelDef) -> Unit,
    onAutoAssign: () -> Unit,
) {
    val tick by ServiceBus.tick.collectAsState()

    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "采集源",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "点任意一路改成你要的摄像头",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAutoAssign) {
                Text("自动分配", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(6.dp))

        Channels.all.forEach { channel ->
            val spec = specs[channel.id] ?: SourceSpec.none
            val status = remember(tick, channel.id, running) {
                SentryService.instance?.runnerStatus(channel.id)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(channel) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            RoundedCornerShape(9.dp)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        channel.name.first().toString(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        status ?: registry.summary(spec),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (spec.isNone) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "改",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun relayCard(ip: String, onConfigure: () -> Unit) {
    val tick by ServiceBus.tick.collectAsState()
    val ctx = LocalContext.current
    var enabled by remember(tick) { mutableStateOf(SentryPrefs.relayEnabled(ctx)) }

    val info = remember(tick) { SentryService.instance?.relayInfo() }
    val detail = info?.third ?: if (enabled) "未启动" else "未启用"
    val connected = SentryService.relayConnected

    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "远程观看",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "不在车旁边也能看：车机主动连中继，手机走 https",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    SentryPrefs.setRelayEnabled(ctx, it)
                    SentryService.instance?.restartRelay()
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        InfoLine("状态", if (connected) "已连接" else detail)
        InfoLine("房间号", SentryPrefs.relayRoom(ctx))
        InfoLine("密钥", SentryPrefs.relayKey(ctx).take(8) + "…")

        val url = SentryPrefs.relayUrl(ctx)
        if (url.isNotBlank()) {
            InfoLine("中继地址", url)
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onConfigure,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                Text("设置中继", fontWeight = FontWeight.SemiBold)
            }
            if (enabled) {
                OutlinedButton(
                    onClick = { SentryService.instance?.restartRelay() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                ) {
                    Text("重连", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "需要一台能上公网的中继服务（仓库里 relay/ 目录，一条命令部署）。" +
                "没有中继时用局域网直连：手机填 $ip:${SentryState.httpPort}。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun autoStartCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val receiverAlive = remember(enabled) { bootReceiverEnabled(ctx) }

    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "开机自启",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (enabled) "点火开机后自动开始推流，不用上车点 App" else "开机后不会自动启动，需手动点「启动服务」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }

        Spacer(Modifier.height(8.dp))
        InfoLine("开机广播", if (receiverAlive) "已注册" else "被系统禁用")
        InfoLine("生效时机", "下次开机 / App 升级后")

        Spacer(Modifier.height(8.dp))
        Text(
            "部分车机会拦截第三方 App 自启。如果重启后没起来，去系统的「自启动管理 / 后台白名单」" +
                "里把「车载哨兵」放行一次 —— 这一步只能手动做，App 自己没有权限改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp,
        )
    }
}

/** 有些车机系统会直接把组件禁用掉，这里查一下真实状态，别让用户以为开关开了就生效 */
private fun bootReceiverEnabled(ctx: Context): Boolean = try {
    val component = ComponentName(ctx, BootReceiver::class.java)
    ctx.packageManager.getComponentEnabledSetting(component) !=
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
} catch (e: Exception) {
    true
}

@Composable
private fun connectCard(ip: String) {
    SectionCard {
        Text(
            "手机端怎么连",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "1. 车机开热点（或让手机和车机连同一个 WiFi）\n" +
                "2. 打开「360 车载哨兵」App → 设备 → 连接车机\n" +
                "3. 自动扫到「车载哨兵」就点它；扫不到就手输 $ip",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun CameraStatusCard(camera: CameraInfo, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                camera.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            Spacer(Modifier.weight(1f))
            StatusPill(
                text = if (camera.online) "在线" else "离线",
                color = if (camera.online) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(74.dp)
                .background(Color(0xFF0B0B12), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    camera.resolution,
                    color = Color.White.copy(alpha = 0.86f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (camera.online) "${camera.fps} fps · ${camera.bitrateKbps / 1000.0f} Mbps" else "无信号",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun PlaceholderCamera(modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier) {
        Text(
            "空闲机位",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(74.dp)
                .background(Color(0xFF14141C), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("未检测到摄像头", color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun tipsCard() {
    SectionCard {
        Text(
            "摄像头接不进来怎么办",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            "按顺序排查：\n" +
                "1. 先选「测试信号」—— 手机能看到彩条，说明编码 / 网络 / 手机端都没问题，问题在采集源\n" +
                "2. Camera2 里没有你的摄像头 → 换成「USB(UVC)」，需要插着摄像头重新扫描\n" +
                "3. 装的是 360 环视盒子 / 后装记录仪 → 用 RTSP，填盒子文档里的 rtsp:// 地址\n" +
                "4. 厂商只给了私有接口 → 用「裸流」，填 tcp://IP:端口 或设备节点路径\n" +
                "5. 全都试过还是不行 → 说明摄像头走的是厂商 SDK，需要厂商提供取流方式",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// =============================================================================
// 采集源选择弹窗
// =============================================================================

@Composable
private fun SourcePickerDialog(
    channel: ChannelDef,
    registry: SourceRegistry,
    current: SourceSpec,
    onDismiss: () -> Unit,
    onPick: (SourceSpec) -> Unit,
) {
    val candidates = remember { registry.candidates() }

    var manualType by remember { mutableStateOf(SourceType.RTSP) }
    var manualValue by remember {
        mutableStateOf(
            if (current.type == SourceType.RTSP || current.type == SourceType.RAW) current.target else ""
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${channel.name} · 采集源") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {

                Text(
                    "这台车机上能用的源（重新插拔 USB 摄像头后需要重进本页扫描）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                var lastGroup = ""
                candidates.forEach { candidate ->
                    if (candidate.group != lastGroup) {
                        lastGroup = candidate.group
                        Text(
                            candidate.group,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                    }

                    val selected = current.type == candidate.spec.type &&
                        current.target == candidate.spec.target

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(candidate.spec) }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                candidate.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                candidate.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (selected) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "手动填写",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(SourceType.RTSP to "RTSP", SourceType.RAW to "裸流").forEach { (type, label) ->
                        val active = manualType == type
                        OutlinedButton(
                            onClick = { manualType = type },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (active) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                } else {
                                    Color.Transparent
                                }
                            ),
                        ) {
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = manualValue,
                    onValueChange = { manualValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(
                            if (manualType == SourceType.RTSP) "rtsp://192.168.1.10:554/stream1"
                            else "tcp://192.168.1.10:9000 或 /data/vendor/cam0.h264"
                        )
                    },
                )

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (manualValue.isNotBlank()) {
                                onPick(SourceSpec(manualType, manualValue.trim()))
                            }
                        },
                        enabled = manualValue.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("使用这个地址")
                    }
                    OutlinedButton(
                        onClick = { onPick(SourceSpec.none) },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("不启用")
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "提示：UVC 摄像头如果已经被系统注册成 Camera2 外接摄像头，" +
                        "上面「标准 Camera2」里会出现，优先用它（零拷贝，更省 CPU）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

// =============================================================================
// 中继配置弹窗
// =============================================================================

@Composable
private fun RelayConfigDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var url by remember { mutableStateOf(SentryPrefs.relayUrl(ctx)) }
    var room by remember {
        mutableStateOf(SentryPrefs.relayRoom(ctx).also { SentryPrefs.setRelayRoom(ctx, it) })
    }
    var key by remember {
        mutableStateOf(SentryPrefs.relayKey(ctx).also { SentryPrefs.setRelayKey(ctx, it) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("远程观看 · 中继") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "车机会主动连到中继服务，手机端填中继地址 + 房间号 + 密钥即可在外网观看。" +
                        "中继服务在仓库 relay/ 目录，一条命令就能部署。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("中继地址 wss://relay.example.com") },
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("房间号（两台填一样）") },
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("密钥（两台填一样）") },
                )

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString("${room.trim()} / ${key.trim()}"))
                    }
                ) {
                    Text("复制房间号与密钥", style = MaterialTheme.typography.bodySmall)
                }

                Text(
                    "房间号 + 密钥相当于门锁：别人猜不到房间号，也拿不到密钥。" +
                        "泄露了就点「换一组」重新生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    SentryPrefs.setRelayUrl(ctx, url)
                    SentryPrefs.setRelayRoom(ctx, room)
                    SentryPrefs.setRelayKey(ctx, key)
                    if (url.isNotBlank()) SentryPrefs.setRelayEnabled(ctx, true)
                    ServiceBus.notifyChanged()
                    onSaved()
                }
            ) { Text("保存并连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 拿不到就返回 null，UI 显示「--」 */
private fun localIpAddress(): String? {
    return try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}

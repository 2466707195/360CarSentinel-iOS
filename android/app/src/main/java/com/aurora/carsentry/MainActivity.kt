package com.aurora.carsentry

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

@Composable
private fun Dashboard(onStart: () -> Unit, onStop: () -> Unit) {

    val tick by ServiceBus.tick.collectAsState()
    val ctx = LocalContext.current
    var autoStart by remember { mutableStateOf(SentryPrefs.autoStart(ctx)) }

    LaunchedEffect(tick) {
        SentryService.instance?.refreshCameraInfo()
    }

    val cameras = remember(tick) { SentryState.cameras() }
    val running = SentryService.instance != null
    val onlineCount = cameras.count { it.online }
    val ip = remember(tick) { localIpAddress() ?: "--" }

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
                        .weight(0.42f)
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
                        .weight(0.58f)
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
}

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
            "关于摄像头来源",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            "车机端用 Android Camera2 取流，按 cameraIdList 顺序映射到前 / 后 / 左 / 右。" +
                "如果车上装的是 USB(UVC) 摄像头或 360 环视盒子，Camera2 可能看不到它们，" +
                "需要换成对应的采集源（详见 docs/PROTOCOL.md 末尾）。",
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
        )
    }
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

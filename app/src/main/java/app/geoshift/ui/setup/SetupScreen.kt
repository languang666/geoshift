package app.geoshift.ui.setup

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.geoshift.R
import app.geoshift.ui.AppState
import app.geoshift.ui.AppViewModel
import app.geoshift.ui.components.Segmented
import app.geoshift.ui.components.IosSwitch
import app.geoshift.ui.components.PortalCard
import app.geoshift.ui.theme.Success
import app.geoshift.data.Backend

/** Permission / settings targets the activity knows how to open. */
enum class SystemTarget { LOCATION_PERMISSION, NOTIFICATION_PERMISSION, WIFI_PERMISSION, OVERLAY, MOCK_APP, LOCATION_SWITCH, LSPOSED, APP_DETAILS }

@Composable
fun SetupScreen(s: AppState, vm: AppViewModel, onOpen: (SystemTarget) -> Unit, onBack: (() -> Unit)?, onDone: () -> Unit) {
    val accepted = s.settings.privacyAccepted
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().navigationBarsPadding().padding(bottom = 24.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } else Spacer(Modifier.width(12.dp))
        }

        if (!accepted) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(R.drawable.ic_logo), null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(72.dp))
                Spacer(Modifier.size(16.dp))
                Text("欢迎使用 Geoshift", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text("轻氧活力虚拟定位与环境仿真", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(16.dp))
            PortalCard(Modifier.padding(horizontal = 16.dp)) {
                Text("使用须知", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
                Text(
                    "本应用是一款虚拟定位工具，用于开发者调试自己的应用和保护位置隐私。请仅在你有权测试的应用上使用，不得用于任何违法用途。\n\n" +
                        "· 位置、路线、预设和设置只保存在本机。\n" +
                        "· 地图瓦片按您的选择向 OpenStreetMap、高德或 Esri 请求所查看的区域；地点搜索仅在你填写高德 Key 后使用。\n" +
                        "· 标准模拟模式由系统保留模拟定位标记；基站 / GNSS 配置不会修改真实无线网络。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(16.dp))
                Button(onClick = { vm.run<Unit> { updateSettings { it.copy(privacyAccepted = true) } } }, modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 14.dp)) { Text("同意并继续") }
            }
            return@Column
        }

        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_logo), null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(52.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("权限与模块", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("验证所需系统权限与框架环境", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.size(20.dp))

        Step(1, "应用权限", "定位与通知是必需的，其余按需授予")
        PortalCard(Modifier.padding(horizontal = 16.dp)) {
            Permission(Icons.Rounded.LocationOn, "精确位置", "模拟与扫描必需", s.caps.preciseLocation) { onOpen(SystemTarget.LOCATION_PERMISSION) }
            Permission(Icons.Rounded.LocationOn, "系统定位开关", "需要保持开启", s.caps.locationEnabled, action = "去设置") { onOpen(SystemTarget.LOCATION_SWITCH) }
            if (Build.VERSION.SDK_INT >= 33) {
                Permission(Icons.Rounded.Notifications, "通知", "显示模拟运行状态，可随时停止", s.caps.notifications) { onOpen(SystemTarget.NOTIFICATION_PERMISSION) }
                Permission(Icons.Rounded.Wifi, "附近 Wi-Fi 设备", "扫描 Wi-Fi 时需要", s.caps.nearbyWifi) { onOpen(SystemTarget.WIFI_PERMISSION) }
            }
            Permission(Icons.Rounded.PictureInPicture, "悬浮窗", "摇杆悬浮窗需要", s.caps.overlay, action = "去设置") { onOpen(SystemTarget.OVERLAY) }
        }

        Step(2, "模拟模式", "选择一种工作方式")
        PortalCard(Modifier.padding(horizontal = 16.dp)) {
            val backends = listOf(Backend.MOCK to "标准模拟", Backend.XPOSED to "Xposed")
            Segmented(backends.map { it.second }, backends.indexOfFirst { it.first == s.settings.backend }) { i ->
                val key = backends[i].first
                if (s.settings.backend != key) vm.run<Unit>("已切换模拟类型") { updateSettings { it.copy(backend = key) } }
            }
            Spacer(Modifier.size(14.dp))
            if (!s.settings.xposed) {
                Guide(1, "打开系统「开发者选项」（在关于手机中连续点击版本号可开启）", null)
                Guide(2, "找到「选择模拟位置信息应用」，选择 Geoshift", s.caps.mockSelected)
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(onClick = { onOpen(SystemTarget.MOCK_APP) }, modifier = Modifier.fillMaxWidth()) { Text("打开开发者选项") }
            } else {
                Guide(1, "安装支持 libxposed API 101+ 的框架（如 Vector）。原版 LSPosed 1.x 不支持 Geoshift 3。", s.caps.framework?.let { it.api >= 101 })
                Guide(2, "在框架管理器的「模块」中启用 Geoshift。作用域已固定为系统框架、电话服务和蓝牙，无需手动勾选。", s.caps.framework != null)
                Guide(3, "重启手机，回到这里点「重新检查」。", s.caps.hookActive)
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { onOpen(SystemTarget.LSPOSED) }, modifier = Modifier.weight(1f)) { Text("打开框架管理器") }
                    OutlinedButton(onClick = { vm.run<Boolean>(onDone = { vm.toast(if (it) "系统模块运行中" else "系统模块未运行：请确认已在框架中启用 Geoshift 并重启") }) { capabilities().hookActive } }) { Text("重新检查") }
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    when {
                        s.caps.hookActive -> "✓ 系统模块运行中（${s.caps.framework?.let { "${it.name} ${it.version} · API ${it.api}" } ?: "框架信息不可用"}）；可在「控制台 → 系统诊断」查看各入口状态。"
                        s.caps.framework != null -> "已连接框架 ${s.caps.framework.name} ${s.caps.framework.version}（API ${s.caps.framework.api}），系统模块尚未运行，请重启手机。" +
                            if (s.caps.framework.api < 101) "该框架 API 版本过低。" else if (!s.caps.framework.remote) "该框架不支持远程配置。" else ""
                        else -> "尚未连接到 Xposed 框架。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.caps.hookActive) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("模拟期间系统会对所有应用伪装定位、Wi-Fi、基站和卫星（可在设置中关闭环境伪装）。部分设备的接口可能不同，实际效果需要逐项验证。系统层已覆盖所有应用，不需要也无法在普通应用里注入。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.size(24.dp))
        Button(onClick = onDone, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp)) {
            Text(if (onBack == null) "进入应用" else "完成")
        }
    }
}

@Composable
private fun Step(n: Int, title: String, subtitle: String) {
    Row(Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** granted=null means the status cannot be read (optional permission). */
@Composable
private fun Permission(icon: ImageVector, title: String, detail: String, granted: Boolean?, action: String = "授予", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Success, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("已授予", color = Success, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun Guide(n: Int, text: String, done: Boolean?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (done == true) Icons.Rounded.CheckCircle else if (done == false) Icons.Rounded.RadioButtonUnchecked else Icons.Rounded.Extension,
            null, tint = if (done == true) Success else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text("$n. $text" + when (done) { true -> "（已完成）"; false -> "（未完成）"; null -> "" },
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

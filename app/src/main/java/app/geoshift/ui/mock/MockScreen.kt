package app.geoshift.ui.mock

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.geoshift.data.PortalState
import app.geoshift.data.SpeedMode
import app.geoshift.ui.PortalViewModel
import app.geoshift.ui.components.ConfirmDialog
import app.geoshift.ui.components.IconBadge
import app.geoshift.ui.components.InputDialog
import app.geoshift.ui.components.PortalCard
import app.geoshift.ui.components.SectionTitle
import app.geoshift.ui.components.SettingGroup
import app.geoshift.ui.components.SettingRow
import app.geoshift.ui.components.formatCoord
import app.geoshift.ui.components.formatDistance
import app.geoshift.ui.components.formatTime
import app.geoshift.ui.theme.Danger
import app.geoshift.ui.theme.Indigo
import java.util.Locale

/** Problems that keep simulation from starting, each with a short fix description. */
fun readinessIssues(s: PortalState): List<String> = buildList {
    if (!s.settings.privacyAccepted) add("尚未同意使用须知")
    if (!s.caps.preciseLocation) add("未授予精确位置权限")
    if (!s.caps.locationEnabled) add("系统定位已关闭")
    if (s.settings.lsposed) {
        if (!s.caps.hookActive) add("未检测到LSPosed模块")
    } else if (!s.caps.mockSelected) add("未将本应用设为模拟位置信息应用")
}

@Composable
fun MockScreen(
    s: PortalState,
    vm: PortalViewModel,
    contentPadding: PaddingValues,
    onOpenSetup: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenCell: () -> Unit,
    onPickRoute: () -> Unit,
) {
    val r = s.runtime
    var gnssDialog by remember { mutableStateOf(false) }
    var captureName by remember { mutableStateOf<String?>(null) }
    var captureConfirm by remember { mutableStateOf<String?>(null) }
    var originalPosition by remember { mutableStateOf<app.geoshift.data.LatLon?>(null) }
    val issues = readinessIssues(s)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding() + 16.dp),
    ) {
        Text("模拟", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 12.dp, bottom = 12.dp))

        HeroCard(s, onToggle = {
            if (r.enabled) vm.run("模拟定位已关闭") { stop() }
            else vm.run("模拟定位已开启") { if (r.routeMode && r.route.size >= 2) startRoute() else startPoint() }
        })

        if (issues.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            PortalCard(Modifier.padding(horizontal = 16.dp), onClick = onOpenSetup) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.WarningAmber, tint = Color(0xFFE59A1A))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("还需完成 ${issues.size} 项设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(issues.joinToString("、"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onOpenSetup) { Text("去设置") }
                }
            }
        }

        SectionTitle("位置模拟")
        SettingGroup {
            SettingRow("一键清除模拟", "停止定位、摇杆、Wi-Fi、基站及 GNSS 模拟；系统可能需要时间刷新位置", icon = Icons.Rounded.LocationOff,
                onClick = { originalPosition = null; vm.run("模拟已停止，请等待系统重新定位") { clearSimulation() } })
            HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow("原始位置", originalPosition?.let { "新 GPS 位置：${formatCoord(it.lat, it.lon)} · 点击重新获取" }
                ?: "停止模拟后点击获取新的 GPS 位置，需等待卫星信号", icon = Icons.Rounded.Radar,
                onClick = { vm.run<app.geoshift.data.LatLon>(onDone = { originalPosition = it }) { deviceLocation() } })
            HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow("单点模拟", if (r.enabled && !r.routeMode) "正在模拟 ${formatCoord(r.position.lat, r.position.lon)}" else "固定在当前虚拟位置",
                icon = Icons.Rounded.LocationOn, checked = r.enabled && !r.routeMode,
                onCheckedChange = { on -> if (on) vm.run("模拟定位已开启") { startPoint() } else vm.run("模拟定位已关闭") { stop() } })
            HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow("摇杆悬浮窗", "显示悬浮窗，拖动圆盘移动虚拟位置", icon = Icons.Rounded.SportsEsports, checked = r.overlay,
                onCheckedChange = { on -> vm.run(if (on) "摇杆服务已启动" else "摇杆悬浮窗已关闭") { overlay(on) } })
        }

        SectionTitle("路线模拟")
        RouteCard(s, vm, onPickRoute)

        if (s.settings.lsposed) {
            SectionTitle("系统定位诊断")
            SettingGroup {
                SettingRow("检查系统定位交付", "勾选系统框架和电话服务，重启后开启模拟。观察定位推送、应用回调替换和 Wi-Fi / 基站报告。", onClick = onOpenSetup)
                listOf("location_system" to "系统定位分发", "location_pump" to "持续位置推送", "environment_system" to "应用回调与卫星/基站",
                    "wifi_privacy" to "Wi-Fi 伪装", "cell" to "电话服务基站").forEach { (key, title) ->
                    val report = s.featureStatus[key]
                    val status = when {
                        key == "wifi_privacy" && !s.settings.spoofEnvironment && !s.runtime.wifiEnabled -> "未启用"
                        report == null -> "暂无报告"
                        System.currentTimeMillis() - report.time > 60_000 -> "历史报告"
                        !report.ok -> "出现错误"
                        report.stage == "read" -> "已命中读取"
                        report.stage == "injected" -> "已执行注入"
                        report.stage == "waiting" -> if (key == "location_pump") "等待提供器" else "等待服务加载"
                        else -> "已安装，待验证"
                    }
                    val details = if (report == null) {
                        if (key == "wifi_privacy") "开始模拟后，等待应用读取 Wi-Fi 时报告。"
                        else if (key == "cell") "需勾选「电话服务」并重启；应用查询基站时报告。"
                        else if (key == "location_pump") "开始模拟后显示已发现的提供器及推送成功、失败次数。"
                        else "尚无系统报告；入口安装与目标应用实际接收需分别验证。"
                    } else
                        listOf(report.detail, report.process.ifEmpty { report.pkg }, formatTime(report.time, "HH:mm:ss"), report.error).filter { it.isNotEmpty() }.joinToString(" · ")
                    SettingRow(title, details, value = status)
                }
            }
        }

        SectionTitle("信号模拟")
        SettingGroup {
            SettingRow("WiFi 模拟", if (r.wifiEnabled) "已开启 · ${r.wifi.optString("ssid")}" else "已关闭", icon = Icons.Rounded.Wifi, onClick = onOpenWifi)
            HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow("基站模拟", if (r.cellEnabled) "已开启 · ${r.cellCount.coerceAtLeast(1)} 个小区" else "已关闭", icon = Icons.Rounded.CellTower, onClick = onOpenCell)
            HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow("GNSS 卫星状态", if (s.settings.gnssEnabled) "已开启 · ${s.settings.satelliteCount} 颗卫星" else "Android 11+，需要 LSPosed",
                icon = Icons.Rounded.SatelliteAlt, checked = s.settings.gnssEnabled,
                enabled = s.caps.gnssSimulation,
                onCheckedChange = { on -> vm.run(if (on) "GNSS 模拟已开启" else "GNSS 模拟已关闭") { setGnss(on, s.settings.satelliteCount) } })
            if (s.settings.gnssEnabled) {
                SettingRow("卫星数量", "4–32 颗", value = "${s.settings.satelliteCount}", onClick = { gnssDialog = true })
            }
            s.featureStatus["gnss"]?.let { FeatureLine("GNSS", it.ok, it.time, it.pkg, it.error) }
        }

        SectionTitle("位置环境")
        SettingGroup {
            SettingRow("记录当前位置环境", "保存当前虚拟坐标与手机此刻扫描到的基站 / Wi-Fi", icon = Icons.Rounded.Radar,
                onClick = { captureName = "位置环境 " + formatTime(System.currentTimeMillis(), "MM-dd HH:mm") })
        }
    }

    if (gnssDialog) {
        InputDialog("卫星数量", initial = s.settings.satelliteCount.toString(), number = true, hint = "范围 4–32，前 12 颗参与定位解算",
            onDismiss = { gnssDialog = false }) { v ->
            val n = v.toIntOrNull()
            if (n == null) vm.toast("请输入整数") else vm.run("设置已应用") { setGnss(true, n) }
        }
    }
    captureName?.let { initial ->
        InputDialog("为这次环境记录命名", initial = initial, label = "名称", onDismiss = { captureName = null }) { name ->
            captureConfirm = name
        }
    }
    captureConfirm?.let { name ->
        ConfirmDialog("记录位置环境",
            "将保存当前虚拟坐标与手机此刻实际扫描到的基站/Wi-Fi。扫描始终来自手机所在的真实位置；若虚拟坐标不是你当前所在地，记录会有偏差。继续？",
            onDismiss = { captureConfirm = null }) {
            val p = r.position
            vm.toast("扫描基站信息中...")
            vm.run<String>("环境记录已保存，可在「历史 · 环境记录」查看") { captureEnvironment(name, p) }
        }
    }
}

@Composable
private fun HeroCard(s: PortalState, onToggle: () -> Unit) {
    val r = s.runtime
    val on = r.enabled
    val gradient = if (on) Brush.linearGradient(listOf(Indigo, Color(0xFF7B5CF0)))
    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainerHigh))
    val fg = if (on) Color.White else MaterialTheme.colorScheme.onSurface
    val sub = if (on) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(gradient).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (on) Icons.Rounded.LocationOn else Icons.Rounded.LocationOff, null, tint = fg, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        on && r.paused -> "路线模拟已暂停"
                        on && r.routeMode -> "路线模拟中"
                        on -> "模拟定位已开启"
                        else -> "模拟定位已关闭"
                    },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = fg,
                )
                Text(if (s.settings.lsposed) "模拟模式 · LSPosed" else "模拟模式 · 标准 Android 模拟位置", style = MaterialTheme.typography.bodySmall, color = sub)
            }
        }
        Spacer(Modifier.size(16.dp))
        Text("虚拟位置", style = MaterialTheme.typography.labelMedium, color = sub)
        Text(formatCoord(r.position.lat, r.position.lon), style = MaterialTheme.typography.titleMedium, color = fg, fontWeight = FontWeight.SemiBold)
        if (on) {
            Text(String.format(Locale.ROOT, "速度 %.1f m/s · 方向 %.0f°", r.speed, r.bearing), style = MaterialTheme.typography.bodySmall, color = sub)
        }
        if (r.error.isNotEmpty() && !on) {
            Spacer(Modifier.size(6.dp))
            Text(r.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        s.featureStatus["location"]?.let {
            Spacer(Modifier.size(6.dp))
            Text((if (it.ok) "目标已读取 · " else "目标报告错误 · ") + it.pkg + " · " + formatTime(it.time, "HH:mm:ss"),
                style = MaterialTheme.typography.bodySmall, color = sub)
        }
        Spacer(Modifier.size(16.dp))
        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            colors = if (on) ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Danger) else ButtonDefaults.buttonColors(),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Icon(if (on) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text(if (on) "停止模拟" else "开始模拟", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun RouteCard(s: PortalState, vm: PortalViewModel, onPickRoute: () -> Unit) {
    val r = s.runtime
    val hasRoute = r.route.size >= 2
    val routeRunning = r.enabled && r.routeMode
    PortalCard(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Route)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (hasRoute) r.routeName.ifEmpty { "未命名路线" } else "未选择路线", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (hasRoute) "${r.route.size} 个点 · ${formatDistance(app.geoshift.data.routeLength(r.route))}" else "从历史路线中选择，或在地图上绘制",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onPickRoute) { Text(if (hasRoute) "更换" else "选择") }
        }
        if (hasRoute) {
            Spacer(Modifier.size(12.dp))
            LinearProgressIndicator(progress = { r.progress.toFloat() }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
            Spacer(Modifier.size(4.dp))
            Text("进度 ${(r.progress * 100).toInt()}% · 当前路线点索引 ${r.routeIndex + 1}/${r.route.size}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(12.dp))
        Text("移动模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedMode.entries.forEach { mode ->
                FilterChip(
                    selected = s.settings.speedMode == mode,
                    onClick = { vm.run { saveSettings(org.json.JSONObject().put("speedMode", mode.key)) } },
                    label = { Text(mode.label) },
                )
            }
        }
        Text(String.format(Locale.ROOT, "约 %.1f m/s（倍率 ×%s）", s.settings.speedMode.metresPerSecond * s.settings.speedMultiplier,
            app.geoshift.ui.components.formatNumber(s.settings.speedMultiplier)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!routeRunning) {
                Button(onClick = { vm.run("路线模拟开始") { startRoute() } }, enabled = hasRoute, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("开始")
                }
            } else {
                Button(onClick = { vm.run(if (r.paused) "路线模拟继续" else "路线模拟已暂停") { pauseRoute(!r.paused) } }, modifier = Modifier.weight(1f)) {
                    Icon(if (r.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text(if (r.paused) "继续" else "暂停")
                }
                OutlinedButton(onClick = { vm.run("路线模拟已停止") { stop() } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("停止")
                }
            }
        }
    }
}

@Composable
fun FeatureLine(name: String, ok: Boolean, time: Long, pkg: String, error: String) {
    Text(
        (if (ok) "✓ 目标已读取$name" else "✕ 目标报告错误") + " · $pkg · ${formatTime(time, "HH:mm:ss")}" + if (error.isNotEmpty()) " · $error" else "",
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

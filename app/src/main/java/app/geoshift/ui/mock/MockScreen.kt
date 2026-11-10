package app.geoshift.ui.mock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.geoshift.data.LatLon
import app.geoshift.data.PortalState
import app.geoshift.data.SpeedMode
import app.geoshift.ui.PortalViewModel
import app.geoshift.ui.components.ConfirmDialog
import app.geoshift.ui.components.CoordinateRow
import app.geoshift.ui.components.InputDialog
import app.geoshift.ui.components.PageHeader
import app.geoshift.ui.components.PortalCard
import app.geoshift.ui.components.RowDivider
import app.geoshift.ui.components.SectionTitle
import app.geoshift.ui.components.SettingGroup
import app.geoshift.ui.components.SettingRow
import app.geoshift.ui.components.ToggleTile
import app.geoshift.ui.components.formatCoord
import app.geoshift.ui.components.formatDistance
import app.geoshift.ui.components.formatNumber
import app.geoshift.ui.components.formatTime
import app.geoshift.ui.theme.Danger
import app.geoshift.ui.theme.LiveEnd
import app.geoshift.ui.theme.LiveStart
import app.geoshift.ui.theme.Warning
import kotlinx.coroutines.delay
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

/**
 * The console: what is being simulated right now, one tile per signal, the route player and the
 * tools that are not tied to a map point.
 */
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
    var originalPosition by remember { mutableStateOf<LatLon?>(null) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var currentAddress by remember { mutableStateOf("") }
    val issues = readinessIssues(s)

    LaunchedEffect(r.position, s.settings.privacyAccepted) {
        if (s.settings.privacyAccepted && (!r.enabled || !r.routeMode)) {
            delay(500)
            vm.run<String>(onDone = { currentAddress = it }) { reverseGeocode(r.position) }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding() + 20.dp),
    ) {
        PageHeader("控制台", if (s.settings.lsposed) "LSPosed 系统级模拟" else "标准 Android 模拟位置")

        StatusHero(s, currentAddress,
            onToggle = {
                if (r.enabled) vm.run("模拟定位已关闭") { stop() }
                else vm.run("模拟定位已开启") { if (r.routeMode && r.route.size >= 2) startRoute() else startPoint() }
            },
            onPause = { vm.run(if (r.routeFinished) "路线重新开始" else if (r.paused) "路线模拟继续" else "路线模拟已暂停") { pauseRoute(!r.paused) } },
            onCopyCoord = { vm.toast("虚拟坐标已复制到剪贴板") },
        )

        if (issues.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Warning.copy(alpha = 0.12f)).clickable(onClick = onOpenSetup).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.WarningAmber, null, tint = Warning)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("还需完成 ${issues.size} 项设置", style = MaterialTheme.typography.titleSmall)
                    Text(issues.joinToString("、"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionTitle("模拟项目")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleTile(Icons.Rounded.LocationOn, "单点定位",
                    if (r.enabled && !r.routeMode) "固定在虚拟位置" else "点击开启", r.enabled && !r.routeMode, Modifier.weight(1f)) {
                    if (r.enabled && !r.routeMode) vm.run("模拟定位已关闭") { stop() } else vm.run("模拟定位已开启") { startPoint() }
                }
                ToggleTile(Icons.Rounded.SportsEsports, "摇杆", if (r.overlay) "悬浮窗已显示" else "拖动圆盘移动", r.overlay, Modifier.weight(1f)) {
                    vm.run(if (r.overlay) "摇杆悬浮窗已关闭" else "摇杆服务已启动") { overlay(!r.overlay) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleTile(Icons.Rounded.Wifi, "Wi-Fi", if (r.wifiEnabled) r.wifi.optString("ssid").ifEmpty { "已开启" } else "未设置",
                    r.wifiEnabled, Modifier.weight(1f), hasSubpage = true, onClick = onOpenWifi)
                ToggleTile(Icons.Rounded.CellTower, "基站", if (r.cellEnabled) "${r.cellCount.coerceAtLeast(1)} 个小区" else "未设置",
                    r.cellEnabled, Modifier.weight(1f), hasSubpage = true, onClick = onOpenCell)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleTile(Icons.Rounded.SatelliteAlt, "卫星",
                    when {
                        !s.caps.gnssSimulation -> "需 Android 11+ 与 LSPosed"
                        s.settings.gnssEnabled -> "${s.settings.satelliteCount} 颗卫星"
                        else -> "点击开启"
                    },
                    s.settings.gnssEnabled, Modifier.weight(1f), enabled = s.caps.gnssSimulation) {
                    val on = !s.settings.gnssEnabled
                    vm.run(if (on) "卫星模拟已开启" else "卫星模拟已关闭") { setGnss(on, s.settings.satelliteCount) }
                }
                ToggleTile(Icons.Rounded.Radar, "记录环境", "保存基站和 Wi-Fi",
                    false, Modifier.weight(1f)) {
                    captureName = "位置环境 " + formatTime(System.currentTimeMillis(), "MM-dd HH:mm")
                }
            }
        }

        SectionTitle("路线")
        RouteCard(s, vm, onPickRoute)

        SectionTitle("工具")
        SettingGroup {
            if (s.settings.gnssEnabled) {
                SettingRow("卫星数量", "4–32 颗，前 12 颗参与定位", icon = Icons.Rounded.Numbers,
                    value = "${s.settings.satelliteCount}", onClick = { gnssDialog = true })
                RowDivider()
            }
            SettingRow("获取真实位置", originalPosition?.let { "真实 GPS：${formatCoord(it.lat, it.lon)}" }
                ?: "停止模拟后读取一次真实 GPS，需要等待卫星信号", icon = Icons.Rounded.MyLocation,
                onClick = { vm.run<LatLon>(onDone = { originalPosition = it }) { deviceLocation() } })
            RowDivider()
            SettingRow("一键清除模拟", "停止定位、摇杆、Wi-Fi、基站和卫星模拟", icon = Icons.Rounded.LocationOff,
                onClick = { originalPosition = null; vm.run("模拟已停止，请等待系统重新定位") { clearSimulation() } })
        }

        if (s.settings.lsposed) {
            SectionTitle("系统诊断")
            // Sensors are delivered inside each app's own process, so Geoshift (system-side, no app
            // injection) cannot forge them. Warn when the simulated speed is clearly non-walking: a
            // stationary phone's accelerometer/step count then contradicts the moving location.
            if (s.runtime.enabled && s.runtime.speed >= 2.3) {
                Spacer(Modifier.size(12.dp))
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Warning.copy(alpha = 0.12f)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = Warning)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("速度与传感器可能不一致", style = MaterialTheme.typography.titleSmall)
                        Text("当前模拟约 ${"%.0f".format(s.runtime.speed * 3.6)} km/h。若手机此刻静止，运动 / 考勤类应用可能用加速度计或计步核对出与定位不符——本工具不伪造传感器。改用静止模拟或让手机实际移动可避免。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Diagnostics(s, diagnostics, onToggle = { diagnostics = !diagnostics }, onOpenSetup = onOpenSetup,
                onCheckIp = { vm.run<String>(onDone = vm::toast) { checkExitIp() } })
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
            vm.run<String>("环境记录已保存，可在「收藏 · 环境」查看") { captureEnvironment(name, p) }
        }
    }
}

/** Big status panel: green gradient while simulating, a quiet card otherwise. */
@Composable
private fun StatusHero(s: PortalState, address: String, onToggle: () -> Unit, onPause: () -> Unit, onCopyCoord: () -> Unit) {
    val r = s.runtime
    val on = r.enabled
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(24.dp)
    val background = if (on) Brush.linearGradient(listOf(LiveStart, LiveEnd))
    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainerHigh))
    val fg = if (on) Color.White else MaterialTheme.colorScheme.onSurface
    val sub = if (on) Color.White.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(background).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) Color.White else MaterialTheme.colorScheme.outline))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    on && r.routeFinished -> "路线已走完"
                    on && r.paused -> "路线已暂停"
                    on && r.routeMode -> "路线模拟中"
                    on -> "正在模拟位置"
                    r.error.isNotEmpty() -> "模拟启动失败"
                    else -> "未在模拟"
                },
                style = MaterialTheme.typography.titleMedium, color = fg,
            )
        }
        Spacer(Modifier.size(14.dp))
        Text("虚拟位置", style = MaterialTheme.typography.labelMedium, color = sub)
        CoordinateRow(
            lat = r.position.lat,
            lon = r.position.lon,
            style = MaterialTheme.typography.titleLarge,
            color = fg,
            onCopied = onCopyCoord,
        )
        if (address.isNotEmpty() && (!on || !r.routeMode)) {
            Spacer(Modifier.size(2.dp))
            Text(address, style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (on && (r.routeMode || r.speed > 0.05)) {
            val norm = ((r.bearing % 360 + 360) % 360)
            val dir = when ((norm / 45.0 + 0.5).toInt() % 8) {
                0 -> "正北"; 1 -> "东北"; 2 -> "正东"; 3 -> "东南"; 4 -> "正南"; 5 -> "西南"; 6 -> "正西"; 7 -> "西北"; else -> "北"
            }
            Spacer(Modifier.size(2.dp))
            Text(String.format(Locale.ROOT, "%.1f km/h · 方向 %.0f°（%s）", r.speed * 3.6, r.bearing, dir),
                style = MaterialTheme.typography.bodySmall, color = sub)
        }
        if (on && r.routeMode && r.route.size >= 2) {
            val totalLength = app.geoshift.data.routeLength(r.route)
            val remainingMetres = (totalLength * (1.0 - r.progress)).coerceAtLeast(0.0)
            val currentSpeed = if (r.speed > 0.1) r.speed else (s.settings.speedMode.metresPerSecond * s.settings.speedMultiplier)
            val remainingSec = if (currentSpeed > 0.05) (remainingMetres / currentSpeed).toLong() else 0L
            val etaStr = if (remainingSec > 3600) "${remainingSec / 3600}小时${(remainingSec % 3600) / 60}分"
                else "${(remainingSec / 60).coerceAtLeast(1)}分钟"

            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.routeName.ifEmpty { "未命名路线" }, style = MaterialTheme.typography.bodySmall, color = sub, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when (r.routeRepeat) {
                    "loop" -> "循环中 · ${(r.progress * 100).toInt()}%"
                    "bounce" -> "往返中 · ${(r.progress * 100).toInt()}%"
                    else -> if (r.routeFinished) "已到达终点"
                        else "${(r.progress * 100).toInt()}% · 剩 ${formatDistance(remainingMetres)}（约 $etaStr）"
                }, style = MaterialTheme.typography.bodySmall, color = fg)
            }
            Spacer(Modifier.size(6.dp))
            LinearProgressIndicator(progress = { r.progress.toFloat() }, modifier = Modifier.fillMaxWidth().clip(CircleShape),
                color = Color.White, trackColor = Color.White.copy(alpha = 0.3f), drawStopIndicator = {})
        }
        if (r.error.isNotEmpty() && !on) {
            Spacer(Modifier.size(6.dp))
            Text(r.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.size(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (on && r.routeMode) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPause()
                    },
                    modifier = Modifier.weight(1f), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.22f), contentColor = Color.White),
                    contentPadding = PaddingValues(vertical = 13.dp),
                ) {
                    Icon(when { r.routeFinished -> Icons.Rounded.Replay; r.paused -> Icons.Rounded.PlayArrow; else -> Icons.Rounded.Pause }, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp)); Text(when { r.routeFinished -> "重新开始"; r.paused -> "继续"; else -> "暂停" })
                }
            }
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
                modifier = Modifier.weight(1f), shape = CircleShape,
                colors = if (on) ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Danger) else ButtonDefaults.buttonColors(),
                contentPadding = PaddingValues(vertical = 13.dp),
            ) {
                Icon(if (on) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (on) "停止模拟" else "开始模拟")
            }
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
            Icon(Icons.Rounded.Route, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (hasRoute) r.routeName.ifEmpty { "未命名路线" } else "未选择路线", style = MaterialTheme.typography.titleSmall)
                Text(if (hasRoute) "${r.route.size} 个点 · ${formatDistance(app.geoshift.data.routeLength(r.route))}" else "在收藏中选择，或在地图上绘制",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onPickRoute) { Text(if (hasRoute) "更换" else "选择") }
        }
        Spacer(Modifier.size(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedMode.entries.forEach { mode ->
                FilterChip(
                    selected = s.settings.speedMode == mode,
                    onClick = { vm.run { saveSettings(org.json.JSONObject().put("speedMode", mode.key)) } },
                    label = { Text(mode.label) },
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Text("走到终点后", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val repeats = listOf("once" to "停在终点", "loop" to "循环", "bounce" to "往返")
            repeats.forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    selected = r.routeRepeat == key,
                    onClick = { if (r.routeRepeat != key) vm.run { setRouteRepeat(key) } },
                    shape = SegmentedButtonDefaults.itemShape(i, repeats.size),
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(String.format(Locale.ROOT, "约 %.1f km/h（倍率 ×%s），速度自然波动 ±20%%，起步、转弯和到站时减速",
            s.settings.speedMode.metresPerSecond * s.settings.speedMultiplier * 3.6, formatNumber(s.settings.speedMultiplier)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!routeRunning) {
            Spacer(Modifier.size(12.dp))
            Button(onClick = { vm.run("路线模拟开始") { startRoute() } }, enabled = hasRoute, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("沿路线出发")
            }
        }
    }
}

private val DIAGNOSTICS = listOf(
    "location_system" to "系统定位分发", "location_pump" to "持续位置推送", "environment_system" to "应用回调与卫星/基站",
    "wifi_privacy" to "Wi-Fi 伪装", "cell" to "电话服务基站", "bluetooth" to "蓝牙扫描",
)

/** One status line per system entry; folded to a summary by default. */
@Composable
private fun Diagnostics(s: PortalState, expanded: Boolean, onToggle: () -> Unit, onOpenSetup: () -> Unit, onCheckIp: () -> Unit) {
    val rows = DIAGNOSTICS.map { (key, title) ->
        val report = s.featureStatus[key]
        val status = when {
            key == "wifi_privacy" && !s.settings.spoofEnvironment && !s.runtime.wifiEnabled -> "未启用"
            key == "bluetooth" && !s.settings.spoofEnvironment -> "未启用"
            report == null -> "暂无报告"
            report.ok && report.stage == "installed" -> "已安装"
            System.currentTimeMillis() - report.time > 60_000 -> "历史报告"
            !report.ok -> "出现错误"
            report.stage == "read" -> "已命中"
            report.stage == "injected" -> "已注入"
            report.stage == "waiting" -> "等待中"
            else -> "已安装"
        }
        val details = if (report == null) when (key) {
            "wifi_privacy" -> "开始模拟后，等待应用读取 Wi-Fi 时报告。"
            "cell" -> "需勾选「电话服务」并重启；应用查询基站时报告。"
            "bluetooth" -> "需勾选「蓝牙」并重启；应用扫描蓝牙时报告。"
            "location_pump" -> "开始模拟后显示已发现的提供器及推送成功、失败次数。"
            else -> "尚无系统报告；入口安装与目标应用实际接收需分别验证。"
        } else listOf(report.detail, report.process.ifEmpty { report.pkg }, formatTime(report.time, "HH:mm:ss"), report.error)
            .filter { it.isNotEmpty() }.joinToString(" · ")
        Triple(title, status, details)
    }
    val errors = rows.count { it.second == "出现错误" }
    val working = rows.count { it.second in setOf("已命中", "已注入", "已安装") }
    SettingGroup {
        SettingRow("系统定位诊断",
            if (errors > 0) "$errors 项出现错误" else "$working / ${rows.size} 项已安装或命中",
            icon = Icons.Rounded.MonitorHeart, value = if (expanded) "收起" else "展开", onClick = onToggle)
        AnimatedVisibility(expanded) {
            Column {
                rows.forEach { (title, status, details) ->
                    RowDivider()
                    Row(Modifier.fillMaxWidth().padding(start = 54.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyMedium)
                            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(status, style = MaterialTheme.typography.labelMedium,
                            color = when (status) {
                                "出现错误" -> MaterialTheme.colorScheme.error
                                "已命中", "已注入" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            })
                    }
                }
                RowDivider()
                SettingRow("出口 IP 校验", "手动检查公网 IP 定位是否与模拟点一致（IP 无法在模块内伪装，差异大时需代理/VPN）",
                    value = "检查", onClick = onCheckIp)
                RowDivider()
                SettingRow("检查权限与模块", "作用域勾选系统框架、电话服务和蓝牙后重启", onClick = onOpenSetup)
            }
        }
    }
}

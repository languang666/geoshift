package app.geoshift.ui.settings

import androidx.compose.material.icons.rounded.Place

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.geoshift.MapSource
import app.geoshift.data.PortalState
import app.geoshift.data.SpeedMode
import app.geoshift.ui.PortalViewModel
import app.geoshift.ui.components.ConfirmDialog
import app.geoshift.ui.components.InputDialog
import app.geoshift.ui.components.SectionTitle
import app.geoshift.ui.components.SettingGroup
import app.geoshift.ui.components.SettingRow
import app.geoshift.ui.components.formatNumber
import app.geoshift.ui.components.formatTime
import org.json.JSONObject

private data class NumberSetting(val key: String, val title: String, val unit: String, val hint: String)

private val NUMBER_SETTINGS = mapOf(
    "interval" to NumberSetting("interval", "上报时间间隔", "毫秒",
        "提示：过大的上报间隔将可能导致位置更新错误，过小的间隔可能导致系统缓冲区溢出而死机。建议值 1000。"),
    "speedMultiplier" to NumberSetting("speedMultiplier", "速度倍率", "×",
        "路线与摇杆移动速度 = 移动模式基础速度 × 倍率。范围 0.01–1000。"),
    "altitude" to NumberSetting("altitude", "模拟海拔高度", "米",
        "提示：中国大部分平原地区海拔在 0–500 米之间。"),
    "accuracy" to NumberSetting("accuracy", "位置精度", "米",
        "提示：精度值越小表示定位越精确，建议值 5–20 米。"),
)

@Composable
fun SettingsScreen(
    s: PortalState,
    vm: PortalViewModel,
    contentPadding: PaddingValues,
    onOpenSetup: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val st = s.settings
    var number by remember { mutableStateOf<NumberSetting?>(null) }
    var choice by remember { mutableStateOf<String?>(null) }
    var mapKey by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun save(key: String, value: Any, message: String? = "设置已应用") =
        vm.run(message) { saveSettings(JSONObject().put(key, value)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 12.dp, bottom = 4.dp))

        SectionTitle("模拟模式")
        SettingGroup {
            SettingRow("模拟类型", if (st.lsposed) "LSPosed 系统级模拟（无需选择模拟位置应用）" else "标准 Android 模拟位置",
                icon = Icons.Rounded.Tune, value = if (st.lsposed) "LSPosed" else "标准", onClick = { choice = "backend" })
            Divider()
            SettingRow("权限与模块检查", if (s.caps.systemHook) "系统入口已安装，目标应用效果需看实际命中" else if (s.caps.hookActive) "LSPosed 模块已连接" else if (st.lsposed) "未检测到LSPosed" else "查看授权与模拟位置应用状态",
                icon = Icons.Rounded.VerifiedUser, onClick = onOpenSetup)
        }

        SectionTitle("位置模拟设置")
        SettingGroup {
            SettingRow("上报时间间隔", "设置位置上报的时间间隔", icon = Icons.Rounded.Timer, value = "${st.interval} 毫秒",
                onClick = { number = NUMBER_SETTINGS["interval"] })
            Divider()
            SettingRow("移动模式", "路线与摇杆的基础速度", icon = Icons.AutoMirrored.Rounded.DirectionsWalk, value = st.speedMode.label, onClick = { choice = "speedMode" })
            Divider()
            SettingRow("速度倍率", "设置摇杆与路线移动速度倍率", icon = Icons.Rounded.Speed, value = "×${formatNumber(st.speedMultiplier)}",
                onClick = { number = NUMBER_SETTINGS["speedMultiplier"] })
            Divider()
            SettingRow("模拟海拔高度", "设置模拟位置的海拔高度（米）", icon = Icons.Rounded.Terrain, value = "${formatNumber(st.altitude)} 米",
                onClick = { number = NUMBER_SETTINGS["altitude"] })
            Divider()
            SettingRow("位置精度", "设置模拟位置的精度值（米）", icon = Icons.Rounded.GpsFixed, value = "${formatNumber(st.accuracy)} 米",
                onClick = { number = NUMBER_SETTINGS["accuracy"] })
        }
        Spacer12()
        SettingGroup {
            SettingRow("伪装 Wi-Fi、基站、卫星和蓝牙", "LSPosed 模式：模拟期间应用只能看到预设 Wi-Fi / 基站（未设置则为空）、模拟卫星，且扫描不到蓝牙设备（已连接的设备不受影响），防止定位 SDK 用真实信号反查位置",
                icon = Icons.Rounded.PrivacyTip, checked = st.spoofEnvironment, enabled = st.lsposed,
                onCheckedChange = { on ->
                    vm.run(if (on) "已开启环境伪装，模拟时生效" else "已关闭环境伪装，应用可能用真实 Wi-Fi / 基站 / 蓝牙算出真实位置") {
                        saveSettings(JSONObject().put("spoofEnvironment", on).put("protectWifi", false))
                    }
                })
            Divider()
            SettingRow("位置随机抖动", "启用后位置信息将添加随机微小偏移", icon = Icons.Rounded.Shuffle, checked = st.jitter,
                onCheckedChange = { save("jitter", it, if (it) "位置随机抖动已开启" else "位置随机抖动已关闭") })
            Divider()
            SettingRow("定位增强服务", "启用后将按照上报间隔循环广播位置", icon = Icons.Rounded.Autorenew, checked = st.enhanced,
                onCheckedChange = { save("enhanced", it, if (it) "位置增强服务已开启" else "位置增强服务已关闭") })
            Divider()
            SettingRow("二次上报", "启用摇杆数据的二次确认上报", icon = Icons.Rounded.Repeat, checked = st.secondReport,
                onCheckedChange = { save("secondReport", it, if (it) "摇杆二次上报已开启" else "摇杆二次上报已关闭") })
            Divider()
            SettingRow("调试模式", "开启后将显示更多调试信息", icon = Icons.Rounded.BugReport, checked = st.debug,
                onCheckedChange = { save("debug", it) })
        }

        SectionTitle("地图与外观")
        SettingGroup {
            SettingRow("地图图层", "无需任何 Key，可随时切换，也可使用地图右侧图层按钮", icon = Icons.Rounded.Place,
                value = st.mapSource.label, onClick = { choice = "mapSource" })
            Divider()
            SettingRow("高德 Web 服务 Key（可选）", "不填也能搜索、显示地址和规划道路；填写后改用高德数据，国内小店和新地点更全", icon = Icons.Rounded.Key,
                value = if (st.mapKeyConfigured) "已设置" else "未设置", onClick = { mapKey = true })
            Divider()
            SettingRow("外观", null, icon = Icons.Rounded.Palette,
                value = when (st.theme) { "light" -> "明亮"; "dark" -> "深色"; else -> "跟随系统" }, onClick = { choice = "theme" })
        }

        SectionTitle("数据管理")
        SettingGroup {
            SettingRow("导出备份", "位置、路线、预设和环境记录（不含 Key）", icon = Icons.Rounded.Upload, onClick = onExport)
            Divider()
            SettingRow("导入备份", "同 ID 记录会被更新", icon = Icons.Rounded.Download, onClick = onImport)
            Divider()
            SettingRow("清除诊断日志", "删除临时数据，释放存储空间", icon = Icons.Rounded.CleaningServices, onClick = { confirm = "logs" })
            Divider()
            SettingRow("清除全部数据", "停止模拟并删除所有记录与预设", icon = Icons.Rounded.DeleteForever, onClick = { confirm = "all" })
        }

        SectionTitle("诊断日志")
        SettingGroup {
            if (s.logs.isEmpty()) SettingRow("暂无日志", "错误会在这里明确记录")
            s.logs.take(if (st.debug) 30 else 6).forEach {
                SettingRow(it.message, formatTime(it.time, "MM-dd HH:mm:ss"))
            }
        }

        SectionTitle("关于")
        SettingGroup {
            SettingRow("Geoshift", "版本 ${s.version} · 包名 app.geoshift", icon = Icons.Rounded.Info)
            Divider()
            SettingRow("使用帮助", null, icon = Icons.AutoMirrored.Rounded.HelpOutline, onClick = { text = "使用帮助" to HELP })
            Divider()
            SettingRow("隐私政策", null, icon = Icons.Rounded.PrivacyTip, onClick = { text = "隐私政策" to PRIVACY })
            Divider()
            SettingRow("开源许可", null, icon = Icons.AutoMirrored.Rounded.Article, onClick = { text = "开源许可" to LICENSES })
        }
    }

    number?.let { n ->
        val current = when (n.key) {
            "interval" -> st.interval.toString()
            "speedMultiplier" -> formatNumber(st.speedMultiplier)
            "altitude" -> formatNumber(st.altitude)
            else -> formatNumber(st.accuracy)
        }
        InputDialog(n.title, initial = current, label = n.unit, hint = n.hint, number = true, onDismiss = { number = null }) { v ->
            val d = v.toDoubleOrNull()
            if (d == null) vm.toast("格式不正确，请输入数字") else save(n.key, if (n.key == "interval") d.toInt() else d,
                when (n.key) {
                    "interval" -> "已设置上报间隔为 ${d.toInt()} 毫秒"
                    "speedMultiplier" -> "已设置速度倍率为 ${formatNumber(d)}"
                    "altitude" -> "已设置模拟海拔为 ${formatNumber(d)} 米"
                    else -> "已设置位置精度为 ${formatNumber(d)} 米"
                })
        }
    }
    when (choice) {
        "mapSource" -> ChoiceDialog("地图图层", MapSource.entries.map { it.key to it.menuLabel }, st.mapSource.key,
            onDismiss = { choice = null }) { save("mapSource", it, "地图已切换") }
        "backend" -> ChoiceDialog("模拟类型", listOf("android_mock" to "标准 Android 模拟位置", "lsposed" to "LSPosed 系统定位"), st.backend,
            onDismiss = { choice = null }) { save("backend", it, "已切换模拟类型") }
        "speedMode" -> ChoiceDialog("移动模式", SpeedMode.entries.map { it.key to "${it.label} · ${it.metresPerSecond} m/s" }, st.speedMode.key,
            onDismiss = { choice = null }) { save("speedMode", it) }
        "theme" -> ChoiceDialog("外观", listOf("system" to "跟随系统", "light" to "明亮", "dark" to "深色"), st.theme,
            onDismiss = { choice = null }) { save("theme", it, null) }
    }
    if (mapKey) {
        MapKeyDialog(configured = st.mapKeyConfigured, onDismiss = { mapKey = false }, onMessage = vm::toast,
            onTest = { vm.run<String>(onDone = vm::toast) { checkMapKey() } },
            onSave = { key -> save("mapKey", key, if (key.isEmpty()) "已清除高德 Key，改用 OpenStreetMap 数据" else "高德 Key 已保存") })
    }
    when (confirm) {
        "logs" -> ConfirmDialog("清除诊断日志", "确定清空所有诊断日志吗？", confirm = "清除", onDismiss = { confirm = null }) {
            vm.run("日志已清除") { clearLogs() }
        }
        "all" -> ConfirmDialog("清除全部数据", "将停止模拟，并删除所有历史位置、路线、预设、环境记录和日志。此操作无法撤销。", confirm = "全部清除", danger = true,
            onDismiss = { confirm = null }) { vm.run("全部数据已清除") { clearAll() } }
    }
    text?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { text = null },
            title = { Text(title) },
            text = { SelectionContainer { Text(body, modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { text = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun Spacer12() = androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 12.dp))

@Composable
private fun ChoiceDialog(title: String, options: List<Pair<String, String>>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (key, label) ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().clickable { onPick(key); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = key == current, onClick = { onPick(key); onDismiss() })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

const val HELP = """1. 首次使用：同意使用须知，授予精确位置权限。

2. 标准模式：在系统「开发者选项 → 选择模拟位置信息应用」中选择 Geoshift，然后在地图选点并点击「开始模拟」。系统会保留模拟标记。

3. LSPosed 模式：启用 Geoshift，勾选「系统框架」「电话服务」和需要模拟的应用；如有「Android 系统」也请勾选。重启手机并开启模拟。系统路径处理 gps / network / fused 的上报与缓存，目标应用提供额外兜底。可在设置中开启 Wi-Fi 和基站信息过滤；停止模拟后恢复正常读取，无法清除已有缓存。不同设备接口可能不同，请通过系统定位诊断及目标应用实际结果验证。

4. 路线：在地图点击右侧「路线编辑」按钮，依次点击地图添加点位，保存后在「模拟」页开始路线模拟，可暂停、继续和调整移动模式。

5. 摇杆：在地图右侧或「模拟」页打开摇杆悬浮窗（需要悬浮窗权限），拖动圆盘移动虚拟位置，点右上角 × 关闭。

6. 环境记录：在「模拟」页记录当前环境，会保存手机此刻真实扫描到的基站与 Wi-Fi，之后可在「历史 · 环境记录」载入到 LSPosed 模拟。"""

const val PRIVACY = """我们重视您的隐私。

· 位置、路线、预设、环境记录、设置和日志全部保存在本机应用私有存储，不会上传。
· 地图按您的选择来自 OpenStreetMap、OpenFreeMap、高德地图或 Esri World Imagery，对应服务器可以看到您查看的地图区域。
· 地点搜索、地址显示和沿道路路线：填写高德 Web 服务 Key 时使用 restapi.amap.com（发送关键词或坐标以及您的 Key）；未填写时使用 photon.komoot.io（搜索）、nominatim.openstreetmap.org（地址）和 routing.openstreetmap.de（道路路线），发送搜索关键词或坐标。
· 不包含任何第三方 SDK、广告或统计服务。
· 未同意使用须知前不会加载在线地图，也不会启动模拟服务。"""

const val LICENSES = """osmdroid — Apache License 2.0
MapLibre Native — BSD 2-Clause License
矢量瓦片 © OpenFreeMap © OpenMapTiles
地图数据 © OpenStreetMap contributors — ODbL
AndroidX / Jetpack Compose — Apache License 2.0
Kotlin — Apache License 2.0
Xposed API 82（仅编译期使用，不打包）— Apache License 2.0

界面布局参考 Portal（fuqiuluo/Portal，Apache License 2.0）与 PortalDev 的使用流程，代码为本工程独立实现。"""

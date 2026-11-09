package app.geoshift.ui.settings

import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Build

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
import androidx.compose.material.icons.rounded.Apps
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
import app.geoshift.ui.components.PageHeader
import app.geoshift.ui.components.RowDivider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.saveable.rememberSaveable
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
    var appScope by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf<Pair<String, String>?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }

    fun save(key: String, value: Any, message: String? = "设置已应用") =
        vm.run(message) { saveSettings(JSONObject().put(key, value)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        PageHeader("设置")

        SectionTitle("模拟方式")
        SettingGroup {
            SettingRow("模拟类型", if (st.lsposed) "系统级，无需选择模拟位置应用" else "系统会保留模拟标记",
                icon = Icons.Rounded.Tune, value = if (st.lsposed) "LSPosed" else "标准", onClick = { choice = "backend" })
            RowDivider()
            SettingRow("模拟范围", if (st.lsposed) "其他应用保持真实位置" else "仅 LSPosed 模式可用",
                icon = Icons.Rounded.Apps, enabled = st.lsposed,
                value = if (st.appScope == "selected") "${st.allowedList.size} 个应用" else "所有应用",
                onClick = { if (st.lsposed) appScope = true })
            RowDivider()
            SettingRow("权限与模块", if (s.caps.systemHook) "系统入口已安装" else if (s.caps.hookActive) "模块已连接" else if (st.lsposed) "未检测到 LSPosed 模块" else "授权与模拟位置应用状态",
                icon = Icons.Rounded.VerifiedUser, onClick = onOpenSetup)
        }

        SectionTitle("定位参数")
        SettingGroup {
            SettingRow("移动模式", "路线与摇杆的基础速度", icon = Icons.AutoMirrored.Rounded.DirectionsWalk, value = st.speedMode.label, onClick = { choice = "speedMode" })
            RowDivider()
            SettingRow("速度倍率", null, icon = Icons.Rounded.Speed, value = "×${formatNumber(st.speedMultiplier)}",
                onClick = { number = NUMBER_SETTINGS["speedMultiplier"] })
            RowDivider()
            SettingRow("位置精度", null, icon = Icons.Rounded.GpsFixed, value = "${formatNumber(st.accuracy)} 米",
                onClick = { number = NUMBER_SETTINGS["accuracy"] })
            RowDivider()
            SettingRow("海拔高度", null, icon = Icons.Rounded.Terrain, value = "${formatNumber(st.altitude)} 米",
                onClick = { number = NUMBER_SETTINGS["altitude"] })
            RowDivider()
            SettingRow("随机抖动", "每秒额外偏移不超过 1 米", icon = Icons.Rounded.Shuffle, checked = st.jitter,
                onCheckedChange = { save("jitter", it, if (it) "位置随机抖动已开启" else "位置随机抖动已关闭") })
        }

        SectionTitle("隐私保护")
        SettingGroup {
            SettingRow("隐藏真实信号", "模拟时应用只看到预设的 Wi-Fi、基站和模拟卫星，扫描不到蓝牙设备，防止用真实信号反查位置",
                icon = Icons.Rounded.PrivacyTip, checked = st.spoofEnvironment, enabled = st.lsposed,
                onCheckedChange = { on ->
                    vm.run(if (on) "已开启环境伪装，模拟时生效" else "已关闭环境伪装，应用可能用真实 Wi-Fi / 基站 / 蓝牙算出真实位置") {
                        saveSettings(JSONObject().put("spoofEnvironment", on).put("protectWifi", false))
                    }
                })
        }

        SectionTitle("地图与外观")
        SettingGroup {
            SettingRow("地图图层", null, icon = Icons.Rounded.Layers, value = st.mapSource.label, onClick = { choice = "mapSource" })
            RowDivider()
            SettingRow("高德 Key", "可选，填写后搜索和路线用高德数据", icon = Icons.Rounded.Key,
                value = if (st.mapKeyConfigured) "已设置" else "未设置", onClick = { mapKey = true })
            RowDivider()
            SettingRow("外观", null, icon = Icons.Rounded.Palette,
                value = when (st.theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }, onClick = { choice = "theme" })
        }

        SectionTitle("高级")
        SettingGroup {
            SettingRow("高级选项", "上报间隔、增强服务、二次上报、调试", icon = Icons.Rounded.Build,
                value = if (advanced) "收起" else "展开", onClick = { advanced = !advanced })
            AnimatedVisibility(advanced) {
                Column {
                    RowDivider()
                    SettingRow("上报间隔", null, icon = Icons.Rounded.Timer, value = "${st.interval} 毫秒",
                        onClick = { number = NUMBER_SETTINGS["interval"] })
                    RowDivider()
                    SettingRow("定位增强服务", "按上报间隔循环广播位置", icon = Icons.Rounded.Autorenew, checked = st.enhanced,
                        onCheckedChange = { save("enhanced", it, if (it) "位置增强服务已开启" else "位置增强服务已关闭") })
                    RowDivider()
                    SettingRow("二次上报", "摇杆数据再确认一次", icon = Icons.Rounded.Repeat, checked = st.secondReport,
                        onCheckedChange = { save("secondReport", it, if (it) "摇杆二次上报已开启" else "摇杆二次上报已关闭") })
                    RowDivider()
                    SettingRow("调试模式", "显示更多日志", icon = Icons.Rounded.BugReport, checked = st.debug,
                        onCheckedChange = { save("debug", it) })
                }
            }
        }

        SectionTitle("数据")
        SettingGroup {
            SettingRow("导出备份", "位置、路线、预设和环境记录（不含 Key）", icon = Icons.Rounded.Upload, onClick = onExport)
            RowDivider()
            SettingRow("导入备份", "同 ID 记录会被更新", icon = Icons.Rounded.Download, onClick = onImport)
            RowDivider()
            SettingRow("清除诊断日志", null, icon = Icons.Rounded.CleaningServices, onClick = { confirm = "logs" })
            RowDivider()
            SettingRow("清除全部数据", "停止模拟并删除所有记录与预设", icon = Icons.Rounded.DeleteForever, onClick = { confirm = "all" })
        }

        SectionTitle("诊断日志")
        SettingGroup {
            if (s.logs.isEmpty()) SettingRow("暂无日志", "错误会在这里明确记录")
            s.logs.take(if (st.debug) 30 else 5).forEachIndexed { i, entry ->
                if (i > 0) RowDivider(icon = false)
                SettingRow(entry.message, formatTime(entry.time, "MM-dd HH:mm:ss"))
            }
        }

        SectionTitle("关于")
        SettingGroup {
            SettingRow("Geoshift", "版本 ${s.version}", icon = Icons.Rounded.Info)
            RowDivider()
            SettingRow("使用帮助", null, icon = Icons.AutoMirrored.Rounded.HelpOutline, onClick = { text = "使用帮助" to HELP })
            RowDivider()
            SettingRow("隐私政策", null, icon = Icons.Rounded.PrivacyTip, onClick = { text = "隐私政策" to PRIVACY })
            RowDivider()
            SettingRow("开源许可", null, icon = Icons.AutoMirrored.Rounded.Article, onClick = { text = "开源许可" to LICENSES })
        }
    }

    if (appScope) {
        AppScopeDialog(st.appScope, st.allowedList, onDismiss = { appScope = false }) { mode, packages ->
            appScope = false
            vm.run(if (mode == "selected") "仅对选中的 ${packages.size} 个应用模拟" else "已对所有应用模拟") {
                saveSettings(JSONObject().put("appScope", mode).put("allowedPackages", packages.joinToString(",")))
            }
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
        "theme" -> ChoiceDialog("外观", listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), st.theme,
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

2. 标准模式：在系统「开发者选项 → 选择模拟位置信息应用」中选择 Geoshift，然后在地图选点并点击开始。系统会保留模拟标记。

3. LSPosed 模式：在 LSPosed / Vector 中启用 Geoshift，作用域勾选「系统框架」「电话服务」和「蓝牙」，不要勾选普通应用，然后重启手机。系统层会对所有应用（或设置里「模拟范围」选中的应用）伪装定位、Wi-Fi、基站和卫星。可在「控制台 → 系统诊断」查看各入口状态。

4. 选点：在地图上点击或长按选点，也可以在搜索框输入地名或「纬度, 经度」。

5. 路线：点地图右侧路线按钮，依次点击地图添加途经点，可选择沿道路步行、骑行或驾车。保存后在「控制台」或「收藏 · 路线」出发；行进中可暂停、继续，Geoshift 在后台被冻结也会继续走。

6. 摇杆：在地图右侧或「控制台」打开摇杆悬浮窗（需要悬浮窗权限），拖动圆盘移动虚拟位置。

7. 环境记录：在「控制台」记录当前环境，会保存手机此刻真实扫描到的基站与 Wi-Fi，之后可在「收藏 · 环境」载入。"""

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

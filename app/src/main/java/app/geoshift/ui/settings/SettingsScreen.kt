package app.geoshift.ui.settings

import android.os.Build

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Build

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import app.geoshift.map.MapSource
import app.geoshift.ui.AppState
import app.geoshift.data.SpeedMode
import app.geoshift.ui.AppViewModel
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
import app.geoshift.data.Backend
import app.geoshift.data.Settings
import app.geoshift.data.ThemeMode

private data class NumberSetting(
    val key: String,
    val title: String,
    val unit: String,
    val hint: String,
    val min: Double,
    val max: Double,
    val presets: List<Double>,
    val defaultVal: Double,
    val isInteger: Boolean = false,
)

private val NUMBER_SETTINGS = mapOf(
    "interval" to NumberSetting(
        "interval", "上报时间间隔", "毫秒",
        "提示：过大的上报间隔将可能导致位置更新错误，过小的间隔可能导致系统缓冲区溢出而死机。建议值 1000。",
        min = 200.0, max = 5000.0, presets = listOf(500.0, 1000.0, 1500.0, 2000.0, 5000.0), defaultVal = 1000.0, isInteger = true,
    ),
    "speedMultiplier" to NumberSetting(
        "speedMultiplier", "速度倍率", "×",
        "路线与摇杆移动速度 = 移动模式基础速度 × 倍率。范围 0.01–1000。",
        min = 0.1, max = 20.0, presets = listOf(0.5, 1.0, 1.5, 2.0, 3.0, 5.0, 10.0, 20.0), defaultVal = 1.0, isInteger = false,
    ),
    "altitude" to NumberSetting(
        "altitude", "模拟海拔高度", "米",
        "提示：中国大部分平原地区海拔在 0–500 米之间。",
        min = 0.0, max = 1000.0, presets = listOf(0.0, 30.0, 100.0, 300.0, 500.0, 1000.0), defaultVal = 0.0, isInteger = false,
    ),
    "accuracy" to NumberSetting(
        "accuracy", "位置精度", "米",
        "提示：精度值越小表示定位越精确，建议值 5–20 米。",
        min = 1.0, max = 50.0, presets = listOf(3.0, 5.0, 10.0, 15.0, 20.0, 50.0), defaultVal = 5.0, isInteger = false,
    ),
)

@Composable
fun SettingsScreen(
    s: AppState,
    vm: AppViewModel,
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

    fun set(message: String? = "设置已应用", change: (Settings) -> Settings) =
        vm.run(message) { updateSettings(change) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        PageHeader("设置")

        SectionTitle("模拟方式")
        SettingGroup {
            SettingRow("模拟类型", if (st.xposed) "系统级，无需选择模拟位置应用" else "系统会保留模拟标记",
                icon = Icons.Rounded.Tune, value = if (st.xposed) "Xposed" else "标准", onClick = { choice = "backend" })
            RowDivider()
            SettingRow("模拟范围", if (st.xposed) "其他应用保持真实位置" else "仅 Xposed 模式可用",
                icon = Icons.Rounded.Apps, enabled = st.xposed,
                value = if (st.onlySelected) "${st.packages.size} 个应用" else "所有应用",
                onClick = { if (st.xposed) appScope = true })
            RowDivider()
            SettingRow("权限与模块", when {
                    s.caps.hookActive -> "系统模块运行中 · ${s.caps.framework?.name.orEmpty()}"
                    s.caps.framework != null -> "框架已连接，系统模块未运行（需重启）"
                    st.xposed -> "未连接到 Xposed 框架"
                    else -> "授权与模拟位置应用状态"
                },
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
            SettingRow("随机抖动", "平滑随机漂移，额外偏移不超过 1 米", icon = Icons.Rounded.Shuffle, checked = st.jitter,
                onCheckedChange = { on -> set(if (on) "位置随机抖动已开启" else "位置随机抖动已关闭") { it.copy(jitter = on) } })
        }

        SectionTitle("隐私保护")
        SettingGroup {
            SettingRow("隐藏真实信号", "模拟时应用只看到预设的 Wi-Fi、基站和模拟卫星，扫描不到蓝牙设备，防止用真实信号反查位置",
                icon = Icons.Rounded.PrivacyTip, checked = st.hideEnvironment, enabled = st.xposed,
                onCheckedChange = { on ->
                    set(if (on) "已开启环境伪装，模拟时生效" else "已关闭环境伪装，应用可能用真实 Wi-Fi / 基站 / 蓝牙算出真实位置") { it.copy(hideEnvironment = on) }
                })
        }

        SectionTitle("地图与外观")
        SettingGroup {
            SettingRow("地图图层", null, icon = Icons.Rounded.Layers, value = st.mapSource.label, onClick = { choice = "mapSource" })
            RowDivider()
            SettingRow("高德 Key", "可选，填写后搜索和路线用高德数据", icon = Icons.Rounded.Key,
                value = if (s.mapKeyConfigured) "已设置" else "未设置", onClick = { mapKey = true })
            RowDivider()
            SettingRow("外观", null, icon = Icons.Rounded.Palette,
                value = st.theme.label, onClick = { choice = "theme" })
        }

        SectionTitle("高级")
        SettingGroup {
            SettingRow("高级选项", "上报间隔、持续上报、二次上报", icon = Icons.Rounded.Build,
                value = if (advanced) "收起" else "展开", onClick = { advanced = !advanced })
            AnimatedVisibility(advanced) {
                Column {
                    RowDivider()
                    SettingRow("上报间隔", null, icon = Icons.Rounded.Timer, value = "${st.interval} 毫秒",
                        onClick = { number = NUMBER_SETTINGS["interval"] })
                    RowDivider()
                    SettingRow("持续上报", "标准模式：静止时也按间隔上报位置", icon = Icons.Rounded.Autorenew, checked = st.keepReporting,
                        onCheckedChange = { on -> set(if (on) "持续上报已开启" else "持续上报已关闭") { it.copy(keepReporting = on) } })
                    RowDivider()
                    SettingRow("二次上报", "标准模式：每个位置上报两次", icon = Icons.Rounded.Repeat, checked = st.doubleReport,
                        onCheckedChange = { on -> set(if (on) "二次上报已开启" else "二次上报已关闭") { it.copy(doubleReport = on) } })
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
            s.logs.take(20).forEachIndexed { i, entry ->
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
        AppScopeDialog(if (st.onlySelected) "selected" else "all", st.packages, onDismiss = { appScope = false }) { mode, packages ->
            appScope = false
            set(if (mode == "selected") "仅对选中的 ${packages.size} 个应用模拟" else "已对所有应用模拟") {
                it.copy(onlySelected = mode == "selected", packages = packages.toList())
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
        NumberSettingDialog(n, initial = current, onDismiss = { number = null }) { d ->
            number = null
            when (n.key) {
                "interval" -> set("已设置上报间隔为 ${d.toInt()} 毫秒") { it.copy(interval = d.toInt()) }
                "speedMultiplier" -> set("已设置速度倍率为 ${formatNumber(d)}") { it.copy(speedMultiplier = d) }
                "altitude" -> set("已设置模拟海拔为 ${formatNumber(d)} 米") { it.copy(altitude = d) }
                else -> set("已设置位置精度为 ${formatNumber(d)} 米") { it.copy(accuracy = d) }
            }
        }
    }
    when (choice) {
        "mapSource" -> ChoiceDialog("地图图层", MapSource.entries.map { it.name to it.menuLabel }, st.mapSource.name,
            onDismiss = { choice = null }) { v -> set("地图已切换") { it.copy(mapSource = MapSource.valueOf(v)) } }
        "backend" -> ChoiceDialog("模拟类型", listOf(Backend.MOCK.name to "标准 Android 模拟位置", Backend.XPOSED.name to "Xposed 系统定位（libxposed API 101+）"), st.backend.name,
            onDismiss = { choice = null }) { v -> set("已切换模拟类型") { it.copy(backend = Backend.valueOf(v)) } }
        "speedMode" -> ChoiceDialog("移动模式", SpeedMode.entries.map { it.name to "${it.label} · ${it.metresPerSecond} m/s" }, st.speedMode.name,
            onDismiss = { choice = null }) { v -> set { it.copy(speedMode = SpeedMode.valueOf(v)) } }
        "theme" -> ChoiceDialog("外观", ThemeMode.entries.map { it.name to it.label }, st.theme.name,
            onDismiss = { choice = null }) { v -> set(null) { it.copy(theme = ThemeMode.valueOf(v)) } }
    }
    if (mapKey) {
        MapKeyDialog(configured = s.mapKeyConfigured, onDismiss = { mapKey = false }, onMessage = vm::toast,
            onTest = { vm.run<String>(onDone = vm::toast) { checkMapKey() } },
            onSave = { key -> vm.run(if (key.isEmpty()) "已清除高德 Key，改用 OpenStreetMap 数据" else "高德 Key 已保存") { setMapKey(key) } })
    }
    when (confirm) {
        "logs" -> ConfirmDialog("清除诊断日志", "确定清空所有诊断日志吗？", confirm = "清除", onDismiss = { confirm = null }) {
            vm.run("日志已清除") { store.clearLogs() }
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
private fun NumberSettingDialog(
    setting: NumberSetting,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var textValue by remember(initial) { mutableStateOf(initial) }
    val haptic = LocalHapticFeedback.current
    val parsed = textValue.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(setting.title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(setting.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))

                Text("快捷预设", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    setting.presets.forEach { preset ->
                        val label = if (setting.isInteger) "${preset.toInt()} ${setting.unit}" else "${formatNumber(preset)} ${setting.unit}"
                        val isSelected = parsed != null && kotlin.math.abs(parsed - preset) < 0.001
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                textValue = if (setting.isInteger) preset.toInt().toString() else formatNumber(preset)
                            },
                            label = { Text(label) },
                        )
                    }
                }

                if (parsed != null && parsed in setting.min..setting.max) {
                    Spacer(Modifier.height(12.dp))
                    Text("滑动调节", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Slider(
                        value = parsed.toFloat(),
                        onValueChange = { f ->
                            val rounded = if (setting.isInteger) {
                                (kotlin.math.round(f / 100.0) * 100).toInt().toDouble()
                            } else if (setting.key == "speedMultiplier") {
                                (kotlin.math.round(f * 10.0) / 10.0)
                            } else {
                                (kotlin.math.round(f * 2.0) / 2.0)
                            }
                            textValue = if (setting.isInteger) rounded.toInt().toString() else formatNumber(rounded)
                        },
                        valueRange = setting.min.toFloat()..setting.max.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (setting.isInteger) "${setting.min.toInt()}" else formatNumber(setting.min),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (setting.isInteger) "${setting.max.toInt()}" else formatNumber(setting.max),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text("数值（${setting.unit}）") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val curr = textValue.toDoubleOrNull() ?: setting.defaultVal
                                val step = if (setting.isInteger) 100.0 else if (setting.key == "speedMultiplier") 0.5 else 1.0
                                val next = (curr - step).coerceAtLeast(if (setting.key == "speedMultiplier") 0.01 else 0.0)
                                textValue = if (setting.isInteger) next.toInt().toString() else formatNumber(next)
                            }) {
                                Icon(Icons.Rounded.Remove, "减少")
                            }
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val curr = textValue.toDoubleOrNull() ?: setting.defaultVal
                                val step = if (setting.isInteger) 100.0 else if (setting.key == "speedMultiplier") 0.5 else 1.0
                                val next = curr + step
                                textValue = if (setting.isInteger) next.toInt().toString() else formatNumber(next)
                            }) {
                                Icon(Icons.Rounded.Add, "增加")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val d = textValue.toDoubleOrNull()
                if (d != null) onConfirm(d)
            }) { Text("确定") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    textValue = if (setting.isInteger) setting.defaultVal.toInt().toString() else formatNumber(setting.defaultVal)
                }) { Text("默认值") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
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

3. Xposed 模式：需要支持新版 Xposed API 的框架（LSPosed 2.2 及以上，或 Vector）。在框架管理器中启用 Geoshift（作用域已固定为系统框架、电话服务和蓝牙，无需手动勾选），然后重启手机。系统层会对所有应用（或设置里「模拟范围」选中的应用）伪装定位、Wi-Fi、基站和卫星。模拟开始后即使 Geoshift 被冻结或关闭也会继续，重启手机后自动停止。可在「控制台 → 系统诊断」查看各入口状态。

4. 选点：在地图上点击或长按选点，也可以在搜索框输入地名或「纬度, 经度」。

5. 路线：点地图右侧路线按钮，依次点击地图添加途经点，可选择沿道路步行、骑行或驾车。保存后在「控制台」或「收藏 · 路线」出发；行进中可暂停、继续，Geoshift 在后台被冻结也会继续走。

6. 摇杆：在地图右侧或「控制台」打开摇杆悬浮窗（需要悬浮窗权限），拖动圆盘移动虚拟位置。

7. 环境记录：在「控制台」记录当前环境，会保存手机此刻真实扫描到的基站与 Wi-Fi，之后可在「收藏 · 环境」载入。"""

const val PRIVACY = """我们重视您的隐私。

· 位置、路线、预设、环境记录、设置和日志全部保存在本机应用私有存储，不会上传。
· 地图按您的选择来自 OpenStreetMap、OpenFreeMap、高德地图或 Esri World Imagery，对应服务器可以看到您查看的地图区域。
· 地点搜索、地址显示和沿道路路线：填写高德 Web 服务 Key 时使用 restapi.amap.com（发送关键词或坐标以及您的 Key），高德不覆盖的海外地点及无结果的搜索改用下列公共服务；未填写时使用 photon.komoot.io（搜索）、nominatim.openstreetmap.org（地址）和 routing.openstreetmap.de（道路路线），发送搜索关键词或坐标。
· 不包含任何第三方 SDK、广告或统计服务。
· 未同意使用须知前不会加载在线地图，也不会启动模拟服务。"""

const val LICENSES = """osmdroid — Apache License 2.0
MapLibre Native — BSD 2-Clause License
矢量瓦片 © OpenFreeMap © OpenMapTiles
地图数据 © OpenStreetMap contributors — ODbL
AndroidX / Jetpack Compose — Apache License 2.0
Haze（玻璃底栏）— Apache License 2.0
Kotlin — Apache License 2.0
libxposed API（仅编译期使用，不打包）/ libxposed service — Apache License 2.0
kotlinx.serialization — Apache License 2.0

界面布局参考 Portal（fuqiuluo/Portal，Apache License 2.0）与 PortalDev 的使用流程，代码为本工程独立实现。"""

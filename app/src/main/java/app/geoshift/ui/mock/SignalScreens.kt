package app.geoshift.ui.mock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.geoshift.data.CellPreset
import app.geoshift.data.PortalActions
import app.geoshift.data.PortalState
import app.geoshift.data.ScanRow
import app.geoshift.data.WifiPreset
import app.geoshift.ui.PortalViewModel
import app.geoshift.ui.components.ConfirmDialog
import app.geoshift.ui.components.EmptyState
import app.geoshift.ui.components.IconBadge
import app.geoshift.ui.components.PortalCard
import app.geoshift.ui.components.SectionTitle
import app.geoshift.ui.theme.Success
import org.json.JSONObject

/** Shared page skeleton for the Wi-Fi and cell simulation screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignalPage(
    title: String,
    onBack: () -> Unit,
    onScan: () -> Unit,
    addLabel: String,
    onAdd: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = { TextButton(onClick = onScan) { Icon(Icons.Rounded.Radar, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("扫描") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(addLabel) })
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp), content = content)
    }
}

@Composable
private fun SwitchCard(title: String, description: String, checked: Boolean, current: String?, status: String?, statusOk: Boolean, onToggle: (Boolean) -> Unit) {
    PortalCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onToggle)
        }
        if (current != null) {
            Spacer(Modifier.size(10.dp))
            Text("当前配置：$current", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.size(6.dp))
        Text(status ?: "尚无目标应用读取报告。配置保存不等于目标已收到，不改变实际无线网络。",
            style = MaterialTheme.typography.bodySmall,
            color = if (status != null && !statusOk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PresetItem(icon: ImageVector, title: String, subtitle: String, active: Boolean, onApply: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { if (active) IconBadge(Icons.Rounded.CheckCircle, tint = Success) else IconBadge(icon) },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "编辑") }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "删除") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onApply),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanSheet(title: String, rows: List<ScanRow>, icon: ImageVector, onPick: (ScanRow) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp))
        Text("点击条目保存为预设。扫描来自手机系统，刷新频率可能受限。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        LazyColumn(Modifier.heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(rows) { row ->
                ListItem(
                    headlineContent = { Text(row.title) },
                    supportingContent = { Text(row.subtitle) },
                    leadingContent = { IconBadge(icon, size = 36) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { onPick(row) },
                )
            }
        }
    }
}

// ---- Wi-Fi --------------------------------------------------------------------------------------

@Composable
fun WifiScreen(s: PortalState, vm: PortalViewModel, onBack: () -> Unit) {
    val r = s.runtime
    var editing by remember { mutableStateOf<WifiPreset?>(null) }
    var creating by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<WifiPreset?>(null) }
    var scan by remember { mutableStateOf<List<ScanRow>?>(null) }
    val status = s.featureStatus["wifi"]

    SignalPage("WiFi 模拟", onBack, onScan = {
        vm.toast("扫描WiFi网络中...")
        vm.run<List<ScanRow>>(onDone = { if (it.isEmpty()) vm.toast("未发现WiFi网络或者未开启WIFI") else scan = it }) { scanWifi() }
    }, addLabel = "添加WiFi", onAdd = { creating = JSONObject() }) {
        item {
            SwitchCard(
                "WiFi 模拟", "模拟可以帮助您在不连接到实际WiFi网络的情况下模拟特定的WiFi连接状态。",
                checked = r.wifiEnabled,
                current = r.wifi.optString("ssid").takeIf { it.isNotEmpty() }?.let { "$it · ${r.wifi.optString("bssid")}" },
                status = status?.let { "${if (it.ok) "目标已读取" else "目标报告错误"} · ${it.pkg}${if (it.error.isNotEmpty()) " · " + it.error else ""}" },
                statusOk = status?.ok ?: true,
            ) { on -> vm.run(if (on) "模拟已开启" else "模拟已关闭") { setWifiEnabled(on) } }
        }
        item { SectionTitle("预设 · 点击应用") }
        if (s.wifiPresets.isEmpty()) item { EmptyState(Icons.Rounded.Wifi, "没有WiFi预设", "点击「扫描」添加附近网络，或点击「添加WiFi」手动创建") }
        items(s.wifiPresets, key = { it.id }) { p ->
            PresetItem(Icons.Rounded.Wifi, p.name, "${p.ssid} · ${p.bssid}",
                active = r.wifiEnabled && r.wifi.optString("bssid").equals(p.bssid, true) && r.wifi.optString("ssid") == p.ssid,
                onApply = { vm.run("${p.name} 的WiFi设置已应用") { applyWifiPreset(p.id) } },
                onEdit = { editing = p }, onDelete = { deleting = p })
        }
    }

    val form = editing?.raw ?: creating
    if (form != null) {
        WifiDialog(form, title = if (editing != null) "编辑WiFi" else "添加WiFi", onDismiss = { editing = null; creating = null }) { name, ssid, bssid ->
            val id = editing?.id
            vm.run(if (id != null) "预设已更新" else "预设已添加") { saveWifiPreset(name, ssid, bssid, id) }
        }
    }
    deleting?.let { p ->
        ConfirmDialog("删除预设", "确定要删除「${p.name}」的WiFi设置吗？", confirm = "删除", danger = true, onDismiss = { deleting = null }) {
            vm.run("预设已删除") { delete("wifiPresets", p.id) }
        }
    }
    scan?.let { rows ->
        ScanSheet("扫描到的WiFi网络", rows, Icons.Rounded.Wifi, onPick = { row ->
            scan = null
            creating = JSONObject().put("name", row.title).put("ssid", row.raw.optString("ssid")).put("bssid", row.raw.optString("bssid"))
        }, onDismiss = { scan = null })
    }
}

@Composable
private fun WifiDialog(initial: JSONObject, title: String, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(initial.optString("name")) }
    var ssid by remember { mutableStateOf(initial.optString("ssid")) }
    var bssid by remember { mutableStateOf(initial.optString("bssid")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID") }, placeholder = { Text("输入WiFi名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bssid, { bssid = it }, label = { Text("BSSID") }, placeholder = { Text("例如 aa:bb:cc:dd:ee:ff") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name.ifBlank { ssid.ifBlank { "未命名网络" } }, ssid, bssid); onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---- Cell ---------------------------------------------------------------------------------------

@Composable
fun CellScreen(s: PortalState, vm: PortalViewModel, onBack: () -> Unit) {
    val r = s.runtime
    var editing by remember { mutableStateOf<CellPreset?>(null) }
    var creating by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<CellPreset?>(null) }
    var scan by remember { mutableStateOf<List<ScanRow>?>(null) }
    val status = s.featureStatus["cell"]

    SignalPage("基站模拟", onBack, onScan = {
        vm.toast("扫描基站信息中...")
        vm.run<List<ScanRow>>(onDone = { if (it.isEmpty()) vm.toast("没有找到基站") else scan = it }) { scanCells() }
    }, addLabel = "添加基站", onAdd = { creating = JSONObject() }) {
        item {
            val cell = r.cell
            SwitchCard(
                "基站模拟", "为目标应用提供 GSM / LTE 小区信息；不改变手机真实的网络注册。",
                checked = r.cellEnabled,
                current = if (cell.has("mcc")) "${cell.optString("networkType", "LTE")} · ${PortalActions.carrier(cell.optString("mcc"), cell.optString("mnc"))} · CID ${cell.opt("cid")}" +
                        if (r.cellCount > 1) " 等 ${r.cellCount} 个小区" else "" else null,
                status = status?.let { "${if (it.ok) "目标已读取" else "目标报告错误"} · ${it.pkg}${if (it.error.isNotEmpty()) " · " + it.error else ""}" },
                statusOk = status?.ok ?: true,
            ) { on -> vm.run(if (on) "基站模拟已开启" else "基站模拟已关闭") { setCellEnabled(on) } }
        }
        item { SectionTitle("基站预设 · 点击应用") }
        if (s.cellPresets.isEmpty()) item { EmptyState(Icons.Rounded.CellTower, "没有基站预设", "点击「扫描」按钮可扫描并添加附近的基站，或点击「添加基站」按钮添加新的基站预设") }
        items(s.cellPresets, key = { it.id }) { p ->
            PresetItem(Icons.Rounded.CellTower, p.name,
                "${p.type} · ${PortalActions.carrier(p.mcc, p.mnc)} · ${if (p.type == "GSM") "LAC" else "TAC"} ${p.area} · CID ${p.cid}",
                active = r.cellEnabled && r.cell.optString("id") == p.id,
                onApply = { vm.run("${p.name} 的基站设置已应用") { applyCellPreset(p.id) } },
                onEdit = { editing = p }, onDelete = { deleting = p })
        }
    }

    val form = editing?.raw ?: creating
    if (form != null) {
        CellDialog(form, title = if (editing != null) "编辑基站" else "添加基站", onDismiss = { editing = null; creating = null }) { row ->
            vm.run(if (editing != null) "基站预设已更新" else "基站预设已添加") { saveCellPreset(row) }
        }
    }
    deleting?.let { p ->
        ConfirmDialog("删除预设", "确定要删除「${p.name}」的基站设置吗？", confirm = "删除", danger = true, onDismiss = { deleting = null }) {
            vm.run("基站预设已删除") { delete("cellPresets", p.id) }
        }
    }
    scan?.let { rows ->
        ScanSheet("扫描到的基站", rows, Icons.Rounded.CellTower, onPick = { row ->
            scan = null
            creating = JSONObject(row.raw.toString()).put("name", row.title)
        }, onDismiss = { scan = null })
    }
}

private val CELL_FIELDS = listOf(
    Triple("mcc", "移动国家代码 MCC", "中国为460"),
    Triple("mnc", "移动网络代码 MNC", "移动 00 · 联通 01 · 电信 11"),
    Triple("area", "位置区域码 TAC/LAC", "0–65535"),
    Triple("cid", "小区识别码 CID", "LTE 0–268435455，GSM 0–65535"),
    Triple("pci", "物理小区 PCI", "LTE 0–503"),
    Triple("channel", "频点 EARFCN/ARFCN", "可选"),
    Triple("rssi", "信号强度 dBm", "-160 ~ -20"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CellDialog(initial: JSONObject, title: String, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var type by remember { mutableStateOf(initial.optString("networkType", "LTE").takeIf { it == "GSM" } ?: "LTE") }
    var name by remember { mutableStateOf(initial.optString("name")) }
    val values = remember {
        androidx.compose.runtime.mutableStateMapOf(
            "mcc" to initial.optString("mcc", "460"),
            "mnc" to initial.optString("mnc", "00"),
            "area" to (initial.opt(if (type == "GSM") "lac" else "tac") ?: "").toString().let { if (it == "null") "" else it },
            "cid" to (initial.opt("cid") ?: "").toString().let { if (it == "null") "" else it },
            "pci" to initial.optString("pci", "0").let { if (it == "null") "0" else it },
            "channel" to (initial.opt(if (type == "GSM") "arfcn" else "earfcn") ?: "0").toString().let { if (it == "null") "0" else it },
            "rssi" to initial.optString("rssi", "-85"),
        )
    }
    val unsupported = initial.has("networkType") && initial.optString("networkType") !in listOf("LTE", "GSM")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unsupported) Text("扫描到的 ${initial.optString("networkType")} 小区暂不支持模拟，将按下方选择的类型保存。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, placeholder = { Text("未命名基站") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("LTE", "GSM").forEachIndexed { i, t ->
                        SegmentedButton(selected = type == t, onClick = { type = t }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(t) }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PortalActions.CARRIERS.forEach { (label, mcc, mnc) ->
                        FilterChip(selected = values["mcc"] == mcc && values["mnc"] == mnc,
                            onClick = { values["mcc"] = mcc; values["mnc"] = mnc }, label = { Text(label) })
                    }
                }
                CELL_FIELDS.filter { type == "LTE" || it.first != "pci" }.forEach { (key, label, hint) ->
                    OutlinedTextField(
                        value = values[key].orEmpty(), onValueChange = { values[key] = it },
                        label = { Text(label) }, supportingText = { Text(hint) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val gsm = type == "GSM"
                val row = JSONObject()
                if (initial.has("id")) row.put("id", initial.optString("id"))
                if (initial.has("createdAt")) row.put("createdAt", initial.optLong("createdAt"))
                row.put("name", name.ifBlank { "未命名基站" }).put("networkType", type)
                    .put("mcc", values["mcc"]!!.trim()).put("mnc", values["mnc"]!!.trim())
                    .put(if (gsm) "lac" else "tac", values["area"]!!.trim().toLongOrNull() ?: -1)
                    .put("cid", values["cid"]!!.trim().toLongOrNull() ?: -1)
                    .put(if (gsm) "arfcn" else "earfcn", values["channel"]!!.trim().toIntOrNull() ?: 0)
                    .put("rssi", values["rssi"]!!.trim().toIntOrNull() ?: -85)
                    .put("registered", initial.optBoolean("registered", true))
                if (!gsm) row.put("pci", values["pci"]!!.trim().toIntOrNull() ?: -1)
                onSave(row); onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

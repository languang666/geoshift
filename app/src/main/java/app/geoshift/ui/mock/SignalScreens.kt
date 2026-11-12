package app.geoshift.ui.mock

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import app.geoshift.ui.components.glass

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
import app.geoshift.data.Place
import app.geoshift.data.Geoshift
import app.geoshift.ui.AppState
import app.geoshift.data.ScanRow
import app.geoshift.data.WifiPreset
import app.geoshift.ui.AppViewModel
import app.geoshift.ui.components.Segmented
import app.geoshift.ui.components.IosSwitch
import app.geoshift.ui.components.ConfirmDialog
import app.geoshift.ui.components.EmptyState
import app.geoshift.ui.components.IconBadge
import app.geoshift.ui.components.PortalCard
import app.geoshift.ui.components.SectionTitle
import app.geoshift.ui.theme.Success
import app.geoshift.core.CellSpec
import app.geoshift.core.CellType
import app.geoshift.data.RecordKind
import app.geoshift.data.ScannedCell
import app.geoshift.data.ScannedWifi

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
            // iOS navigation bar: blue back button with the previous page's name, centred title, text action.
            Box(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 4.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, "返回", Modifier.size(20.dp)); Text("控制台", style = MaterialTheme.typography.bodyLarge)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                TextButton(onClick = onScan, modifier = Modifier.align(Alignment.CenterEnd)) { Text("扫描", style = MaterialTheme.typography.bodyLarge) }
            }
        },
        floatingActionButton = {
            Row(Modifier.navigationBarsPadding().glass(CircleShape).clickable(onClick = onAdd).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(addLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
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
            IosSwitch(checked, onToggle)
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
private fun <T> ScanSheet(title: String, rows: List<ScanRow<T>>, icon: ImageVector, onPick: (ScanRow<T>) -> Unit, onDismiss: () -> Unit) {
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
fun WifiScreen(s: AppState, vm: AppViewModel, onBack: () -> Unit) {
    val r = s.runtime
    var editing by remember { mutableStateOf<WifiPreset?>(null) }
    var creating by remember { mutableStateOf<WifiDraft?>(null) }
    var deleting by remember { mutableStateOf<WifiPreset?>(null) }
    var scan by remember { mutableStateOf<List<ScanRow<ScannedWifi>>?>(null) }
    val status = s.reports["wifi"]

    SignalPage("Wi-Fi 模拟", onBack, onScan = {
        vm.toast("扫描Wi-Fi网络中...")
        vm.run<List<ScanRow<ScannedWifi>>>(onDone = { if (it.isEmpty()) vm.toast("未发现Wi-Fi网络或者未开启WIFI") else scan = it }) { scanWifi() }
    }, addLabel = "添加Wi-Fi", onAdd = { creating = WifiDraft() }) {
        item {
            SwitchCard(
                "Wi-Fi 模拟", "模拟可以帮助您在不连接到实际Wi-Fi网络的情况下模拟特定的Wi-Fi连接状态。",
                checked = r.wifiEnabled,
                current = r.wifi?.let { "${it.ssid} · ${it.bssid}" },
                status = status?.let { "${if (it.ok) "系统已替换" else "出现错误"} · ${it.detail}" },
                statusOk = status?.ok ?: true,
            ) { on -> vm.run(if (on) "模拟已开启" else "模拟已关闭") { setWifiEnabled(on) } }
        }
        item { SectionTitle("预设 · 点击应用") }
        if (s.wifiPresets.isEmpty()) item { EmptyState(Icons.Rounded.Wifi, "没有Wi-Fi预设", "点击「扫描」添加附近网络，或点击「添加Wi-Fi」手动创建") }
        items(s.wifiPresets, key = { it.id }) { p ->
            PresetItem(Icons.Rounded.Wifi, p.name, "${p.ssid} · ${p.bssid}",
                active = r.wifiEnabled && r.wifi?.bssid.equals(p.bssid, true) && r.wifi?.ssid == p.ssid,
                onApply = { vm.run("${p.name} 的Wi-Fi设置已应用") { applyWifiPreset(p.id) } },
                onEdit = { editing = p }, onDelete = { deleting = p })
        }
    }

    val form = editing?.let { WifiDraft(it.name, it.ssid, it.bssid) } ?: creating
    if (form != null) {
        WifiDialog(form, title = if (editing != null) "编辑Wi-Fi" else "添加Wi-Fi", onDismiss = { editing = null; creating = null }) { name, ssid, bssid ->
            val id = editing?.id
            vm.run(if (id != null) "预设已更新" else "预设已添加") { saveWifiPreset(name, ssid, bssid, id) }
        }
    }
    deleting?.let { p ->
        ConfirmDialog("删除预设", "确定要删除「${p.name}」的Wi-Fi设置吗？", confirm = "删除", danger = true, onDismiss = { deleting = null }) {
            vm.run("预设已删除") { delete(RecordKind.WIFI, setOf(p.id)) }
        }
    }
    scan?.let { rows ->
        ScanSheet("扫描到的Wi-Fi网络", rows, Icons.Rounded.Wifi, onPick = { row ->
            scan = null
            creating = WifiDraft(row.title, row.value.ssid, row.value.bssid)
        }, onDismiss = { scan = null })
    }
}

@Composable
private fun WifiDialog(initial: WifiDraft, title: String, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var ssid by remember { mutableStateOf(initial.ssid) }
    var bssid by remember { mutableStateOf(initial.bssid) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID") }, placeholder = { Text("输入Wi-Fi名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
fun CellScreen(s: AppState, vm: AppViewModel, onBack: () -> Unit) {
    val r = s.runtime
    var editing by remember { mutableStateOf<CellPreset?>(null) }
    var creating by remember { mutableStateOf<CellDraft?>(null) }
    var deleting by remember { mutableStateOf<CellPreset?>(null) }
    var scan by remember { mutableStateOf<List<ScanRow<ScannedCell>>?>(null) }
    val status = s.reports["phone"] ?: s.reports["callbacks"]

    SignalPage("基站模拟", onBack, onScan = {
        vm.toast("扫描基站信息中...")
        vm.run<List<ScanRow<ScannedCell>>>(onDone = { if (it.isEmpty()) vm.toast("没有找到基站") else scan = it }) { scanCells() }
    }, addLabel = "添加基站", onAdd = { creating = CellDraft() }) {
        item {
            val cell = r.cells.firstOrNull()
            SwitchCard(
                "基站模拟", "为目标应用提供 GSM / LTE / NR 小区信息；不改变手机真实的网络注册。",
                checked = r.cellEnabled,
                current = if (cell != null) "${cell.type} · ${Geoshift.carrier(cell.mcc, cell.mnc)} · CID ${cell.cid}" +
                        if (r.cells.size > 1) " 等 ${r.cells.size} 个小区" else "" else null,
                status = status?.let { "${if (it.ok) "系统已替换" else "出现错误"} · ${it.detail}" },
                statusOk = status?.ok ?: true,
            ) { on -> vm.run(if (on) "基站模拟已开启" else "基站模拟已关闭") { setCellEnabled(on) } }
        }
        item { SectionTitle("基站预设 · 点击应用") }
        if (s.cellPresets.isEmpty()) item { EmptyState(Icons.Rounded.CellTower, "没有基站预设", "点击「扫描」按钮可扫描并添加附近的基站，或点击「添加基站」按钮添加新的基站预设") }
        items(s.cellPresets, key = { it.id }) { p ->
            PresetItem(Icons.Rounded.CellTower, p.name,
                "${p.cell.type} · ${Geoshift.carrier(p.cell.mcc, p.cell.mnc)} · ${if (p.cell.type == CellType.GSM) "LAC" else "TAC"} ${p.cell.area} · CID ${p.cell.cid}",
                active = r.cellEnabled && r.cellPresetId == p.id,
                onApply = { vm.run("${p.name} 的基站设置已应用") { applyCellPreset(p.id) } },
                onEdit = { editing = p }, onDelete = { deleting = p })
        }
    }

    val form = editing?.let { CellDraft.of(it.name, it.cell) } ?: creating
    if (form != null) {
        val id = editing?.id
        CellDialog(form, title = if (editing != null) "编辑基站" else "添加基站", onDismiss = { editing = null; creating = null }) { name, cell ->
            vm.run(if (id != null) "基站预设已更新" else "基站预设已添加", onDone = { editing = null; creating = null }) { saveCellPreset(name, cell, id) }
        }
    }
    deleting?.let { p ->
        ConfirmDialog("删除预设", "确定要删除「${p.name}」的基站设置吗？", confirm = "删除", danger = true, onDismiss = { deleting = null }) {
            vm.run("基站预设已删除") { delete(RecordKind.CELLS, setOf(p.id)) }
        }
    }
    scan?.let { rows ->
        ScanSheet("扫描到的基站", rows, Icons.Rounded.CellTower, onPick = { row ->
            scan = null
            creating = CellDraft.scanned(row.title, row.value)
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
private fun CellDialog(initial: CellDraft, title: String, onDismiss: () -> Unit, onSave: (String, CellSpec) -> Unit) {
    var type by remember { mutableStateOf(if (initial.type in listOf("GSM", "NR")) initial.type else "LTE") }
    var name by remember { mutableStateOf(initial.name) }
    val values = remember {
        androidx.compose.runtime.mutableStateMapOf(
            "mcc" to initial.mcc, "mnc" to initial.mnc, "area" to initial.area, "cid" to initial.cid,
            "pci" to initial.pci, "channel" to initial.channel, "rssi" to initial.dbm,
        )
    }
    val unsupported = initial.type !in listOf("LTE", "GSM", "NR")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unsupported) Text("扫描到的 ${initial.type} 小区暂不支持模拟，将按下方选择的类型保存。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, placeholder = { Text("未命名基站") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Segmented(listOf("LTE", "GSM", "NR"), listOf("LTE", "GSM", "NR").indexOf(type)) { type = listOf("LTE", "GSM", "NR")[it] }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Geoshift.CARRIERS.forEach { (label, mcc, mnc) ->
                        FilterChip(selected = values["mcc"] == mcc && values["mnc"] == mnc,
                            onClick = { values["mcc"] = mcc; values["mnc"] = mnc }, label = { Text(label) })
                    }
                }
                CELL_FIELDS.filter { type != "GSM" || it.first != "pci" }.forEach { (key, label, hint) ->
                    OutlinedTextField(
                        value = values[key].orEmpty(), onValueChange = { values[key] = it },
                        label = { Text(label) }, supportingText = { Text(if (type == "NR") when (key) { "area" -> "NR TAC 0–16777215"; "cid" -> "NR NCI 0–68719476735"; "pci" -> "NR PCI 0–1007"; "channel" -> "NR-ARFCN 0–3279165"; "rssi" -> "NR -156 ~ -31 dBm"; else -> hint } else hint) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val gsm = type == "GSM"
                onSave(name.ifBlank { "未命名基站" }, CellSpec(
                    type = CellType.valueOf(type),
                    mcc = values["mcc"]!!.trim(), mnc = values["mnc"]!!.trim(),
                    cid = values["cid"]!!.trim().toLongOrNull() ?: -1,
                    area = values["area"]!!.trim().toIntOrNull() ?: -1,
                    pci = if (gsm) 0 else values["pci"]!!.trim().toIntOrNull() ?: -1,
                    channel = values["channel"]!!.trim().let { if (it.isEmpty()) 0 else it.toIntOrNull() ?: -1 },
                    dbm = values["rssi"]!!.trim().toIntOrNull() ?: 0,
                    registered = initial.registered,
                ))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Editor contents for a Wi-Fi preset. */
private data class WifiDraft(val name: String = "", val ssid: String = "", val bssid: String = "")

/** Editor contents for a cell preset, as text fields. */
private data class CellDraft(
    val name: String = "", val type: String = "LTE", val mcc: String = "460", val mnc: String = "00", val area: String = "",
    val cid: String = "", val pci: String = "0", val channel: String = "0", val dbm: String = "-85", val registered: Boolean = true,
) {
    companion object {
        fun of(name: String, c: CellSpec) = CellDraft(name, c.type.name, c.mcc, c.mnc, c.area.toString(), c.cid.toString(),
            c.pci.toString(), c.channel.toString(), c.dbm.toString(), c.registered)

        fun scanned(name: String, c: ScannedCell) = CellDraft(name, c.type, c.mcc.ifEmpty { "460" }, c.mnc.ifEmpty { "00" },
            c.area?.toString().orEmpty(), c.cid?.toString().orEmpty(), (c.pci ?: 0).toString(), (c.channel ?: 0).toString(),
            c.dbm.toString(), c.registered)
    }
}

package app.geoshift.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.geoshift.data.LatLon
import app.geoshift.data.PortalState
import app.geoshift.data.RoadProfile
import app.geoshift.ui.PortalViewModel
import app.geoshift.ui.components.ConfirmDialog
import app.geoshift.ui.components.EmptyState
import app.geoshift.ui.components.IconBadge
import app.geoshift.ui.components.InputDialog
import app.geoshift.ui.components.PageHeader
import app.geoshift.ui.components.formatCoord
import app.geoshift.ui.components.formatDistance
import app.geoshift.ui.components.formatTime
import app.geoshift.ui.theme.Danger

private enum class Sort(val label: String) { NEWEST("最新添加"), OLDEST("最早添加"), NAME("按名称排序") }

private data class HistoryRow(
    val key: String, val id: String, val name: String, val detail: String, val time: Long,
    val icon: ImageVector, val position: LatLon?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    s: PortalState,
    vm: PortalViewModel,
    contentPadding: PaddingValues,
    initialTab: Int,
    onShowOnMap: (LatLon) -> Unit,
    onGoMock: () -> Unit,
    onExport: () -> Unit,
) {
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.NEWEST) }
    var sortMenu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<HistoryRow?>(null) }
    var confirmDelete by remember { mutableStateOf<HistoryRow?>(null) }
    var rename by remember { mutableStateOf<HistoryRow?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(tab) {
        selectionMode = false
        selectedIds = emptySet()
    }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }

    val rows = when (tab) {
        0 -> s.locations.map {
            HistoryRow("locations", it.id, it.name, it.address.ifEmpty { formatCoord(it.position.lat, it.position.lon) }, it.createdAt, Icons.Rounded.Place, it.position)
        }
        1 -> s.routes.map {
            HistoryRow("routes", it.id, it.name, if (it.profile == RoadProfile.STRAIGHT) "${it.points.size} 个点位 · ${formatDistance(it.length)}路线"
                else "沿道路${it.profile.label} · ${it.waypointCount} 个途经点 · ${formatDistance(it.length)}", it.createdAt, Icons.Rounded.Route, it.points.firstOrNull())
        }
        else -> s.environments.map {
            HistoryRow("environments", it.id, it.name, "基站 ${it.cellCount} · Wi-Fi ${it.wifiCount}${if (it.simulated) " · 采集时模拟开启" else ""}",
                it.capturedAt, Icons.Rounded.Radar, it.position)
        }
    }.filter { query.isBlank() || it.name.contains(query, true) || it.detail.contains(query, true) }
        .let { list ->
            when (sort) {
                Sort.NEWEST -> list.sortedByDescending { it.time }
                Sort.OLDEST -> list.sortedBy { it.time }
                Sort.NAME -> list.sortedBy { it.name }
            }
        }

    Column(Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding())) {
        if (selectionMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectionMode = false; selectedIds = emptySet() }) {
                    Icon(Icons.Rounded.Close, "退出多选")
                }
                Text("已选 ${selectedIds.size} 项", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedIds = if (selectedIds.size == rows.size) emptySet() else rows.map { it.id }.toSet()
                }) {
                    Text(if (selectedIds.size == rows.size) "取消全选" else "全选")
                }
                if (selectedIds.isNotEmpty()) {
                    IconButton(onClick = { confirmBatchDelete = true }) {
                        Icon(Icons.Rounded.Delete, "删除选中", tint = Danger)
                    }
                }
            }
        } else {
            PageHeader("收藏") {
                if (rows.isNotEmpty()) {
                    IconButton(onClick = { selectionMode = true }) {
                        Icon(Icons.Rounded.Checklist, "批量管理")
                    }
                }
                IconButton(onClick = onExport) { Icon(Icons.Rounded.Upload, "导出备份") }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "排序") }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        Sort.entries.forEach {
                            DropdownMenuItem(text = { Text(it.label, fontWeight = if (it == sort) FontWeight.Bold else null) },
                                onClick = { sort = it; sortMenu = false })
                        }
                    }
                }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            val tabs = listOf("位置" to s.locations.size, "路线" to s.routes.size, "环境" to s.environments.size)
            tabs.forEachIndexed { i, (label, n) ->
                SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                    label = { Text(if (n > 0) "$label $n" else label) })
            }
        }
        TextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(when (tab) { 1 -> "搜索路线"; 2 -> "搜索环境记录"; else -> "搜索位置" }) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "清除") } },
            singleLine = true, shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (rows.isEmpty()) {
            when {
                query.isNotBlank() -> EmptyState(Icons.Rounded.Search, if (tab == 1) "未找到匹配的路线" else "未找到匹配的位置", "尝试使用不同的关键词搜索")
                tab == 0 -> EmptyState(Icons.Rounded.Place, "还没有收藏位置", "在地图上选点，点卡片右下角的加号收藏")
                tab == 1 -> EmptyState(Icons.Rounded.Route, "还没有路线", "点地图右侧的路线按钮，依次点选途经点")
                else -> EmptyState(Icons.Rounded.Radar, "暂无环境记录", "在「控制台」记录此处的基站与 Wi-Fi")
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.key + it.id }) { row ->
                    if (selectionMode) {
                        val isSelected = selectedIds.contains(row.id)
                        ListItem(
                            headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
                            supportingContent = {
                                Column {
                                    Text(row.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatTime(row.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            leadingContent = {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedIds = if (checked) selectedIds + row.id else selectedIds - row.id
                                    },
                                )
                            },
                            trailingContent = { IconBadge(row.icon) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.clip(RoundedCornerShape(18.dp))
                                .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedIds = if (isSelected) selectedIds - row.id else selectedIds + row.id
                                },
                        )
                    } else {
                        SwipeRow(row, onClick = { sheet = row }, onDelete = { confirmDelete = row })
                    }
                }
            }
        }
    }

    sheet?.let { row ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(row.icon)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(row.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("创建时间 ${formatTime(row.time)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.size(12.dp))
                val close = { sheet = null }
                when (row.key) {
                    "locations" -> {
                        val p = row.position!!
                        SheetAction(Icons.Rounded.PlayArrow, "立即模拟此位置") {
                            close(); vm.run("模拟定位已开启") { startPoint(p) }; onShowOnMap(p)
                        }
                        SheetAction(Icons.Rounded.Map, "设为虚拟位置并在地图查看") {
                            close(); vm.run("已设置为当前虚拟位置") { setLocation(p) }; onShowOnMap(p)
                        }
                        SheetAction(Icons.Rounded.Edit, "重命名") { close(); rename = row }
                    }
                    "routes" -> {
                        SheetAction(Icons.Rounded.PlayArrow, "开始路线模拟") {
                            close(); vm.run("路线模拟开始") { startRoute(row.id) }; onGoMock()
                        }
                        SheetAction(Icons.Rounded.Map, "选择此路线并在地图查看") {
                            close(); vm.run("已选择路线") { selectRoute(row.id) }; row.position?.let(onShowOnMap)
                        }
                    }
                    else -> {
                        SheetAction(Icons.Rounded.Upload, "载入此环境（坐标、基站、Wi-Fi）") {
                            close(); vm.run<String>(onDone = { vm.toast(it) }) { applyEnvironment(row.id) }
                        }
                        SheetAction(Icons.Rounded.Search, "查看详情") {
                            close(); detail = s.environments.firstOrNull { it.id == row.id }?.raw?.toString(2)
                        }
                    }
                }
                SheetAction(Icons.Rounded.Delete, "删除", danger = true) { close(); confirmDelete = row }
            }
        }
    }

    confirmDelete?.let { row ->
        val what = when (row.key) { "locations" -> "位置"; "routes" -> "路线"; else -> "环境记录" }
        ConfirmDialog("删除$what", "您确定要删除「${row.name}」吗？此操作无法撤销。", confirm = "删除", danger = true,
            onDismiss = { confirmDelete = null }) {
            vm.run("${what}已成功删除") { delete(row.key, row.id) }
        }
    }
    // Search or sort can hide selected rows; only visible rows stay selected (and get deleted).
    val visibleIds = rows.map { it.id }.toSet()
    LaunchedEffect(visibleIds) { selectedIds = selectedIds intersect visibleIds }

    if (confirmBatchDelete) {
        val what = when (tab) { 0 -> "位置"; 1 -> "路线"; else -> "环境记录" }
        ConfirmDialog(
            "批量删除$what",
            "确定要删除选中的 ${selectedIds.size} 项$what 吗？此操作无法撤销。",
            confirm = "批量删除",
            danger = true,
            onDismiss = { confirmBatchDelete = false },
        ) {
            val key = when (tab) { 0 -> "locations"; 1 -> "routes"; else -> "environments" }
            val ids = (selectedIds intersect visibleIds).toList()
            val count = ids.size
            selectionMode = false
            selectedIds = emptySet()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            vm.run("已成功删除 ${count} 项$what") {
                ids.forEach { delete(key, it) }
            }
        }
    }
    rename?.let { row ->
        InputDialog("重命名", initial = row.name, label = "名称", onDismiss = { rename = null }) { name ->
            vm.run("已重命名") { renameLocation(row.id, name) }
        }
    }
    detail?.let { text ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("环境详情") },
            text = {
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(row: HistoryRow, onClick: () -> Unit, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) onDelete()
        false // Keep the row; deletion happens after confirmation.
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(Danger).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Rounded.Delete, "删除", tint = androidx.compose.ui.graphics.Color.White)
            }
        },
    ) {
        ListItem(
            headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
            supportingContent = {
                Column {
                    Text(row.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatTime(row.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            leadingContent = { IconBadge(row.icon) },
            trailingContent = { Icon(Icons.Rounded.MoreVert, "更多操作（左滑删除）", tint = MaterialTheme.colorScheme.outline) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.clip(RoundedCornerShape(18.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        )
    }
}

@Composable
private fun SheetAction(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text, color = if (danger) Danger else MaterialTheme.colorScheme.onSurface) },
        leadingContent = { Icon(icon, null, tint = if (danger) Danger else MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

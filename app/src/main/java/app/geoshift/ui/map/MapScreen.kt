package app.geoshift.ui.map

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.geoshift.Geo
import app.geoshift.MapSource
import org.json.JSONObject
import app.geoshift.data.LatLon
import app.geoshift.data.PortalActions
import app.geoshift.data.PortalState
import app.geoshift.data.routeLength
import app.geoshift.ui.PortalViewModel
import app.geoshift.ui.components.InputDialog
import app.geoshift.ui.components.StatusPill
import app.geoshift.ui.components.Tone
import app.geoshift.ui.components.formatCoord
import app.geoshift.ui.components.formatDistance
import app.geoshift.ui.theme.Danger
import app.geoshift.ui.theme.isDark
import app.geoshift.ui.settings.MapKeyDialog
import kotlinx.coroutines.delay

@Composable
fun MapScreen(
    s: PortalState,
    vm: PortalViewModel,
    contentPadding: PaddingValues,
    centerRequest: CenterRequest?,
    onCenter: (CenterRequest) -> Unit,
    onOpenSetup: () -> Unit,
) {
    val r = s.runtime
    var selected by remember { mutableStateOf<LatLon?>(null) }
    var address by remember { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    val draft = remember { mutableStateListOf<LatLon>() }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PortalActions.Place>>(emptyList()) }
    var saveDialog by remember { mutableStateOf<String?>(null) }
    var routeDialog by remember { mutableStateOf(false) }
    var mapMenu by remember { mutableStateOf(false) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    var addressGeneration by remember { mutableIntStateOf(0) }
    val camera = remember { MapCamera(r.position) }
    var androidKeyDialog by remember { mutableStateOf(false) }
    var nativeFailure by remember(s.settings.amapAndroidKeyRevision, s.settings.mapSource) { mutableStateOf(false) }
    var nativeLoaded by remember(s.settings.amapAndroidKeyRevision, s.settings.mapSource) { mutableStateOf(false) }
    var slowNative by remember(s.settings.amapAndroidKeyRevision, s.settings.mapSource) { mutableStateOf(false) }
    var bottomCardHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val nativeMap = s.settings.mapSource == MapSource.AMAP && s.settings.amapAndroidKeyConfigured &&
        s.settings.amapSdkAccepted && s.settings.privacyAccepted && !nativeFailure
    LaunchedEffect(nativeMap) {
        if (nativeMap) { delay(15_000); slowNative = true }
    }
    val focus = LocalFocusManager.current

    // Reverse-geocode the selected point when a map key is configured.
    LaunchedEffect(selected, s.settings.mapKeyConfigured, s.settings.privacyAccepted) {
        val generation = ++addressGeneration
        val p = selected ?: return@LaunchedEffect
        if (s.settings.mapKeyConfigured && s.settings.privacyAccepted)
            vm.run<String>(onDone = { if (selected == p && addressGeneration == generation) address = it }) { reverseGeocode(p) }
    }

    fun select(p: LatLon) {
        selected = p
        address = ""
        searchGeneration++
        results = emptyList()
        focus.clearFocus()
    }

    Box(Modifier.fillMaxSize()) {
        if (nativeMap) AmapMap(
            modifier = Modifier.fillMaxSize(), virtual = r.position, simulating = r.enabled,
            selected = if (editing) null else selected, route = if (editing) draft.toList() else r.route,
            routeEditing = editing, dark = isDark(s.settings.theme), keyRevision = s.settings.amapAndroidKeyRevision,
            camera = camera, center = centerRequest, logoBottomMargin = bottomCardHeight + with(density) { 8.dp.roundToPx() },
            onTap = { p -> if (editing) draft.add(p) else select(p) },
            onLongPress = { p -> if (!editing) select(p) },
            onLoaded = { nativeLoaded = true }, onFailure = { nativeFailure = true },
        ) else OsmMap(
            modifier = Modifier.fillMaxSize(),
            virtual = r.position,
            simulating = r.enabled,
            selected = if (editing) null else selected,
            route = if (editing) draft.toList() else r.route,
            routeEditing = editing,
            online = s.settings.privacyAccepted,
            dark = isDark(s.settings.theme),
            source = s.settings.mapSource,
            camera = camera,
            center = centerRequest,
            onTap = { p -> if (editing) draft.add(p) else select(p) },
            onLongPress = { p -> if (!editing) select(p) },
        )

        // ---- Top: search bar / route banner ----
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
            AnimatedContent(editing, label = "top") { isEditing ->
                if (isEditing) {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primary, shadowElevation = 4.dp) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Route, null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(12.dp))
                            Text("路线编辑 · 点击地图依次添加点位", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else {
                    SearchBar(query, onQuery = { query = it; searchGeneration++; results = emptyList() }, onSearch = {
                        focus.clearFocus()
                        val keyword = query.trim()
                        val generation = ++searchGeneration
                        if (keyword.isEmpty()) vm.toast("请输入地点关键词")
                        else if (!s.settings.mapKeyConfigured) vm.toast("位置搜索需要在设置中填写高德 Web 服务 Key")
                        else vm.run<List<PortalActions.Place>>(onDone = {
                            if (searchGeneration == generation && !editing) {
                                results = it
                                if (it.isEmpty()) vm.toast("未找到相关位置，尝试使用不同的关键词搜索")
                            }
                        }) { search(keyword) }
                    }, onClear = { query = ""; searchGeneration++; results = emptyList() })
                }
            }
            if (s.settings.mapSource == MapSource.AMAP && (!nativeMap || !nativeLoaded)) {
                Surface(Modifier.padding(top = 8.dp).fillMaxWidth().clickable { androidKeyDialog = true },
                    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                    Text(when {
                        nativeFailure -> "高清地图暂不可用，已使用基础图 · 查看配置"
                        !nativeMap -> "高德基础图 · 配置 Android Key 开启高清地图"
                        slowNative -> "高清地图加载较慢 · 检查网络、Android Key 与 SHA1"
                        else -> "正在加载高德高清地图…"
                    }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
            AnimatedVisibility(results.isNotEmpty() && !editing) {
                Surface(Modifier.padding(top = 8.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface) {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(results) { place ->
                            Row(Modifier.fillMaxWidth().clickable {
                                select(place.position)
                                address = place.address.ifEmpty { place.name }
                                onCenter(CenterRequest(place.position, 16.0))
                            }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (place.address.isNotEmpty()) Text(place.address, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        // ---- Right: floating tools ----
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box {
                MapTool(Icons.Rounded.Layers, "地图图层：${s.settings.mapSource.label}", active = s.settings.mapSource == MapSource.AMAP) { mapMenu = true }
                DropdownMenu(expanded = mapMenu, onDismissRequest = { mapMenu = false }) {
                    MapSource.values().forEach { source ->
                        DropdownMenuItem(text = { Text(when (source) { MapSource.DEFAULT -> "全球地图 · OpenStreetMap（无需 Key）"; MapSource.SATELLITE -> "全球卫星影像 · Esri"; else -> "高德地图 · 基础 / 高清" }) },
                            trailingIcon = { if (source == s.settings.mapSource) Icon(Icons.Rounded.Check, "已选择") },
                            onClick = {
                                mapMenu = false
                                if (source != s.settings.mapSource) vm.run("已切换至${source.label}") {
                                    saveSettings(JSONObject().put("mapSource", source.key))
                                }
                            })
                    }
                    if (s.settings.mapSource == MapSource.AMAP) {
                        DropdownMenuItem(text = { Text("高清地图设置与申请教程") }, onClick = { mapMenu = false; androidKeyDialog = true })
                        if (s.settings.amapAndroidKeyConfigured) DropdownMenuItem(
                            text = { Text(if (nativeMap) "切换为高德基础图" else "重试高德高清地图") },
                            onClick = {
                                mapMenu = false
                                nativeFailure = nativeMap
                                nativeLoaded = false
                                slowNative = false
                            })
                    }
                }
            }
            MapTool(Icons.Rounded.PinDrop, "虚拟位置") { onCenter(CenterRequest(r.position)) }
            MapTool(Icons.Rounded.MyLocation, "原始位置（先停止模拟）") {
                vm.run<LatLon>(onDone = { select(it); onCenter(CenterRequest(it, 16.0)) }) { deviceLocation() }
            }
            MapTool(Icons.Rounded.Route, "路线编辑", active = editing) {
                if (!editing) { draft.clear(); selected = null; searchGeneration++; results = emptyList(); focus.clearFocus() }
                editing = !editing
            }
            MapTool(Icons.Rounded.SportsEsports, "摇杆", active = r.overlay) {
                vm.run(if (r.overlay) "摇杆悬浮窗已关闭" else "摇杆服务已启动") { overlay(!r.overlay) }
            }
        }

        // ---- Bottom: contextual card ----
        val sel = selected
        val mode = when {
            editing -> 2
            sel != null -> 1
            else -> 0
        }
        AnimatedContent(
            targetState = mode,
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomCardHeight = it.height }.padding(contentPadding).padding(12.dp),
            transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { it / 2 } + fadeOut()) },
            label = "bottom",
        ) { m ->
            when (m) {
                2 -> RouteEditorCard(
                    count = draft.size,
                    length = routeLength(draft),
                    onUndo = { if (draft.isNotEmpty()) draft.removeAt(draft.lastIndex) },
                    onClear = { draft.clear() },
                    onSave = { if (draft.size < 2) vm.toast("请至少选择两个路线点") else routeDialog = true },
                    onClose = { editing = false; draft.clear() },
                )
                1 -> if (sel != null) PreviewCard(
                    point = sel,
                    address = address,
                    distance = Geo.distance(sel.lat, sel.lon, r.position.lat, r.position.lon),
                    running = r.enabled,
                    onClose = { selected = null },
                    onSetVirtual = {
                        vm.run(if (r.enabled) "位置切换成功" else "已设置为当前虚拟位置", onDone = { _: Unit -> selected = null }) { setLocation(sel) }
                    },
                    onStart = { vm.run("模拟定位已开启", onDone = { _: Unit -> selected = null }) { startPoint(sel) } },
                    onSave = { saveDialog = address },
                )
                else -> StatusCard(s, onToggle = {
                    if (r.enabled) vm.run("模拟定位已关闭") { stop() }
                    else vm.run("模拟定位已开启") { if (r.routeMode && r.route.size >= 2) startRoute() else startPoint() }
                }, onOpenSetup = onOpenSetup)
            }
        }

        if (!nativeMap) Text(
            s.settings.mapSource.attribution,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF555B6E),
            modifier = Modifier.align(Alignment.BottomStart).padding(contentPadding).padding(start = 4.dp, bottom = 2.dp)
                .background(Color(0x99FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
        )
    }

    if (androidKeyDialog) MapKeyDialog(androidKey = true, configured = s.settings.amapAndroidKeyConfigured,
        onDismiss = { androidKeyDialog = false }, onMessage = vm::toast, onSave = { key, accepted ->
            vm.run(if (key.isEmpty()) "已清除 Android Key" else "Android Key 已保存，正在加载高清地图") {
                saveSettings(JSONObject().put("amapAndroidKey", key).put("amapSdkAccepted", accepted))
            }
        })

    saveDialog?.let { suggestion ->
        val p = selected
        InputDialog("添加到历史", initial = suggestion.ifEmpty { "未命名位置" }, label = "名称",
            hint = if (suggestion.isNotEmpty()) "名称建议：$suggestion" else null,
            onDismiss = { saveDialog = null }) { name ->
            if (p != null) vm.run<String>("位置已成功添加到历史记录") { saveLocation(name, p, address) }
        }
    }
    if (routeDialog) {
        InputDialog("保存路线", initial = "路线 ${draft.size} 点", label = "名称", hint = "共 ${draft.size} 个点 · ${formatDistance(routeLength(draft))}路线",
            onDismiss = { routeDialog = false }) { name ->
            val points = draft.toList()
            vm.run<String>("路线保存成功", onDone = { editing = false; draft.clear() }) { saveRoute(name, points) }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
        TextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("搜索地点、地址") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "清除") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MapTool(icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
        modifier = Modifier.size(46.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, modifier = Modifier.size(22.dp)) }
    }
}

@Composable
private fun BottomCard(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
private fun StatusCard(s: PortalState, onToggle: () -> Unit, onOpenSetup: () -> Unit) {
    val r = s.runtime
    val ready = s.settings.privacyAccepted && s.caps.preciseLocation &&
            (if (s.settings.lsposed) s.caps.hookActive else s.caps.mockSelected)
    BottomCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    r.enabled && r.paused -> StatusPill("路线模拟已暂停", Tone.WARN)
                    r.enabled && r.routeMode -> StatusPill("路线模拟中", Tone.OK)
                    r.enabled -> StatusPill("模拟定位已开启", Tone.OK)
                    r.error.isNotEmpty() -> StatusPill("模拟启动失败", Tone.ERROR)
                    else -> StatusPill("模拟定位已关闭", Tone.WARN)
                }
                Spacer(Modifier.size(8.dp))
                Text("虚拟位置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatCoord(r.position.lat, r.position.lon), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onToggle,
                shape = CircleShape,
                colors = if (r.enabled) ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White) else ButtonDefaults.buttonColors(),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
            ) {
                Icon(if (r.enabled) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(if (r.enabled) "停止" else "开始模拟")
            }
        }
        if (r.enabled && r.routeMode) {
            Spacer(Modifier.size(12.dp))
            Text("${r.routeName.ifEmpty { "未命名路线" }} · ${(r.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            LinearProgressIndicator(progress = { r.progress.toFloat() }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
        }
        if (r.error.isNotEmpty() && !r.enabled) {
            Spacer(Modifier.size(8.dp))
            Text(r.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (!ready) {
            Spacer(Modifier.size(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onOpenSetup).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("还有权限或模块未配置，点此完成设置", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                Text("去设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Spacer(Modifier.size(6.dp))
            Text("点击地图选点，长按也可以快速选点", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PreviewCard(
    point: LatLon, address: String, distance: Double, running: Boolean,
    onClose: () -> Unit, onSetVirtual: () -> Unit, onStart: () -> Unit, onSave: () -> Unit,
) {
    BottomCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("位置预览", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(address.ifEmpty { "未知地址" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(4.dp))
                Text("坐标 ${formatCoord(point.lat, point.lon)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("距当前虚拟位置 ${formatDistance(distance)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭") }
        }
        Spacer(Modifier.size(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running) {
                Button(onClick = onSetVirtual, modifier = Modifier.weight(1f)) { Text("设为虚拟位置") }
            } else {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("开始模拟", maxLines = 1)
                }
                FilledTonalButton(onClick = onSetVirtual, modifier = Modifier.weight(1f)) { Text("设为虚拟位置", maxLines = 1) }
            }
            OutlinedButton(onClick = onSave, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(Icons.Rounded.Add, "添加到历史", Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun RouteEditorCard(count: Int, length: Double, onUndo: () -> Unit, onClear: () -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    BottomCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("路线编辑", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("$count 个点位 · ${formatDistance(length)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "退出编辑") }
        }
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onUndo, enabled = count > 0) {
                Icon(Icons.AutoMirrored.Rounded.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("撤销")
            }
            OutlinedButton(onClick = onClear, enabled = count > 0) {
                Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("清空")
            }
            Button(onClick = onSave, enabled = count >= 2, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("保存路线")
            }
        }
    }
}

package app.geoshift.ui.map

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import app.geoshift.ui.components.glass
import app.geoshift.ui.theme.palette
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import app.geoshift.ui.settings.MapKeyDialog
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.geoshift.core.Geo
import app.geoshift.map.MapSource
import app.geoshift.core.LatLon
import app.geoshift.data.Place
import app.geoshift.data.Geoshift
import app.geoshift.ui.AppState
import app.geoshift.ui.AppViewModel
import app.geoshift.ui.components.CoordinateRow
import app.geoshift.ui.components.InputDialog
import app.geoshift.ui.components.formatCoord
import app.geoshift.ui.components.formatDistance
import app.geoshift.ui.theme.Danger
import app.geoshift.ui.theme.isDark
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.ui.platform.LocalContext
import app.geoshift.data.RoadProfile
import app.geoshift.data.net.RoadRouter

@Composable
fun MapScreen(
    s: AppState,
    vm: AppViewModel,
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
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var saveDialog by remember { mutableStateOf<String?>(null) }
    var routeDialog by remember { mutableStateOf(false) }
    var mapMenu by remember { mutableStateOf(false) }
    var keyDialog by remember { mutableStateOf(false) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    var addressGeneration by remember { mutableIntStateOf(0) }
    val camera = remember { MapCamera(r.position) }
    var mapBearing by remember { mutableDoubleStateOf(0.0) }
    var bottomCardHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // Everything but the plain OpenStreetMap layer is drawn by MapLibre (sharper, rotatable).
    val vectorMap = s.settings.mapSource != MapSource.OSM
    val focus = LocalFocusManager.current

    val searchHistory = s.settings.searchHistory
    val saveSearchHistory: (String) -> Unit = { keyword ->
        vm.run { store.updateSettings { it.copy(searchHistory = (listOf(keyword) + it.searchHistory.filter { k -> k != keyword }).take(6)) } }
    }
    val clearSearchHistory: () -> Unit = { vm.run { store.updateSettings { it.copy(searchHistory = emptyList()) } } }

    val doSearch: (String) -> Unit = { kw ->
        focus.clearFocus()
        val keyword = kw.trim()
        val generation = ++searchGeneration
        if (keyword.isEmpty()) {
            vm.toast("请输入地点关键词")
        } else if (!s.settings.privacyAccepted) {
            vm.toast("位置搜索需要联网，请先在设置中同意隐私说明")
        } else {
            saveSearchHistory(keyword)
            vm.run<List<Place>>(onDone = {
                if (searchGeneration == generation && !editing) {
                    results = it
                    if (it.isEmpty()) vm.toast("未找到相关位置，尝试使用不同的关键词搜索")
                }
            }) { search(keyword, camera.target) }
        }
    }
    // Road routes: each section between tapped points is snapped to roads in the background.
    val router = vm.engine.router
    val profile = s.settings.roadProfile
    var roadPath by remember { mutableStateOf<List<LatLon>?>(null) }
    var roadError by remember { mutableStateOf<String?>(null) }
    var roadBusy by remember { mutableStateOf(false) }
    val waypoints = draft.toList()
    LaunchedEffect(waypoints, profile, editing) {
        roadError = null
        if (!editing || !profile.road || waypoints.size < 2) {
            roadPath = null; roadBusy = false; return@LaunchedEffect
        }
        roadBusy = true
        delay(300)
        try {
            val sections = withContext(Dispatchers.IO) { waypoints.zipWithNext { a, b -> router.section(profile, a, b) } }
            roadPath = RoadRouter.join(sections)
            roadBusy = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            roadPath = null; roadError = e.message ?: "道路规划失败"; roadBusy = false
        }
    }
    val tipDismissed = s.settings.mapTipDismissed
    val currentRoute = s.currentRoute
    val road = profile.road && roadPath != null
    val editRoute = if (road) roadPath!! else waypoints
    val editAnchors = if (road) waypoints else emptyList()
    val editLength = remember(editRoute) { Geo.routeLength(editRoute) }

    // Reverse-geocode the selected point when a map key is configured.
    LaunchedEffect(selected, s.settings.privacyAccepted) {
        val generation = ++addressGeneration
        val p = selected ?: return@LaunchedEffect
        if (s.settings.privacyAccepted)
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
        if (vectorMap) VectorMap(
            modifier = Modifier.fillMaxSize(), virtual = r.position, simulating = r.enabled,
            selected = if (editing) null else selected, route = if (editing) editRoute else currentRoute,
            routeEditing = editing, anchors = if (editing) editAnchors else emptyList(),
            online = s.settings.privacyAccepted, dark = isDark(s.settings.theme), source = s.settings.mapSource,
            camera = camera, center = centerRequest,
            onBearingChanged = { mapBearing = it },
            onTap = { p -> if (editing) draft.add(p) else select(p) },
            onLongPress = { p -> if (!editing) select(p) },
        ) else OsmMap(
            modifier = Modifier.fillMaxSize(),
            virtual = r.position,
            simulating = r.enabled,
            selected = if (editing) null else selected,
            route = if (editing) editRoute else currentRoute,
            routeEditing = editing,
            anchors = if (editing) editAnchors else emptyList(),
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
                    Surface(shape = CircleShape, color = palette.blue, shadowElevation = 8.dp) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Route, null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(12.dp))
                            Text("路线编辑 · 点击地图依次添加点位", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else {
                    SearchBar(
                        query = query,
                        onQuery = { query = it; searchGeneration++; results = emptyList() },
                        onSearch = { doSearch(query) },
                        onClear = { query = ""; searchGeneration++; results = emptyList() },
                    )
                }
            }
            AnimatedVisibility(query.isEmpty() && results.isEmpty() && searchHistory.isNotEmpty() && !editing) {
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp), elevation = 6.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = "搜索历史",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            searchHistory.forEach { item ->
                                SuggestionChip(
                                    onClick = {
                                        query = item
                                        doSearch(item)
                                    },
                                    label = { Text(item, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                                    shape = CircleShape,
                                    border = null,
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = palette.fill,
                                    ),
                                )
                            }
                        }
                        IconButton(onClick = clearSearchHistory, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.Close, "清除历史", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            AnimatedVisibility(!tipDismissed && !editing && results.isEmpty()) {
                MapTip(
                    onSwitch = { mapMenu = true },
                    onKey = { keyDialog = true },
                    onClose = { vm.run { store.updateSettings { it.copy(mapTipDismissed = true) } } },
                )
            }
            AnimatedVisibility(results.isNotEmpty() && !editing) {
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().glass(RoundedCornerShape(26.dp))) {
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
            // Centred between the top bar and the bottom card, so neither covers a tool.
            Modifier.align(Alignment.CenterEnd).statusBarsPadding()
                .padding(top = 72.dp, end = 12.dp, bottom = with(density) { bottomCardHeight.toDp() }),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MapCompass(
                bearing = mapBearing,
                onClick = {
                    if (abs(signedBearing(mapBearing)) > 1.0) {
                        onCenter(CenterRequest(camera.target, camera.zoom, bearing = 0.0))
                        vm.toast("已回正正北")
                    } else {
                        vm.toast("当前已指向正北（0°）")
                    }
                },
            )
            ToolGroup {
                Box {
                    MapTool(Icons.Rounded.Layers, "地图图层：${s.settings.mapSource.label}") { mapMenu = true }
                    DropdownMenu(expanded = mapMenu, onDismissRequest = { mapMenu = false }) {
                        MapSource.values().forEach { source ->
                            DropdownMenuItem(text = { Text(source.menuLabel) },
                                trailingIcon = { if (source == s.settings.mapSource) Icon(Icons.Rounded.Check, "已选择") },
                                onClick = {
                                    mapMenu = false
                                    if (source != s.settings.mapSource) vm.run("已切换至${source.label}") {
                                        updateSettings { it.copy(mapSource = source) }
                                    }
                                })
                        }
                    }
                }
            }
            ToolGroup {
                MapTool(Icons.Rounded.PinDrop, "回到虚拟位置") { onCenter(CenterRequest(r.position)) }
                ToolDivider()
                MapTool(Icons.Rounded.MyLocation, "真实位置（先停止模拟）") {
                    vm.run<LatLon>(onDone = { select(it); onCenter(CenterRequest(it, 16.0)) }) { deviceLocation() }
                }
            }
            ToolGroup {
                MapTool(Icons.Rounded.Route, "路线编辑", active = editing) {
                    if (!editing) { draft.clear(); selected = null; searchGeneration++; results = emptyList(); focus.clearFocus() }
                    editing = !editing
                }
                ToolDivider()
                MapTool(Icons.Rounded.SportsEsports, "摇杆", active = r.joystick) {
                    vm.run(if (r.joystick) "摇杆悬浮窗已关闭" else "摇杆服务已启动") { joystick(!r.joystick) }
                }
            }
            ToolGroup {
                MapTool(Icons.Rounded.Add, "放大") {
                    val next = (camera.zoom + 1.0).coerceAtMost(19.0)
                    camera.zoom = next
                    onCenter(CenterRequest(camera.target, next))
                }
                ToolDivider()
                MapTool(Icons.Rounded.Remove, "缩小") {
                    val next = (camera.zoom - 1.0).coerceAtLeast(3.0)
                    camera.zoom = next
                    onCenter(CenterRequest(camera.target, next))
                }
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
                    length = editLength,
                    profile = profile,
                    roadStatus = when {
                        profile == RoadProfile.FLY -> "点与点之间直线飞行，按飞行速度播放"
                        !profile.road -> null
                        draft.size < 2 -> "点选途经点，路线会沿${router.source}道路连接"
                        roadBusy -> "正在沿道路规划…"
                        roadError != null -> roadError
                        else -> "已沿${router.source}道路连接"
                    },
                    roadFailed = roadError != null,
                    onProfile = { p -> vm.run { store.updateSettings { it.copy(roadProfile = p) } } },
                    onUndo = { if (draft.isNotEmpty()) draft.removeAt(draft.lastIndex) },
                    onClear = { draft.clear() },
                    onSave = {
                        when {
                            draft.size < 2 -> vm.toast("请至少选择两个路线点")
                            profile.road && roadBusy -> vm.toast("道路还在规划，请稍候")
                            profile.road && roadPath == null -> vm.toast(roadError ?: "道路规划失败，可改用直线")
                            else -> routeDialog = true
                        }
                    },
                    onClose = { editing = false; draft.clear() },
                )
                1 -> if (sel != null) PreviewCard(
                    point = sel,
                    address = address,
                    distance = Geo.distance(sel, r.position),
                    running = r.enabled,
                    onClose = { selected = null },
                    onSetVirtual = {
                        vm.run(if (r.enabled) "位置切换成功" else "已设置为当前虚拟位置", onDone = { _: Unit -> selected = null }) { setLocation(sel) }
                    },
                    onStart = { vm.run("模拟定位已开启", onDone = { _: Unit -> selected = null }) { startPoint(sel) } },
                    onRouteTo = {
                        draft.clear()
                        draft.add(r.position)
                        draft.add(sel)
                        selected = null
                        editing = true
                    },
                    onSave = { saveDialog = address },
                    onCopyCoord = { vm.toast("位置坐标已复制到剪贴板") },
                )
                else -> StatusCard(s, onToggle = {
                    if (r.enabled) vm.run("模拟定位已关闭") { stop() }
                    else vm.run("模拟定位已开启") { if (r.routeMode && currentRoute.size >= 2) startRoute() else startPoint() }
                }, onPause = { vm.run(if (r.routeFinished) "路线重新开始" else if (r.paused) "路线模拟继续" else "路线模拟已暂停") { pauseRoute(!r.paused) } },
                    onOpenSetup = onOpenSetup,
                    onCopyCoord = { vm.toast("虚拟坐标已复制到剪贴板") })
            }
        }

        Text(
            s.settings.mapSource.attribution,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF555B6E),
            modifier = Modifier.align(Alignment.BottomStart).padding(contentPadding).padding(start = 4.dp, bottom = 2.dp)
                .background(Color(0x99FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
        )
    }

    if (keyDialog) MapKeyDialog(configured = s.mapKeyConfigured, onDismiss = { keyDialog = false }, onMessage = vm::toast,
        onTest = { vm.run<String>(onDone = vm::toast) { checkMapKey() } },
        onSave = { key ->
            vm.run(if (key.isEmpty()) "已清除高德 Key" else "高德 Key 已保存，搜索和地址将使用高德数据") {
                setMapKey(key)
            }
        })

    saveDialog?.let { suggestion ->
        val p = selected
        InputDialog("添加到历史", initial = suggestion.ifEmpty { "未命名位置" }, label = "名称",
            hint = if (suggestion.isNotEmpty()) "名称建议：$suggestion" else null,
            onDismiss = { saveDialog = null }) { name ->
            if (p != null) vm.run<String>("位置已成功添加到历史记录") { savePlace(name, p, address) }
        }
    }
    if (routeDialog) {
        val points = editRoute
        val via = waypoints
        val kind = profile
        val title = when {
            kind == RoadProfile.STRAIGHT -> "路线 ${via.size} 点"
            else -> "${kind.label}路线 ${formatDistance(editLength)}"
        }
        InputDialog("保存路线", initial = title, label = "名称",
            hint = (when {
                kind == RoadProfile.FLY -> "飞行 · ${via.size} 个途经点"
                !kind.road -> "共 ${via.size} 个点"
                else -> "沿道路${kind.label} · ${via.size} 个途经点"
            }) + " · ${formatDistance(editLength)}路线",
            onDismiss = { routeDialog = false }) { name ->
            vm.run<String>("路线保存成功", onDone = { editing = false; draft.clear() }) { saveRoute(name, points, via, kind) }
        }
    }
}

/** Small dismissible hint for when the map stays blank (blocked tile server, network, region). */
@Composable
private fun MapTip(onSwitch: () -> Unit, onKey: () -> Unit, onClose: () -> Unit) {
    val link = MaterialTheme.typography.labelMedium
    Box(Modifier.padding(top = 8.dp).glass(CircleShape, elevation = 6.dp)) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("地图不显示？", style = link, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("换个图层", style = link, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onSwitch).padding(horizontal = 4.dp, vertical = 6.dp))
            Text("·", style = link, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("填写高德 Key", style = link, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onKey).padding(horizontal = 4.dp, vertical = 6.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "关闭提示，不再显示", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    Box(Modifier.glass(CircleShape)) {
        TextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("搜索地点、地址或坐标", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
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

/** Rounded floating group holding one or more map tools. */
@Composable
private fun ToolGroup(content: @Composable () -> Unit) {
    Column(Modifier.glass(RoundedCornerShape(24.dp)).padding(vertical = 2.dp)) { content() }
}

@Composable
private fun ToolDivider() =
    HorizontalDivider(Modifier.width(48.dp).padding(horizontal = 12.dp), thickness = 0.5.dp, color = palette.separator)

@Composable
private fun MapTool(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    iconModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(48.dp).padding(3.dp).clip(CircleShape).clickable(onClick = onClick)
            .background(if (active) palette.blue else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, modifier = iconModifier.size(22.dp),
            tint = if (active) Color.White else MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Compass dial widget: 3D beveled red/slate needle pointing to True North.
 * Rotates smoothly in real time with map bearing; tap to reset bearing to True North.
 */
@Composable
private fun MapCompass(
    bearing: Double,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val signed = signedBearing(bearing)
    // Unwrapped needle angle: crossing north (359° → 1°) turns 2°, not a full circle.
    val needle = remember { floatArrayOf(0f) }
    val delta = ((-signed - needle[0]) % 360.0 + 540.0) % 360.0 - 180.0
    needle[0] += delta.toFloat()
    val animatedBearing by animateFloatAsState(
        targetValue = needle[0],
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "compass",
    )
    val isFacingNorth = abs(signed) < 1.0

    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        shape = CircleShape,
        color = Color.Transparent,
        modifier = modifier.size(48.dp).glass(CircleShape),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .size(28.dp)
                    .rotate(animatedBearing)
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val halfW = size.width * 0.18f
                val len = size.height * 0.44f

                // North Needle - 3D beveled red
                val northLeft = Path().apply {
                    moveTo(cx, cy - len)
                    lineTo(cx - halfW, cy)
                    lineTo(cx, cy)
                    close()
                }
                drawPath(northLeft, color = Color(0xFFDC3545))

                val northRight = Path().apply {
                    moveTo(cx, cy - len)
                    lineTo(cx + halfW, cy)
                    lineTo(cx, cy)
                    close()
                }
                drawPath(northRight, color = Color(0xFFFF5252))

                // South Needle - 3D beveled slate
                val southLeft = Path().apply {
                    moveTo(cx, cy + len)
                    lineTo(cx - halfW, cy)
                    lineTo(cx, cy)
                    close()
                }
                drawPath(southLeft, color = Color(0xFF8A93A6))

                val southRight = Path().apply {
                    moveTo(cx, cy + len)
                    lineTo(cx + halfW, cy)
                    lineTo(cx, cy)
                    close()
                }
                drawPath(southRight, color = Color(0xFFBCC5D4))

                // Center pivot
                drawCircle(color = Color.White, radius = halfW * 0.72f, center = Offset(cx, cy))
                drawCircle(color = Color(0xFF1E2330), radius = halfW * 0.42f, center = Offset(cx, cy))
            }

            // Fixed North "N" mark at the top of dial
            Text(
                text = "N",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = if (isFacingNorth) Color(0xFFDC3545) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun BottomCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize().glass(RoundedCornerShape(32.dp), elevation = 18.dp).padding(20.dp)) { content() }
}

/** Idle / running panel: state and position on the left, one round start-stop button on the right. */
@Composable
private fun StatusCard(s: AppState, onToggle: () -> Unit, onPause: () -> Unit, onOpenSetup: () -> Unit, onCopyCoord: () -> Unit) {
    val r = s.runtime
    val haptic = LocalHapticFeedback.current
    val ready = s.settings.privacyAccepted && s.caps.preciseLocation &&
            (if (s.settings.xposed) s.caps.hookActive else s.caps.mockSelected)
    val live = MaterialTheme.colorScheme.tertiary
    BottomCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(
                        when {
                            r.enabled && r.paused -> app.geoshift.ui.theme.Warning
                            r.enabled -> live
                            r.error.isNotEmpty() -> Danger
                            else -> MaterialTheme.colorScheme.outline
                        }))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            r.enabled && r.paused -> "路线已暂停"
                            r.enabled && r.routeMode -> "路线模拟中"
                            r.enabled -> "正在模拟"
                            r.error.isNotEmpty() -> "模拟启动失败"
                            else -> "未在模拟"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (r.enabled) live else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(4.dp))
                CoordinateRow(r.position.lat, r.position.lon, style = MaterialTheme.typography.titleMedium, onCopied = onCopyCoord)
                Text(
                    when {
                        r.enabled && r.routeMode -> String.format(java.util.Locale.ROOT, "%s · %.0f km/h · %d%%",
                            r.routeName.ifEmpty { "未命名路线" }, r.speed * 3.6, (r.progress * 100).toInt())
                        r.enabled -> "所有定位来源都在这个点"
                        else -> "点地图选点，或直接在此处开始"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (r.enabled && r.routeMode) {
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPause()
                    },
                    shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(when { r.routeFinished -> Icons.Rounded.Replay; r.paused -> Icons.Rounded.PlayArrow; else -> Icons.Rounded.Pause },
                            when { r.routeFinished -> "重新开始"; r.paused -> "继续"; else -> "暂停" })
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
                shape = CircleShape, shadowElevation = 2.dp,
                color = if (r.enabled) Danger else MaterialTheme.colorScheme.primary,
                contentColor = Color.White, modifier = Modifier.size(60.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (r.enabled) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (r.enabled) "停止模拟" else "开始模拟",
                        Modifier.size(30.dp))
                }
            }
        }
        if (r.enabled && r.routeMode) {
            Spacer(Modifier.size(12.dp))
            LinearProgressIndicator(progress = { r.progress.toFloat() }, modifier = Modifier.fillMaxWidth().clip(CircleShape),
                color = live, drawStopIndicator = {})
        }
        if (r.error.isNotEmpty() && !r.enabled) {
            Spacer(Modifier.size(8.dp))
            Text(r.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (!ready) {
            Spacer(Modifier.size(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onOpenSetup).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("还有权限或模块未配置", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                Text("去设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PreviewCard(
    point: LatLon, address: String, distance: Double, running: Boolean,
    onClose: () -> Unit, onSetVirtual: () -> Unit, onStart: () -> Unit, onRouteTo: () -> Unit,
    onSave: () -> Unit, onCopyCoord: () -> Unit,
) {
    BottomCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("选中的位置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(address.ifEmpty { "未知地址" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoordinateRow(point.lat, point.lon, style = MaterialTheme.typography.bodySmall, onCopied = onCopyCoord)
                    Text(" · 距虚拟位置 ${formatDistance(distance)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭") }
        }
        Spacer(Modifier.size(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Button(onClick = onSetVirtual, modifier = Modifier.weight(1f)) { Text("设为虚拟位置", maxLines = 1) }
            } else {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("开始模拟", maxLines = 1)
                }
                FilledTonalButton(onClick = onSetVirtual, modifier = Modifier.weight(1f)) { Text("设为位置", maxLines = 1) }
            }
            FilledTonalButton(onClick = onRouteTo, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(Icons.Rounded.Directions, "规划去这里的路线", Modifier.size(20.dp))
            }
            OutlinedButton(onClick = onSave, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Rounded.Add, "添加到历史", Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteEditorCard(
    count: Int, length: Double, profile: RoadProfile, roadStatus: String?, roadFailed: Boolean,
    onProfile: (RoadProfile) -> Unit, onUndo: () -> Unit, onClear: () -> Unit, onSave: () -> Unit, onClose: () -> Unit,
) {
    BottomCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("路线编辑", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("$count 个点位 · ${formatDistance(length)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "退出编辑") }
        }
        // Five choices do not fit a narrow phone; let the row scroll instead of clipping.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoadProfile.entries.forEach { p ->
                FilterChip(selected = p == profile, onClick = { onProfile(p) }, label = { Text(p.label) })
            }
        }
        if (roadStatus != null) Text(roadStatus, style = MaterialTheme.typography.bodySmall,
            color = if (roadFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(8.dp))
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

/** Map bearing (0..360) as -180..180, so 359° counts as 1° west of north. */
private fun signedBearing(bearing: Double): Double = ((bearing % 360.0) + 540.0) % 360.0 - 180.0

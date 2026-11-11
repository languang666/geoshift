package app.geoshift.ui

import android.app.Activity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.geoshift.ui.components.glass
import app.geoshift.ui.theme.palette
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.geoshift.R
import app.geoshift.ui.AppState
import app.geoshift.ui.history.HistoryScreen
import app.geoshift.ui.map.CenterRequest
import app.geoshift.ui.map.MapScreen
import app.geoshift.ui.mock.CellScreen
import app.geoshift.ui.mock.MockScreen
import app.geoshift.ui.mock.WifiScreen
import app.geoshift.ui.settings.SettingsScreen
import app.geoshift.ui.setup.SetupScreen
import app.geoshift.ui.setup.SystemTarget
import app.geoshift.ui.theme.GeoshiftTheme
import app.geoshift.data.ThemeMode
import app.geoshift.ui.theme.isDark

private enum class Tab(val label: String, val icon: ImageVector) {
    MAP("地图", Icons.Rounded.Map), MOCK("控制台", Icons.Rounded.Tune),
    HISTORY("收藏", Icons.Rounded.Bookmarks), SETTINGS("设置", Icons.Rounded.Settings),
}

private enum class Page { MAIN, WIFI, CELL, SETUP }

@Composable
fun GeoshiftApp(vm: AppViewModel, onOpen: (SystemTarget) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    val state by vm.state.collectAsState()
    val s = state
    GeoshiftTheme(s?.settings?.theme ?: ThemeMode.SYSTEM) {
        val dark = isDark(s?.settings?.theme ?: ThemeMode.SYSTEM)
        val view = LocalView.current
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (s == null) Splash() else Root(s, vm, onOpen, onExport, onImport)
        }
        val frameworkHelp by vm.frameworkHelp.collectAsState()
        if (frameworkHelp) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = vm::hideFrameworkHelp,
                title = { Text("打开 Xposed 框架管理器") },
                text = {
                    Text("Vector 等框架的管理器隐藏在系统里，不能被其他应用直接打开，可以用以下任一方式进入：\n\n" +
                        "1. 下拉通知栏，点击框架的常驻通知；\n" +
                        "2. 打开拨号盘，输入 *#*#5776733#*#*；\n" +
                        "3. 在管理器设置中创建桌面快捷方式。\n\n" +
                        "进入后在「模块」里启用 Geoshift（作用域已固定，无需勾选），然后重启手机。")
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = vm::hideFrameworkHelp) { Text("知道了") } },
            )
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_portal), null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(72.dp))
            Spacer(Modifier.size(20.dp))
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun Root(s: AppState, vm: AppViewModel, onOpen: (SystemTarget) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    var onboarding by rememberSaveable { mutableStateOf(!s.settings.privacyAccepted) }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
    var historyTab by rememberSaveable { mutableIntStateOf(0) }
    var center by remember { mutableStateOf<CenterRequest?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    if (onboarding || !s.settings.privacyAccepted) {
        Box(Modifier.fillMaxSize()) {
            SetupScreen(s, vm, onOpen, onBack = null, onDone = { onboarding = false })
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        return
    }

    BackHandler(enabled = page != Page.MAIN || tab != Tab.MAP) {
        if (page != Page.MAIN) page = Page.MAIN else tab = Tab.MAP
    }

    AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") { current ->
        when (current) {
            Page.WIFI -> Box(Modifier.fillMaxSize()) {
                WifiScreen(s, vm, onBack = { page = Page.MAIN })
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            Page.CELL -> Box(Modifier.fillMaxSize()) {
                CellScreen(s, vm, onBack = { page = Page.MAIN })
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            Page.SETUP -> Box(Modifier.fillMaxSize()) {
                SetupScreen(s, vm, onOpen, onBack = { page = Page.MAIN }, onDone = { page = Page.MAIN })
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            Page.MAIN -> {
                val openSetup = { page = Page.SETUP }
                val hazeState = rememberHazeState()
                // Content runs edge to edge and scrolls under the floating tab bar.
                val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val padding = PaddingValues(bottom = nav + TAB_BAR_HEIGHT + 16.dp)
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        modifier = Modifier.hazeSource(hazeState),
                        targetState = tab,
                        transitionSpec = {
                            fadeIn(animationSpec = androidx.compose.animation.core.tween(180)) togetherWith
                                fadeOut(animationSpec = androidx.compose.animation.core.tween(120))
                        },
                        label = "tabTransition",
                    ) { currentTab ->
                        when (currentTab) {
                            Tab.MAP -> MapScreen(s, vm, padding, center, onCenter = { center = it }, onOpenSetup = openSetup)
                            Tab.HISTORY -> HistoryScreen(s, vm, padding, historyTab,
                                onShowOnMap = { p -> center = CenterRequest(p, 16.0); tab = Tab.MAP },
                                onGoMock = { tab = Tab.MOCK }, onExport = onExport)
                            Tab.MOCK -> MockScreen(s, vm, padding, onOpenSetup = openSetup,
                                onOpenWifi = { page = Page.WIFI }, onOpenCell = { page = Page.CELL },
                                onPickRoute = { historyTab = 1; tab = Tab.HISTORY })
                            Tab.SETTINGS -> SettingsScreen(s, vm, padding, onOpenSetup = openSetup, onExport = onExport, onImport = onImport)
                        }
                    }
                    // Translucent status-bar strip so scrolled content never sits under the clock (the map stays full-bleed).
                    if (tab != Tab.MAP) Box(
                        Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f)),
                    )
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = nav + TAB_BAR_HEIGHT + 20.dp))
                    TabBar(tab, s.runtime.enabled, hazeState, isDark(s.settings.theme), Modifier.align(Alignment.BottomCenter).padding(bottom = nav + 8.dp)) { tab = it }
                }
            }
        }
    }
}

private val TAB_BAR_HEIGHT = 64.dp

/** Floating glass capsule with a draggable, spring-animated selection. */
@Composable
private fun TabBar(selected: Tab, simulating: Boolean, hazeState: dev.chrisbanes.haze.HazeState, dark: Boolean, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    val p = palette
    val glassStyle = GlassStyle.regular.then {
        tint(if (dark) Color.Black.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.22f))
        shape(CircleShape)
    }
    BoxWithConstraints(
        modifier.padding(horizontal = 22.dp).fillMaxWidth().height(TAB_BAR_HEIGHT)
            .hazeGlass(input = HazeInput.Sources(hazeState), style = glassStyle).padding(5.dp),
    ) {
        val density = LocalDensity.current
        val itemWidth = maxWidth / Tab.entries.size
        val itemWidthPx = with(density) { itemWidth.toPx() }
        var dragPosition by remember { mutableStateOf<Float?>(null) }
        val position by animateFloatAsState(
            targetValue = dragPosition ?: selected.ordinal.toFloat(),
            animationSpec = spring(dampingRatio = 0.68f, stiffness = 500f), label = "tab lens",
        )
        Box(
            Modifier.offset { IntOffset((position * itemWidthPx).roundToInt(), 0) }
                .size(width = itemWidth, height = TAB_BAR_HEIGHT - 10.dp)
                .hazeGlass(input = HazeInput.Sources(hazeState), style = GlassStyle.regular.then {
                    shape(CircleShape)
                    tint(if (dark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.42f))
                }),
        )
        Row(
            Modifier.fillMaxSize().pointerInput(itemWidthPx, selected) {
                detectHorizontalDragGestures(
                    onDragStart = { dragPosition = selected.ordinal.toFloat() },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragPosition = ((dragPosition ?: selected.ordinal.toFloat()) + amount / itemWidthPx)
                            .coerceIn(0f, (Tab.entries.size - 1).toFloat())
                    },
                    onDragEnd = {
                        dragPosition?.let { onSelect(Tab.entries[it.roundToInt()]) }
                        dragPosition = null
                    },
                    onDragCancel = { dragPosition = null },
                )
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t ->
                val on = t == selected
                val tint = if (on) p.blue else MaterialTheme.colorScheme.onSurface
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(t.icon, null, tint = tint, modifier = Modifier.size(25.dp))
                        if (t == Tab.MOCK && simulating) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp)
                            .size(9.dp).clip(CircleShape).background(p.green))
                    }
                    Text(t.label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1)
                }
            }
        }
    }
}

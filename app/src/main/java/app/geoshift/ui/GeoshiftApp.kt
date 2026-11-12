package app.geoshift.ui

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
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
            Icon(painterResource(R.drawable.ic_logo), null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(72.dp))
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

/**
 * Soft drop shadow drawn only outside the capsule. A regular shadow sits under the whole shape and
 * shows through translucent glass as a grey band.
 */
private fun Modifier.capsuleShadow(radius: Dp, offsetY: Dp, color: Color): Modifier = drawBehind {
    val corner = size.height / 2
    val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner))) }
    clipPath(outline, ClipOp.Difference) {
        drawIntoCanvas { canvas ->
            val paint = Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                this.color = color.toArgb()
                setShadowLayer(radius.toPx(), 0f, offsetY.toPx(), color.toArgb())
            }
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, corner, corner, paint)
        }
    }
}

/** Floating glass capsule with a draggable, spring-animated selection. */
@Composable
private fun TabBar(selected: Tab, simulating: Boolean, hazeState: dev.chrisbanes.haze.HazeState, dark: Boolean, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    val p = palette
    // Built once per theme: a new style object on every recomposition would make Haze redo its setup.
    val barStyle = remember(dark) {
        GlassStyle.regular.then {
            tint(if (dark) Color(0xFF191726).copy(alpha = 0.55f) else Color(0xFFF7F8FE).copy(alpha = 0.65f))
            shape(CircleShape)
        }
    }
    val lensStyle = remember(dark) {
        GlassStyle.regular.then {
            shape(CircleShape)
            tint(if (dark) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.65f))
        }
    }
    BoxWithConstraints(
        modifier.padding(horizontal = 22.dp).widthIn(max = 360.dp).fillMaxWidth().height(TAB_BAR_HEIGHT)
            .capsuleShadow(14.dp, 4.dp, Color.Black.copy(alpha = if (dark) 0.45f else 0.16f))
            .hazeGlass(input = HazeInput.Sources(hazeState), style = barStyle).padding(5.dp),
    ) {
        val itemWidth = maxWidth / Tab.entries.size
        val itemWidthPx = with(LocalDensity.current) { itemWidth.toPx() }
        val last = (Tab.entries.size - 1).toFloat()
        val scope = rememberCoroutineScope()
        val select by rememberUpdatedState(onSelect)
        // The lens follows the finger exactly while dragged and springs to the selected tab otherwise.
        val lens = remember { Animatable(selected.ordinal.toFloat()) }
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(selected, dragging) {
            if (!dragging) lens.animateTo(selected.ordinal.toFloat(), spring(dampingRatio = 0.68f, stiffness = 500f))
        }
        val lensScale by animateFloatAsState(
            targetValue = if (dragging) 1.12f else 1f,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f), label = "lens press",
        )
        Box(
            Modifier.offset { IntOffset((lens.value * itemWidthPx).roundToInt(), 0) }
                .size(width = itemWidth, height = TAB_BAR_HEIGHT - 10.dp)
                .graphicsLayer { scaleX = lensScale; scaleY = lensScale }
                .hazeGlass(input = HazeInput.Sources(hazeState), style = lensStyle),
        )
        Row(
            Modifier.fillMaxSize().pointerInput(itemWidthPx) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { lens.snapTo((lens.value + amount / itemWidthPx).coerceIn(0f, last)) }
                    },
                    onDragEnd = {
                        select(Tab.entries[lens.value.roundToInt().coerceIn(0, Tab.entries.size - 1)])
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
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
                        // The console tab carries a green dot while anything is being simulated.
                        if (t == Tab.MOCK && simulating) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp)
                            .size(9.dp).clip(CircleShape).background(p.green))
                    }
                    Text(t.label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1)
                }
            }
        }
    }
}

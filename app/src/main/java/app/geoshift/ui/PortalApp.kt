package app.geoshift.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NearMe
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
import app.geoshift.data.PortalState
import app.geoshift.ui.history.HistoryScreen
import app.geoshift.ui.map.CenterRequest
import app.geoshift.ui.map.MapScreen
import app.geoshift.ui.mock.CellScreen
import app.geoshift.ui.mock.MockScreen
import app.geoshift.ui.mock.WifiScreen
import app.geoshift.ui.settings.SettingsScreen
import app.geoshift.ui.setup.SetupScreen
import app.geoshift.ui.setup.SystemTarget
import app.geoshift.ui.theme.PortalTheme
import app.geoshift.ui.theme.isDark

private enum class Tab(val label: String, val icon: ImageVector) {
    MAP("地图", Icons.Rounded.Map), HISTORY("历史", Icons.Rounded.History),
    MOCK("模拟", Icons.Rounded.NearMe), SETTINGS("设置", Icons.Rounded.Settings),
}

private enum class Page { MAIN, WIFI, CELL, SETUP }

@Composable
fun PortalApp(vm: PortalViewModel, onOpen: (SystemTarget) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    val state by vm.state.collectAsState()
    val s = state
    PortalTheme(s?.settings?.theme ?: "system") {
        val dark = isDark(s?.settings?.theme ?: "system")
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
        val lsposedHelp by vm.lsposedHelp.collectAsState()
        if (lsposedHelp) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = vm::hideLsposedHelp,
                title = { Text("打开 LSPosed / Vector 管理器") },
                text = {
                    Text("LSPosed 的维护版本已改名为 Vector，两者用法相同。管理器隐藏在系统里，不能被其他应用直接打开，可以用以下任一方式进入：\n\n" +
                        "1. 下拉通知栏，点击 LSPosed / Vector 的常驻通知；\n" +
                        "2. 打开拨号盘，输入 *#*#5776733#*#*；\n" +
                        "3. 在管理器设置中创建桌面快捷方式。\n\n" +
                        "进入后在「模块」里启用 Geoshift，作用域勾选「系统框架」和「电话服务」，然后重启手机。")
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = vm::hideLsposedHelp) { Text("知道了") } },
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
private fun Root(s: PortalState, vm: PortalViewModel, onOpen: (SystemTarget) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
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
            Page.MAIN -> Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(selected = tab == t, onClick = { tab = t },
                                icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                        }
                    }
                },
            ) { padding ->
                val openSetup = { page = Page.SETUP }
                Box(Modifier.fillMaxSize()) {
                when (tab) {
                    Tab.MAP -> MapScreen(s, vm, padding, center, onCenter = { center = it }, onOpenSetup = openSetup)
                    Tab.HISTORY -> HistoryScreen(s, vm, padding, historyTab,
                        onShowOnMap = { p -> center = CenterRequest(p, 16.0); tab = Tab.MAP },
                        onGoMock = { tab = Tab.MOCK }, onExport = onExport)
                    Tab.MOCK -> MockScreen(s, vm, padding, onOpenSetup = openSetup,
                        onOpenWifi = { page = Page.WIFI }, onOpenCell = { page = Page.CELL },
                        onPickRoute = { historyTab = 1; tab = Tab.HISTORY })
                    Tab.SETTINGS -> SettingsScreen(s, vm, padding, onOpenSetup = openSetup, onExport = onExport, onImport = onImport)
                }
                // Opaque status-bar strip so scrolled content never sits under the clock (the map stays full-bleed).
                if (tab != Tab.MAP) Box(
                    Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f)),
                )
                }
            }
        }
    }
}

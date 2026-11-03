package app.geoshift

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.geoshift.ui.PortalApp
import app.geoshift.ui.PortalViewModel
import app.geoshift.ui.setup.SystemTarget
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: PortalViewModel by viewModels()

    private var permissionAskedAt = 0L

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.refresh()
        // An instant denial means Android did not show the dialog (denied before): send the user to app settings.
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty() && SystemClock.elapsedRealtime() - permissionAskedAt < 600) {
            vm.toast("系统未弹出授权框，请在「权限」中手动允许")
            start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun ask(vararg names: String) {
        permissionAskedAt = SystemClock.elapsedRealtime()
        permissions.launch(arrayOf(*names))
    }

    private val exportDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.run("已导出位置、路线和预设，不含地图 Key。") { exportTo(uri) }
    }

    private val importDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.run<Int>(onDone = { vm.toast("备份已导入 $it 条记录；同 ID 记录已更新。") }) { importFrom(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = "Geoshift/${BuildConfig.VERSION_NAME} (Android; app.geoshift)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        setContent {
            PortalApp(
                vm = vm,
                onOpen = ::open,
                onExport = { exportDoc.launch("geoshift-backup-${System.currentTimeMillis()}.json") },
                onImport = { importDoc.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        vm.foreground = true
        vm.refresh()
    }

    override fun onPause() {
        vm.foreground = false
        super.onPause()
    }

    private fun open(target: SystemTarget) {
        when (target) {
            SystemTarget.LOCATION_PERMISSION -> ask(
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
            SystemTarget.NOTIFICATION_PERMISSION ->
                if (Build.VERSION.SDK_INT >= 33) ask(Manifest.permission.POST_NOTIFICATIONS)
            SystemTarget.WIFI_PERMISSION ->
                if (Build.VERSION.SDK_INT >= 33) ask(Manifest.permission.NEARBY_WIFI_DEVICES)
            SystemTarget.OVERLAY -> start(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            SystemTarget.MOCK_APP -> start(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            SystemTarget.LOCATION_SWITCH -> start(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            SystemTarget.LSPOSED -> openLsposed()
            SystemTarget.APP_DETAILS -> start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    /**
     * Old LSPosed ships a launcher app; current LSPosed hides its manager inside the system Shell
     * package. Try both, then explain the remaining ways in.
     */
    private fun openLsposed() {
        packageManager.getLaunchIntentForPackage("org.lsposed.manager")?.let { if (tryStart(it)) return }
        val parasitic = Intent(Intent.ACTION_MAIN)
            .addCategory("org.lsposed.manager.LAUNCH_MANAGER")
            .setComponent(ComponentName("com.android.shell", "com.android.shell.BugreportWarningActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(parasitic)) return
        vm.showLsposedHelp()
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent); true
    } catch (e: Exception) {
        false
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            vm.toast("无法打开此设置，请在系统设置中手动操作。")
        }
    }
}

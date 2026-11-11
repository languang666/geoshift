package app.geoshift.xposed

import app.geoshift.xposed.system.SystemSide
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Module entry (META-INF/xposed/java_init.list). Scope: system_server ("system"), the phone process
 * and the Bluetooth process. Ordinary apps get no hooks: system_server covers them.
 */
class Module : XposedModule() {
    private var process = ""
    private var installed = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        process = param.processName
        Log.xposed = this
        Diag.processName = process
        Diag.isSystemServer = param.isSystemServer
        if ((frameworkProperties and XposedInterface.PROP_CAP_REMOTE) == 0L)
            Log.w("framework has no remote preferences; Geoshift cannot receive its configuration")
        Sim.start(this)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (installed) return
        installed = true
        Log.i("system_server: ${frameworkName} ${frameworkVersion} API $apiVersion")
        SystemSide(Hooker(this), param.classLoader).install()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (installed) return
        val hooks = Hooker(this)
        when {
            process == "com.android.phone" -> { installed = true; PhoneSide(hooks, param.classLoader).install() }
            process in BLUETOOTH -> { installed = true; BluetoothSide(hooks, param.classLoader).install() }
        }
    }

    private companion object {
        /** The Bluetooth stack: AOSP / most ROMs, and the Google build on Pixel and the emulator. */
        val BLUETOOTH = setOf("com.android.bluetooth", "com.google.android.bluetooth")
    }
}

package app.geoshift.xposed.system

import android.os.Binder
import android.os.Build
import android.os.Bundle
import app.geoshift.core.LatLon
import app.geoshift.xposed.Bridge
import app.geoshift.xposed.Contexts
import app.geoshift.xposed.Diag
import app.geoshift.xposed.Hooker
import app.geoshift.xposed.Log
import app.geoshift.xposed.Reflect
import app.geoshift.xposed.Sim
import org.json.JSONObject

/**
 * Everything installed in system_server. Commands arrive through
 * LocationManager.sendExtraCommand(provider = "geoshift", command, extras):
 *   PING   (app only)        -> extras: active, reports (JSON), config (what the hooks see)
 *   MOVE   (app only)        <- extras: lat, lon, speed, bearing (joystick steps)
 *   REPORT (system uids)     <- extras: feature, report (diagnostics from the phone / Bluetooth process)
 * Every other caller gets the stock behaviour, so no ordinary app can tell the module is there.
 */
class SystemSide(private val hooks: Hooker, private val loader: ClassLoader) {
    @Volatile private var cachedUid = -1
    private val FEATURE = Regex("[a-z_]{1,30}")

    fun install() {
        Diag.isSystemServer = true
        Diag.processName = "system_server"
        val location = LocationHooks(hooks, loader)
        hookCommands(location)
        step("location_system", "系统定位") {
            check(Build.VERSION.SDK_INT >= 31) { "需要 Android 12 或更新版本" }
            location.install()
        }
        step("callbacks", "应用回调") { CallbackHooks(hooks, loader, location).install() }
        step("wifi", "Wi-Fi") { WifiHooks(hooks, loader, ::ownUid).install() }
    }

    private inline fun step(feature: String, label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.w("$label install", e)
            Diag.report(feature, false, "error", "${label}入口安装失败：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
        }
    }

    private fun ownUid(): Int {
        if (cachedUid < 0) cachedUid = try {
            Contexts.get()!!.packageManager.getApplicationInfo(Bridge.SELF, 0).uid
        } catch (_: Throwable) {
            return -2
        }
        return cachedUid
    }

    private fun hookCommands(location: LocationHooks) {
        val service = Reflect.firstClass(listOf("com.android.server.location.LocationManagerService",
            "com.android.server.LocationManagerService"), loader, "命令通道") ?: return
        hooks.declared(service, "sendExtraCommand") { m ->
            hooks.before(m) { call ->
                if (call.args.size < 3 || call.args[0] != Bridge.COMMAND) return@before
                val uid = Binder.getCallingUid()
                val own = uid == ownUid()
                if (!own && uid >= 10000) return@before // Stock behaviour for everyone else.
                call.thisObject?.let { location.attachService(it) }
                call.result = if (call.returnType == java.lang.Boolean.TYPE) true else null
                val extras = call.args[2] as? Bundle ?: return@before
                try {
                    handle(call.args[1].toString(), extras, own)
                    if (own && call.args[1] == "MOVE") location.schedule(0)
                } catch (e: Throwable) {
                    extras.putString("error", "${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    private fun handle(command: String, extras: Bundle, own: Boolean) {
        when (command) {
            "PING" -> if (own) {
                extras.putBoolean("active", true)
                extras.putString("reports", Diag.snapshot().toString())
                extras.putString("config", Sim.config.encode())
            }
            "SET" -> if (own) extras.getString("config")?.let { Sim.accept(it) }
            "MOVE" -> if (own) Sim.moveTo(LatLon(extras.getDouble("lat"), extras.getDouble("lon")),
                extras.getDouble("speed"), extras.getDouble("bearing"))
            "REPORT" -> {
                val feature = extras.getString("feature").orEmpty()
                if (feature.matches(FEATURE)) runCatching { Diag.reports[feature] = JSONObject(extras.getString("report") ?: "{}") }
            }
        }
    }
}

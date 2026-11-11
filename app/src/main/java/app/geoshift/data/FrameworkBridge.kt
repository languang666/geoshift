package app.geoshift.data

import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings.Global
import app.geoshift.core.LatLon
import app.geoshift.core.SimConfig
import app.geoshift.xposed.Bridge
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.FileOutputStream

/**
 * The app's side of the Xposed module. The framework hands the app a service binder when the module
 * is enabled; through it the app writes the simulation config to remote preferences, which the
 * framework pushes to every hooked process. Diagnostics and joystick steps go to system_server
 * directly (LocationManager.sendExtraCommand), which answers only this app.
 */
class FrameworkBridge(private val context: Context) : XposedServiceHelper.OnServiceListener {
    private val _service = MutableStateFlow<XposedService?>(null)
    private val _framework = MutableStateFlow<Framework?>(null)
    val framework: StateFlow<Framework?> = _framework.asStateFlow()

    private val _reports = MutableStateFlow<Map<String, Report>>(emptyMap())
    val reports: StateFlow<Map<String, Report>> = _reports.asStateFlow()

    /** elapsedRealtime of the last PING system_server answered. */
    @Volatile var seenAt = -1L
        private set
    val hookActive get() = seenAt >= 0 && SystemClock.elapsedRealtime() - seenAt < 60_000

    /** What the hooks last reported they act on (PING), for the diagnostics page. */
    @Volatile var hookConfig: SimConfig? = null
        private set

    private var uploadedRoutes = HashSet<String>()
    private var lastStable: SimConfig? = null
    private var lastWriteAt = 0L
    @Volatile private var pending: SimConfig? = null
    /** Called when the service (re)binds so the current config is written to it. */
    var onBind: (() -> Unit)? = null

    fun register() = XposedServiceHelper.registerListener(this)

    override fun onServiceBind(service: XposedService) {
        _service.value = service
        synchronized(this) { uploadedRoutes = HashSet(); lastStable = null }
        _framework.value = describe(service)
        onBind?.invoke()
    }

    override fun onServiceDied(service: XposedService) {
        if (_service.value === service) {
            _service.value = null
            _framework.value = null
        }
    }

    private fun describe(s: XposedService): Framework? = try {
        Framework(s.frameworkName, s.frameworkVersion, s.apiVersion, s.scope,
            remote = (s.frameworkProperties and XposedService.PROP_CAP_REMOTE) != 0L)
    } catch (_: Throwable) {
        null
    }

    fun refreshFramework() { _service.value?.let { _framework.value = describe(it) } }

    /** Asks the framework to add packages to the module scope (the user confirms in its manager). */
    fun requestScope(packages: List<String>, done: (String?) -> Unit) {
        val s = _service.value ?: return done("未连接到 Xposed 框架")
        s.requestScope(packages, object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) { refreshFramework(); done(null) }
            override fun onScopeRequestFailed(message: String) = done(message)
        })
    }

    fun bootCount(): Int = Global.getInt(context.contentResolver, Global.BOOT_COUNT, -1)

    /**
     * Writes the config for the hooks. While a route plays or the joystick moves, the position changes
     * all the time: route steps the hooks compute themselves, joystick steps go out as MOVE commands,
     * and the stored config is rewritten only when something else changed (or every few seconds).
     */
    @Synchronized fun push(config: SimConfig, routePoints: (String) -> List<LatLon>?, joystick: Boolean) {
        val service = _service.value ?: return
        val stable = config.copy(writtenAt = 0).let {
            if (it.route != null || joystick) it.copy(position = LatLon(0.0, 0.0), speed = 0.0, bearing = 0.0) else it
        }
        val now = SystemClock.elapsedRealtime()
        if (joystick && config.active) move(config.position, config.speed, config.bearing)
        if (stable == lastStable && (!joystick || now - lastWriteAt < 3000)) return
        try {
            config.route?.routeId?.let { id ->
                if (id !in uploadedRoutes) {
                    val points = routePoints(id) ?: fail("路线不存在")
                    service.openRemoteFile(Bridge.routeFile(id)).use { pfd ->
                        FileOutputStream(pfd.fileDescriptor).use { out ->
                            out.channel.truncate(0)
                            out.write(SimConfig.JSON.encodeToString(Bridge.ROUTE_SERIALIZER, points).toByteArray())
                        }
                    }
                    uploadedRoutes.add(id)
                }
            }
            val text = config.copy(writtenAt = now).encode()
            service.getRemotePreferences(Bridge.PREFS_GROUP).edit().putString(Bridge.KEY_CONFIG, text).apply()
            // Vector does not push preference changes into system_server; hand it the config directly.
            command("SET", Bundle().apply { putString("config", text) })
            lastStable = stable
            lastWriteAt = now
        } catch (e: Throwable) {
            pending = config
            throw UserError("写入 Xposed 配置失败：${e.javaClass.simpleName}。请确认框架支持 libxposed API 101+。")
        }
    }

    /** Joystick step straight to system_server. */
    fun move(p: LatLon, speed: Double, bearing: Double) {
        command("MOVE", Bundle().apply {
            putDouble("lat", p.lat); putDouble("lon", p.lon); putDouble("speed", speed); putDouble("bearing", bearing)
        })
    }

    /** Asks system_server for its reports. Returns whether it answered. */
    fun ping(): Boolean {
        val b = command("PING", Bundle()) ?: return false
        if (!b.getBoolean("active")) return false
        seenAt = SystemClock.elapsedRealtime()
        hookConfig = b.getString("config")?.let { runCatching { SimConfig.decode(it) }.getOrNull() }
        val json = runCatching { JSONObject(b.getString("reports") ?: "{}") }.getOrNull() ?: return true
        _reports.value = json.keys().asSequence().mapNotNull { key ->
            json.optJSONObject(key)?.let {
                key to Report(it.optBoolean("ok"), it.optLong("time"), it.optString("stage"), it.optString("detail"), it.optString("process"))
            }
        }.toMap()
        return true
    }

    private fun command(name: String, extras: Bundle): Bundle? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.sendExtraCommand(Bridge.COMMAND, name, extras)
        extras
    } catch (_: Throwable) {
        null
    }
}

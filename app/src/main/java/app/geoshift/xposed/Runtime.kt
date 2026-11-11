package app.geoshift.xposed

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import app.geoshift.core.Clock
import app.geoshift.core.LatLon
import app.geoshift.core.RoutePlan
import app.geoshift.core.SimConfig
import io.github.libxposed.api.XposedInterface
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Names shared by the app and the hooks. */
object Bridge {
    const val PREFS_GROUP = "geoshift"
    const val KEY_CONFIG = "config"
    /** sendExtraCommand provider name for the diagnostics / joystick channel. */
    const val COMMAND = "geoshift"
    const val SELF = "app.geoshift"
    fun routeFile(id: String) = "route-$id.json"
    val ROUTE_SERIALIZER = ListSerializer(LatLon.serializer())
}

/** Contexts inside a hooked process, fetched lazily (none exists yet when the module loads). */
object Contexts {
    @Volatile private var cached: Context? = null

    fun get(): Context? {
        cached?.let { return it }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? Context
            val ctx = app ?: at.getMethod("currentActivityThread").invoke(null)?.let { thread ->
                at.getMethod("getSystemContext").invoke(thread) as? Context
            }
            ctx?.also { cached = it }
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * The simulation config in this process. The app writes it to the framework's remote preferences; the
 * framework pushes every change here. Hooks read [config], a plain volatile field, so they never block.
 */
object Sim {
    @Volatile private var raw: SimConfig = SimConfig.OFF
    @Volatile private var effective: SimConfig = SimConfig.OFF
    @Volatile var plan: RoutePlan? = null
        private set
    /** Joystick position sent straight to system_server; newer than the stored config while moving. */
    @Volatile private var move: Move? = null

    private class Move(val point: LatLon, val speed: Double, val bearing: Double, val at: Long)

    private lateinit var xposed: XposedInterface
    private var prefs: SharedPreferences? = null
    private val worker by lazy { Handler(HandlerThread("geoshift-config").apply { start() }.looper) }
    private val listeners = ArrayList<() -> Unit>()

    /** The config hooks should act on: the stored one, valid only for the boot it was written in. */
    val config: SimConfig
        get() {
            val c = effective
            val m = move ?: return c
            if (!c.active || c.route != null || m.at < c.writtenAt) return c
            return c.copy(position = m.point, speed = m.speed, bearing = m.bearing)
        }

    val clock get() = Clock.SYSTEM

    fun start(xposed: XposedInterface) {
        this.xposed = xposed
        worker.post {
            try {
                val p = xposed.getRemotePreferences(Bridge.PREFS_GROUP)
                prefs = p
                p.registerOnSharedPreferenceChangeListener { _, key -> if (key == Bridge.KEY_CONFIG) worker.post { load() } }
                load()
            } catch (e: Throwable) {
                Log.w("remote preferences unavailable", e)
                Diag.report(Diag.configKey, false, "error", "无法读取框架远程配置：${e.javaClass.simpleName}（框架需支持 libxposed API 101+）")
            }
        }
    }

    fun onChange(listener: () -> Unit) = synchronized(listeners) { listeners.add(listener) }

    /**
     * The config sent straight from the app (system_server only). Vector does not push remote
     * preference changes into system_server, so the app also delivers each write here.
     */
    fun accept(text: String) { worker.post { load(text) } }

    private fun load(text: String? = prefs?.getString(Bridge.KEY_CONFIG, null)) {
        raw = try {
            if (text.isNullOrEmpty()) SimConfig.OFF else SimConfig.decode(text)
        } catch (e: Throwable) {
            Diag.report(Diag.configKey, false, "error", "配置格式无法识别：${e.javaClass.simpleName}")
            SimConfig.OFF
        }
        loadRoute(raw)
        validate()
    }

    /** A config from an earlier boot must not resume after a restart. */
    private fun validate() {
        val c = raw
        if (c == SimConfig.OFF) { publish(SimConfig.OFF); return }
        val now = SimConfig.bootMoment(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        if (!c.sameBoot(now)) {
            publish(SimConfig.OFF)
            Diag.report(Diag.configKey, true, "stale", "配置来自上次开机，已忽略；打开 App 重新开始模拟")
        } else {
            publish(c)
            Diag.report(Diag.configKey, true, "synced", "已收到配置：" + if (c.active) "模拟中" else "未模拟")
        }
    }

    private fun publish(c: SimConfig) {
        val changed = c != effective
        effective = c
        if (changed) synchronized(listeners) { listeners.toList() }.forEach { runCatching(it) }
    }

    private fun loadRoute(c: SimConfig) {
        val id = c.route?.routeId ?: return
        if (plan?.id == id) return
        try {
            val text = xposed.openRemoteFile(Bridge.routeFile(id)).use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).bufferedReader().readText()
            }
            val points = SimConfig.JSON.decodeFromString(Bridge.ROUTE_SERIALIZER, text)
            plan = RoutePlan(id, points)
        } catch (e: Throwable) {
            Diag.report(Diag.configKey, false, "error", "路线文件读取失败：${e.javaClass.simpleName}；暂用 App 上报的位置")
        }
    }

    /** Joystick step from the app (system_server only). */
    fun moveTo(point: LatLon, speed: Double, bearing: Double) {
        move = Move(point, speed, bearing, SystemClock.elapsedRealtime())
    }
}

/**
 * Diagnostics. system_server keeps the reports and hands them to the app on PING; the phone and
 * Bluetooth processes forward theirs to system_server (REPORT). Repeats are throttled.
 */
object Diag {
    @Volatile var isSystemServer = false
    @Volatile var processName = ""
    /** Each process reports its own config state. */
    val configKey get() = when {
        isSystemServer -> "config_system"
        processName.contains("phone") -> "config_phone"
        else -> "config_bluetooth"
    }
    val reports = ConcurrentHashMap<String, JSONObject>()
    private val lastSent = ConcurrentHashMap<String, Pair<Long, String>>()
    private const val THROTTLE_MS = 3000L

    fun report(feature: String, ok: Boolean, stage: String, detail: String) {
        val now = SystemClock.elapsedRealtime()
        val last = lastSent[feature]
        if (last != null && now - last.first < THROTTLE_MS && last.second == stage) return
        lastSent[feature] = now to stage
        val json = JSONObject().put("ok", ok).put("stage", stage).put("detail", detail.take(300))
            .put("time", System.currentTimeMillis()).put("process", processName.take(100))
        if (isSystemServer) reports[feature] = json else forward(feature, json)
    }

    private fun forward(feature: String, json: JSONObject) {
        try {
            val ctx = Contexts.get() ?: return
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return
            lm.sendExtraCommand(Bridge.COMMAND, "REPORT", Bundle().apply {
                putString("feature", feature)
                putString("report", json.toString())
            })
        } catch (_: Throwable) {
        }
    }

    fun snapshot(): JSONObject {
        val out = JSONObject()
        for ((k, v) in reports) out.put(k, v)
        if (Reflect.missing.isNotEmpty()) {
            out.put("missing", JSONObject().put("ok", false).put("stage", "error").put("time", System.currentTimeMillis())
                .put("process", "system_server")
                .put("detail", Reflect.missing.entries.joinToString("；") { "${it.key}（${it.value}）" }.take(600)))
        }
        return out
    }
}

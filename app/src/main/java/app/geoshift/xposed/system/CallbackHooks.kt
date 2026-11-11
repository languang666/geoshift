package app.geoshift.xposed.system

import android.location.Location
import android.os.Build
import android.os.SystemClock
import app.geoshift.core.Fix
import app.geoshift.core.Nmea
import app.geoshift.core.Satellite
import app.geoshift.core.SimConfig
import app.geoshift.core.Sky
import app.geoshift.xposed.AppScope
import app.geoshift.xposed.Call
import app.geoshift.xposed.CallbackOwners
import app.geoshift.xposed.Diag
import app.geoshift.xposed.Fakes
import app.geoshift.xposed.Hooker
import app.geoshift.xposed.Reflect
import app.geoshift.xposed.Sim
import app.geoshift.xposed.failClosed
import java.util.concurrent.atomic.AtomicLong

/**
 * Rewrites what system_server sends to apps, at the Binder proxy of each client callback:
 *   location listeners / getCurrentLocation callbacks -> simulated location
 *   GNSS status -> simulated satellites; NMEA -> simulated sentences of the same type
 *   raw GNSS measurements / navigation messages (enough to solve the real fix) -> dropped
 *   cell info / cell location / service state / barring / registration failure -> simulated or none
 *   Xiaomi HyperOS blurred-location service -> simulated location / cells
 * In "selected" mode only callbacks owned by a chosen app are rewritten; other apps keep real data,
 * and a simulated fix the provider cache may still hold is swapped for the real one.
 */
class CallbackHooks(private val hooks: Hooker, private val loader: ClassLoader, private val location: LocationHooks) {
    private val installed = ArrayList<String>()
    private val missing = ArrayList<String>()
    private val hits = linkedMapOf("定位" to AtomicLong(), "卫星" to AtomicLong(), "NMEA" to AtomicLong(),
        "原始测量拦截" to AtomicLong(), "基站" to AtomicLong(), "小米模糊定位" to AtomicLong())
    private val unknownOwners = AtomicLong()
    private var lastReport = 0L

    private fun report(detail: String) = Diag.report("callbacks", false, "error", detail)

    /** The config if the simulation applies to the app owning this callback proxy. */
    private fun forOwner(c: SimConfig?, proxy: Any?): SimConfig? {
        if (c == null || !c.selectedOnly) return c
        val uid = CallbackOwners.ownerOf(proxy)
        if (uid < 0) {
            val n = unknownOwners.incrementAndGet()
            if (n == 1L || (n and (n - 1)) == 0L) report("回调归属未知 $n 次：${proxy?.javaClass?.name}；无法确认是否为选中应用")
        }
        return if (AppScope.covers(c, uid)) c else null
    }

    private fun locationConfig() = Sim.config.takeIf { it.locationOn }
    private fun gnssConfig() = Sim.config.takeIf { it.gnssOn }
    private fun cellConfig() = Sim.config.takeIf { it.cellsOn }

    /** For an app the simulation does not cover: a simulated fix becomes the real one (or nothing). */
    private fun realFor(c: SimConfig, item: Any?): Location? {
        val l = item as? Location ?: return null
        return if (location.nearSimulated(c, l)) location.realFix(l.provider) else l
    }

    fun install() {
        proxy("android.location.ILocationListener\$Stub\$Proxy", "onLocationChanged", "定位回调") { call ->
            val all = locationConfig() ?: return@proxy
            val original = call.args.firstOrNull() as? List<*> ?: return@proxy
            val c = forOwner(all, call.thisObject)
            if (c == null) {
                val real = original.mapNotNull { realFor(all, it) }
                if (real.isEmpty()) call.drop() else call.args[0] = real
                return@proxy
            }
            call.failClosed("定位回调", ::report) {
                call.args[0] = if (original.isEmpty()) listOf(Fakes.replace(c, null)) else original.map { Fakes.replace(c, it) }
                hit("定位")
            }
        }
        proxy("android.location.ILocationCallback\$Stub\$Proxy", "onLocation", "单次定位回调") { call ->
            val all = locationConfig() ?: return@proxy
            if (call.args.isEmpty()) return@proxy
            val c = forOwner(all, call.thisObject)
            if (c == null) { call.args[0] = realFor(all, call.args[0]); return@proxy }
            call.failClosed("单次定位回调", ::report) { call.args[0] = Fakes.replace(c, call.args[0]); hit("定位") }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            proxy("android.location.IGnssStatusListener\$Stub\$Proxy", "onSvStatusChanged", "卫星状态") { call ->
                val c = forOwner(gnssConfig(), call.thisObject) ?: return@proxy
                if (call.args.isEmpty()) return@proxy
                call.failClosed("卫星状态", ::report) { call.args[0] = Fakes.gnss(c); hit("卫星") }
            }
        }
        installNmea()
        // Raw pseudoranges and navigation data let an app solve the real position itself.
        for ((cls, method, label) in listOf(
            Triple("android.location.IGnssMeasurementsListener\$Stub\$Proxy", "onGnssMeasurementsReceived", "GNSS 原始测量"),
            Triple("android.location.IGnssNavigationMessageListener\$Stub\$Proxy", "onGnssNavigationMessageReceived", "GNSS 导航电文"),
        )) proxy(cls, method, label) { call ->
            if (forOwner(locationConfig(), call.thisObject) == null) return@proxy
            call.drop(); hit("原始测量拦截")
        }
        installPhoneState()
        MiuiHooks(hooks, loader, ::hit).install()
        Diag.report("callbacks", missing.isEmpty(), if (missing.isEmpty()) "installed" else "error",
            "已安装：${installed.joinToString("、")}" + if (missing.isEmpty()) "" else "；缺失：${missing.joinToString("、")}")
    }

    /** A chip sends one burst of NMEA sentences per fix; all of them describe the same fix and sky. */
    private class Burst(val config: SimConfig, val at: Long, val fix: Fix, val sky: List<Satellite>)
    @Volatile private var burst: Burst? = null

    private fun burst(c: SimConfig): Burst {
        val now = SystemClock.elapsedRealtime()
        burst?.let { if (it.config === c && now - it.at in 0 until 300) return it }
        return Burst(c, now, Fakes.fix(c, "gps"), Sky.at(c, now)).also { burst = it }
    }

    private fun installNmea() {
        val type = Reflect.classOrNull("android.location.IGnssNmeaListener\$Stub\$Proxy", loader)
        val n = hooks.declared(type, "onNmeaReceived") { m ->
            hooks.around(m, before = { call ->
                val c = forOwner(locationConfig(), call.thisObject) ?: return@around
                val text = call.args.indexOfLast { it is String }
                // NMEA carries the real position: swap each sentence for the simulated one.
                val fake = try {
                    if (text < 0) emptyList() else burst(c).let { b -> Nmea.replace(call.args[text] as String, b.fix, b.sky) }
                } catch (_: Throwable) { emptyList() }
                if (fake.isEmpty()) { call.drop(); return@around }
                call.args[text] = fake[0]
                more.set(fake.drop(1))
                hit("NMEA")
            }, after = { call ->
                // Simulated GSV parts the real chip never sent, one callback each like real sentences.
                val extra = more.get() ?: return@around
                more.remove()
                val text = call.args.indexOfLast { it is String }
                for (sentence in extra) {
                    val args = call.args.clone()
                    args[text] = sentence
                    try { call.proceedWith(args) } catch (_: Throwable) { return@around }
                }
            })
        }
        mark(n, "NMEA")
    }

    private val more = ThreadLocal<List<String>?>()

    private fun installPhoneState() {
        val cls = "com.android.internal.telephony.IPhoneStateListener\$Stub\$Proxy"
        proxy(cls, "onCellInfoChanged", "基站列表回调") { call ->
            val c = forOwner(cellConfig(), call.thisObject) ?: return@proxy
            if (call.args.isEmpty()) return@proxy
            call.failClosed("基站列表回调", ::report) { call.args[0] = Fakes.cellsOrEmpty(c); hit("基站") }
        }
        proxy(cls, "onCellLocationChanged", "基站位置回调") { call ->
            val c = forOwner(cellConfig(), call.thisObject) ?: return@proxy
            if (call.args.isEmpty()) return@proxy
            call.failClosed("基站位置回调", ::report) { call.args[0] = Fakes.cellIdentityOrEmpty(c); hit("基站") }
        }
        proxy(cls, "onServiceStateChanged", "网络状态回调") { call ->
            val c = forOwner(cellConfig(), call.thisObject) ?: return@proxy
            val state = call.args.firstOrNull() ?: return@proxy
            call.failClosed("网络状态回调", ::report) { call.args[0] = Fakes.serviceState(state, c); hit("基站") }
        }
        proxy(cls, "onBarringInfoChanged", "接入限制回调") { call ->
            forOwner(cellConfig(), call.thisObject) ?: return@proxy
            val info = call.args.firstOrNull() ?: return@proxy
            call.failClosed("接入限制回调", ::report) { call.args[0] = Reflect.call(info, "createLocationInfoSanitizedCopy"); hit("基站") }
        }
        proxy(cls, "onRegistrationFailed", "注册失败回调") { call ->
            val c = forOwner(cellConfig(), call.thisObject) ?: return@proxy
            if (call.args.isEmpty()) return@proxy
            call.failClosed("注册失败回调", ::report) { call.args[0] = Fakes.cellIdentityOrEmpty(c); hit("基站") }
        }
    }

    private fun proxy(className: String, method: String, label: String, block: (Call) -> Unit) {
        val type = Reflect.classOrNull(className, loader)
        mark(hooks.declared(type, method) { m -> hooks.before(m, block) }, label)
        if (type == null) Reflect.missing[className.substringAfterLast('.')] = label
    }

    private fun mark(count: Int, label: String) {
        if (count > 0) installed.add(label) else missing.add(label)
    }

    private fun hit(kind: String) {
        hits[kind]?.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastReport < 3000) return
            lastReport = now
        }
        Diag.report("callbacks", true, "read", "应用回调替换：" + hits.entries.filter { it.value.get() > 0 || it.key != "小米模糊定位" }
            .joinToString(" ") { "${it.key}=${it.value.get()}" })
    }
}

/**
 * Xiaomi HyperOS / MIUI keeps its own location service in system_server for "approximate location";
 * it answers some apps from the real fix and real towers on its own path. Only present on Xiaomi
 * ROMs, so its absence is not reported.
 */
private class MiuiHooks(private val hooks: Hooker, private val loader: ClassLoader, private val hit: (String) -> Unit) {
    fun install() {
        val type = Reflect.firstClass(listOf("com.android.server.location.MiuiBlurLocationManagerImpl",
            "com.android.server.location.MiuiBlurLocationManager"), loader, null) ?: return
        hooks.declared(type, "getBlurryLocation") { m ->
            hooks.after(m) { call ->
                val c = target(Sim.config.takeIf { it.locationOn }, call.args) ?: return@after
                call.failClosed("小米模糊定位", { Diag.report("callbacks", false, "error", it) }) {
                    call.result = locationLike(c, call.result, call.returnType); hit("小米模糊定位")
                }
            }
        }
        hooks.declared(type, "getBlurryCellInfos") { m ->
            hooks.after(m) { call ->
                val c = target(Sim.config.takeIf { it.cellsOn }, call.args) ?: return@after
                call.result = fit(runCatching { Fakes.cellsOrEmpty(c) }.getOrElse { ArrayList<Any>() }, call.returnType)
                hit("小米模糊定位")
            }
        }
        hooks.declared(type, "getBlurryCellLocation") { m ->
            hooks.after(m) { call ->
                val c = target(Sim.config.takeIf { it.cellsOn }, call.args) ?: return@after
                call.result = fit(runCatching { Fakes.cellIdentityOrEmpty(c) }.getOrNull(), call.returnType)
                hit("小米模糊定位")
            }
        }
        hooks.declared(type, "handleGpsLocationChangedLocked") { m ->
            hooks.before(m) { call ->
                val c = Sim.config.takeIf { it.locationOn } ?: return@before
                if (c.selectedOnly) return@before // A global refresh: only when every app is covered.
                call.drop(); hit("小米模糊定位")
            }
        }
    }

    /** In "selected" mode, the config only when a uid / package argument names a chosen app. */
    private fun target(c: SimConfig?, args: Array<Any?>): SimConfig? {
        if (c == null || !c.selectedOnly) return c
        for (a in args) {
            if (a is Int && a >= 10000 && AppScope.covers(c, a)) return c
            if (a is String && AppScope.listed(c, a)) return c
        }
        return null
    }

    /** Replaces a Location, LocationResult or list of locations with simulated ones of the same shape. */
    private fun locationLike(c: SimConfig, result: Any?, returnType: Class<*>): Any? = when {
        result is Location -> Fakes.replace(c, result)
        result is List<*> -> if (result.isEmpty()) listOf(Fakes.replace(c, null)) else result.map { Fakes.replace(c, it) }
        // Provider caches may share this object with apps outside the chosen scope: build a new one.
        result != null -> Fakes.replacePacket(c, result)
        Location::class.java.isAssignableFrom(returnType) -> Fakes.replace(c, null)
        else -> null
    }

    private fun fit(value: Any?, returnType: Class<*>): Any? = when {
        value != null && returnType.isInstance(value) -> value
        returnType.isAssignableFrom(ArrayList::class.java) -> ArrayList<Any>()
        else -> app.geoshift.xposed.defaultValue(returnType)
    }
}

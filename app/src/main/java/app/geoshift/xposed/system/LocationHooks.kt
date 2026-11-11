package app.geoshift.xposed.system

import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import app.geoshift.core.Geo
import app.geoshift.core.LatLon
import app.geoshift.core.SimConfig
import app.geoshift.xposed.AppScope
import app.geoshift.xposed.CallbackOwners
import app.geoshift.xposed.Diag
import app.geoshift.xposed.Fakes
import app.geoshift.xposed.Hooker
import app.geoshift.xposed.Log
import app.geoshift.xposed.Reflect
import app.geoshift.xposed.Sim
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Location inside system_server (Android 12+): every app gets the simulated fix with no scope of its
 * own. Real provider reports are dropped while simulating and a pump reports the simulated fix through
 * each provider manager at the configured interval, exactly like a real fix. Deliveries to apps,
 * cached last fixes and replays after stopping are rewritten as well.
 */
class LocationHooks(private val hooks: Hooker, private val loader: ClassLoader) {
    private val providers = listOf("gps", "network", "fused")
    private val managers: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    @Volatile private var service = WeakReference<Any>(null)
    /** Last real fix per provider, for apps outside the chosen list and for after stopping. */
    private val lastReal = ConcurrentHashMap<String, Location>()
    /** After stopping, provider caches still hold the last simulated fix until a real one arrives. */
    private val staleProviders = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var pumpWasActive = false
    @Volatile private var lastFake: LatLon? = null
    @Volatile private var lastPumpAt = 0L
    /** True while the pump itself reports a simulated fix on this thread. */
    private val injecting = ThreadLocal.withInitial { false }
    private val pump by lazy { Handler(HandlerThread("geoshift-pump").apply { start() }.looper) }
    private val tick = Runnable { injectAll() }

    private val dropped = ConcurrentHashMap<String, AtomicLong>()
    private val pumped = ConcurrentHashMap<String, AtomicLong>()
    private val pumpFailed = ConcurrentHashMap<String, AtomicLong>()
    private val delivered = AtomicLong()
    @Volatile private var deliveryHooks = 0
    @Volatile private var lastTarget = "尚无"
    @Volatile private var lastPumpError = ""
    private var lastPumpReport = 0L

    private fun active(): SimConfig? = Sim.config.takeIf { it.locationOn }

    fun install() {
        val lpm = Reflect.requireClass("com.android.server.location.provider.LocationProviderManager", loader, "系统定位")
            ?: error("LocationProviderManager")
        hooks.constructors(lpm) { c -> hooks.after(c) { managers.add(it.thisObject!!) } }
        var reports = 0
        for (m in methods(lpm, nested = false) { it.name == "onReportLocation" && firstParam(it) == "android.location.LocationResult" }) {
            hooks.before(m, ::onReport); reports++
        }
        var cached = 0
        for (m in methods(lpm, nested = false) { it.name == "getLastLocation" && it.returnType == Location::class.java }) {
            hooks.after(m, ::onCached); cached++
        }
        deliveryHooks = hookDeliveries(lpm)
        check(reports > 0 && cached > 0) { "定位入口缺失：report=$reports, last=$cached" }
        watchRegistrations()
        Sim.onChange { schedule(0) }
        Diag.report("location_system", true, "installed", "系统定位入口已安装（上报 $reports · 缓存 $cached · 交付 $deliveryHooks）")
        schedule(0)
    }

    // ---- Real reports -------------------------------------------------------------------------

    private fun onReport(call: app.geoshift.xposed.Call) {
        val manager = call.thisObject ?: return
        managers.add(manager)
        if (injecting.get()) return // The pump's own report: already simulated.
        val packet = call.args.firstOrNull() ?: return
        val name = nameOf(manager)
        val c = active()
        if (c == null) {
            // A real fix after stopping: from here on the provider cache is real again.
            if (name in providers && staleProviders.remove(name)) {
                rememberReal(name, packet, null)
                staleProviders.remove("passive")
            }
            return
        }
        if (name !in providers && name != "passive") return
        if (c.selectedOnly) {
            // Real fixes keep flowing for apps not chosen; each delivery decides per app.
            if (name in providers) rememberReal(name, packet, c)
            return
        }
        if (name in providers) {
            rememberReal(name, packet, c)
            // Drop real reports; the pump delivers the simulated fix. Replacing them instead loops: a
            // fused provider listening passively sees the replaced fix as new input and reports again.
            call.drop()
            count(dropped, name)
            return
        }
        try {
            call.args[0] = Fakes.wrap(Fakes.location(c, if (name == "passive") "gps" else name))
        } catch (e: Throwable) {
            call.drop()
            Log.w("passive report", e)
        }
    }

    // ---- Cached last fix ----------------------------------------------------------------------

    private fun onCached(call: app.geoshift.xposed.Call) {
        val name = nameOf(call.thisObject ?: return)
        if (name !in providers && name != "passive") return
        val provider = if (name == "passive") "gps" else name
        val c = active()
        val result = call.result as? Location
        if (c == null) {
            if (staleSimulated(result, name)) call.result = afterStop(provider)
            return
        }
        if (c.selectedOnly && !AppScope.covers(c, identityUid(call.args))) {
            // Not chosen: the real last fix, never a simulated one left in the provider cache.
            if (result != null && nearSimulated(c, result)) call.result = realFix(provider)
            return
        }
        try {
            call.result = Fakes.location(c, provider)
        } catch (e: Throwable) {
            call.result = null
            Diag.report("location_delivery", false, "error", "缓存定位替换失败，已阻止返回真实数据：${e.javaClass.simpleName}")
        }
    }

    // ---- Deliveries to apps -------------------------------------------------------------------

    /** Registration classes nested in LocationProviderManager (Android 12–16). */
    private val registrations = listOf("\$Registration", "\$LocationRegistration", "\$LocationListenerRegistration",
        "\$LocationPendingIntentRegistration", "\$GetCurrentLocationListenerRegistration")

    private fun hookDeliveries(lpm: Class<*>): Int {
        // Optimised system jars drop the inner-class metadata getDeclaredClasses() relies on, so the
        // registration classes are also looked up by name.
        val targets = LinkedHashSet<Method>()
        targets += methods(lpm, nested = true, ::isDelivery)
        for (suffix in registrations) Reflect.classOrNull(lpm.name + suffix, loader)?.let { targets += methods(it, nested = true, ::isDelivery) }
        if (targets.isEmpty()) Reflect.missing["LocationProviderManager.acceptLocationChange"] = "定位交付"
        for (m in targets) hooks.before(m, ::onDelivery)
        return targets.size
    }

    private fun isDelivery(m: Method) = m.name == "acceptLocationChange" && firstParam(m) == "android.location.LocationResult"

    private fun onDelivery(call: app.geoshift.xposed.Call) {
        val packet = call.args.firstOrNull() ?: return
        val c = active()
        if (c == null) {
            // Stopped: a cached simulated fix replayed to a new listener is dropped.
            if (staleProviders.isNotEmpty()) {
                val last = Fakes.lastOf(packet)
                if (staleSimulated(last, last?.provider)) call.drop()
            }
            return
        }
        if (c.selectedOnly && !AppScope.covers(c, registrationUid(call.thisObject))) {
            // Not chosen: drop the pump's simulated fixes and any replayed from the cache; real ones go through.
            if (injecting.get()) { call.drop(); return }
            val last = Fakes.lastOf(packet)
            if (last != null && nearSimulated(c, last)) call.drop()
            return
        }
        try {
            val provider = Fakes.lastOf(packet)?.provider ?: return
            if (provider !in providers && provider != "passive") return
            call.args[0] = Fakes.replacePacket(c, packet)
            delivered.incrementAndGet()
            lastTarget = targetOf(call.thisObject)
            progress()
        } catch (e: Throwable) {
            call.drop()
            Diag.report("location_delivery", false, "error", "定位交付替换失败，已阻止返回真实数据：${e.javaClass.simpleName}")
            Log.w("delivery", e)
        }
    }

    private fun targetOf(registration: Any?): String = try {
        val identity = Reflect.call(registration!!, "getIdentity")!!
        (Reflect.call(identity, "getPackageName") as? String ?: "?").take(100)
    } catch (_: Throwable) {
        "包名不可用"
    }

    private fun registrationUid(registration: Any?): Int = try {
        Reflect.call(Reflect.call(registration!!, "getIdentity")!!, "getUid") as Int
    } catch (_: Throwable) {
        -1
    }

    /** Owner uid of a provider request / registration identity argument, or -1. */
    private fun identityUid(args: Array<Any?>): Int {
        for (a in args) if (a != null && a.javaClass.name.endsWith("CallerIdentity")) (Reflect.callOrNull(a, "getUid") as? Int)?.let { return it }
        return -1
    }

    // ---- New listeners get a fix at once ------------------------------------------------------

    /**
     * Records which app owns each location / GNSS / phone-state callback (for "selected" mode), and
     * runs the pump right after an app starts listening instead of at the next tick.
     */
    private fun watchRegistrations() {
        val lms = Reflect.firstClass(listOf("com.android.server.location.LocationManagerService",
            "com.android.server.LocationManagerService"), loader, "定位服务")
        if (lms != null) hooks.constructors(lms) { c -> hooks.after(c) { call -> call.thisObject?.let { attachService(it) } } }
        var n = CallbackOwners.watch(hooks, lms) { m, _ ->
            if (m.name.startsWith("registerLocation") || m.name == "getCurrentLocation" || m.name == "requestLocationUpdates") kick()
        }
        n += CallbackOwners.watch(hooks, Reflect.classOrNull("com.android.server.TelephonyRegistry", loader))
        Log.i("callback owner hooks: $n")
    }

    /** Called by the command hook, which sees the LocationManagerService instance. */
    fun attachService(instance: Any) { service = WeakReference(instance) }

    private fun kick() {
        if (active() == null) return
        val since = SystemClock.elapsedRealtime() - lastPumpAt
        schedule(if (since >= 200) 30 else 200 - since)
    }

    // ---- Pump ---------------------------------------------------------------------------------

    fun schedule(delay: Long) {
        pump.removeCallbacks(tick)
        pump.postDelayed(tick, delay)
    }

    /**
     * Reports the simulated fix through each provider's own lock, exactly like a real fix, so apps
     * keep receiving updates even with no real GPS / network signal.
     */
    private fun injectAll() {
        lastPumpAt = SystemClock.elapsedRealtime()
        val c = active()
        if (c == null) {
            if (pumpWasActive) {
                pumpWasActive = false
                staleProviders.addAll(providers)
                staleProviders.add("passive")
            }
            return
        }
        pumpWasActive = true
        staleProviders.clear()
        runCatching { lastFake = Fakes.fix(c, "gps").point }
        // Providers can exist before the constructor hook was installed: discover them from the service.
        service.get()?.let { s ->
            for (name in providers) runCatching { Reflect.call(s, "getLocationProviderManager", name)?.let { managers.add(it) } }
        }
        val snapshot = synchronized(managers) { managers.toList() }
        var ok = 0
        var failed = 0
        for (manager in snapshot) {
            val name = nameOf(manager)
            if (name !in providers) continue
            try {
                if (active() == null) break
                val provider = Reflect.get(manager, "mProvider", "定位泵")!!
                val lock = ownerLock(provider)
                val packet = Fakes.wrap(Fakes.location(c, name))
                synchronized(lock) {
                    injecting.set(true)
                    try { Reflect.call(manager, "onReportLocation", packet) } finally { injecting.set(false) }
                }
                count(pumped, name); ok++
            } catch (e: Throwable) {
                count(pumpFailed, name); failed++
                lastPumpError = "$name: ${e.javaClass.simpleName}"
                Log.w("inject $name", e)
            }
        }
        pumpReport(snapshot.size, ok, failed)
        schedule(c.interval.coerceIn(200, 5000).toLong())
    }

    private fun ownerLock(provider: Any): Any {
        for (name in listOf("mOwnerLock", "mLock")) Reflect.getOrNull(provider, name)?.let { return it }
        Reflect.missing["MockableLocationProvider.mOwnerLock"] = "定位泵"
        error("provider lock not found")
    }

    // ---- Real fixes ---------------------------------------------------------------------------

    /** Keeps the newest real fix. A chosen Play services app computes fixes from simulated input; those are not "real". */
    private fun rememberReal(provider: String, packet: Any, c: SimConfig?) {
        val last = Fakes.lastOf(packet) ?: return
        if (c != null && nearSimulated(c, last)) return
        lastReal[provider] = Location(last)
    }

    fun realFix(provider: String?): Location? {
        val l = lastReal[provider ?: "gps"] ?: lastReal["fused"] ?: lastReal["gps"] ?: lastReal["network"]
        return l?.let { Location(it) }
    }

    /** True when a fix is (close to) the simulated position, i.e. it must not reach an uncovered app. */
    fun nearSimulated(c: SimConfig, l: Location): Boolean = try {
        Geo.distance(Fakes.fix(c, "fused").point, LatLon(l.latitude, l.longitude)) < 500
    } catch (_: Throwable) {
        false
    }

    private fun staleSimulated(l: Location?, provider: String?): Boolean {
        val fake = lastFake ?: return false
        if (l == null || (provider ?: "") !in staleProviders) return false
        return Geo.distance(fake, LatLon(l.latitude, l.longitude)) < 500
    }

    /** The provider's own real fix (never another provider's) instead of a cached simulated one. */
    private fun afterStop(provider: String): Location? {
        val real = lastReal[provider] ?: return null
        val fake = lastFake ?: return Location(real)
        return if (Geo.distance(fake, LatLon(real.latitude, real.longitude)) < 500) null else Location(real)
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun nameOf(manager: Any): String = (Reflect.getOrNull(manager, "mName") as? String).orEmpty()

    private fun firstParam(m: Method) = m.parameterTypes.firstOrNull()?.name

    /** Concrete methods matching [predicate] in [type], its superclasses and (if [nested]) its declared nested classes. */
    private fun methods(type: Class<*>, nested: Boolean, predicate: (Method) -> Boolean): List<Method> {
        val out = LinkedHashSet<Method>()
        val queue = ArrayDeque<Class<*>>().apply { add(type) }
        val seen = HashSet<Class<*>>()
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            if (t == Any::class.java || !seen.add(t)) continue
            if (nested) runCatching { queue.addAll(t.declaredClasses) }
            t.superclass?.let { queue.add(it) }
            for (m in t.declaredMethods) if (!Modifier.isAbstract(m.modifiers) && predicate(m)) out.add(m)
        }
        return out.toList()
    }

    private fun count(map: ConcurrentHashMap<String, AtomicLong>, key: String) {
        map.getOrPut(key) { AtomicLong() }.incrementAndGet()
    }

    private fun counts(map: ConcurrentHashMap<String, AtomicLong>) = providers.joinToString(" ") { "$it=${map[it]?.get() ?: 0}" }

    private fun progress() {
        Diag.report("location_delivery", true, "injected",
            "已改写交付 ${delivered.get()} 次（入口 $deliveryHooks）· 拦截真实上报 ${counts(dropped)} · 最近目标 $lastTarget")
    }

    @Synchronized private fun pumpReport(found: Int, ok: Int, failed: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPumpReport < 3000) return
        lastPumpReport = now
        val detail = "已发现提供器 $found · " + providers.joinToString(" ") { "$it=${pumped[it]?.get() ?: 0}/${pumpFailed[it]?.get() ?: 0}" } + "（成功/失败）"
        Diag.report("location_pump", failed == 0, if (failed > 0) "error" else if (ok > 0) "injected" else "waiting",
            if (failed > 0) "$detail；最近错误 $lastPumpError" else detail)
    }
}

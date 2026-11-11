package app.geoshift.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings.canDrawOverlays
import app.geoshift.BuildConfig
import app.geoshift.core.CellSpec
import app.geoshift.core.CellType
import app.geoshift.core.Geo
import app.geoshift.core.LatLon
import app.geoshift.core.Repeat
import app.geoshift.core.RouteAnchor
import app.geoshift.core.RoutePlan
import app.geoshift.core.SimConfig
import app.geoshift.core.WifiSpec
import app.geoshift.core.mockFlag
import app.geoshift.core.simplifyRoute
import app.geoshift.data.net.Amap
import app.geoshift.data.net.IpLocator
import app.geoshift.data.net.OpenGeocoder
import app.geoshift.data.net.RoadRouter
import app.geoshift.service.JoystickService
import app.geoshift.service.SimulationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class RecordKind(val label: String) { PLACES("位置"), ROUTES("路线"), WIFI("Wi-Fi 预设"), CELLS("基站预设"), ENVIRONMENTS("环境") }

/**
 * The app's single engine: state, records and every operation the UI and services perform.
 * Operations validate their input and throw [UserError] with a Chinese message. Blocking
 * operations must run off the main thread.
 */
class Geoshift private constructor(context: Context) {
    val app: Context = context.applicationContext
    val store = Store(app)
    val bridge = FrameworkBridge(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val amap = Amap { store.mapKey.value }
    val router = RoadRouter(amap)
    val scanner = DeviceScanner(app) { capabilities() }

    private val _caps = MutableStateFlow(Capabilities())
    val caps: StateFlow<Capabilities> = _caps.asStateFlow()

    /** Whether Geoshift's own screen is showing; the service clock slows down while it is not. */
    @Volatile var uiVisible = false

    private val boot by lazy { bridge.bootCount() }
    private val plans = HashMap<String, RoutePlan>()

    init {
        bridge.onBind = { pushNow() }
        bridge.register()
        // Every change the hooks care about goes out as soon as it happens.
        scope.launch {
            combine(store.settings, store.runtime) { s, r -> s to r }.conflate().collectLatest { (s, r) ->
                try { bridge.push(simConfig(s, r), ::routePoints, joystick = r.joystick) }
                catch (e: UserError) { store.log(e.message.orEmpty()) }
            }
        }
    }

    private fun pushNow() = scope.launch {
        runCatching { bridge.push(simConfig(store.settings.value, store.runtime.value), ::routePoints, store.runtime.value.joystick) }
    }

    // ---- Config for the hooks -----------------------------------------------------------------

    /** What the hooks get: nothing unless the Xposed backend is chosen. */
    fun simConfig(s: Settings, r: Runtime): SimConfig =
        if (!s.xposed || !s.privacyAccepted) SimConfig(boot = boot) else localConfig(s, r)

    /** The simulation as a config, whichever backend plays it. */
    fun localConfig(s: Settings, r: Runtime): SimConfig {
        val route = r.anchor.takeIf { r.enabled && r.routeMode }
        return SimConfig(
            boot = boot, active = r.enabled, paused = r.paused, position = r.position,
            speed = if (route == null) r.speed else 0.0, bearing = r.bearing, route = route, noiseSeed = r.noiseSeed,
            accuracy = s.accuracy, altitude = s.altitude, jitter = s.jitter, interval = s.interval,
            hideEnvironment = s.hideEnvironment, gnss = s.gnss, satellites = s.satellites,
            wifi = r.wifi.takeIf { r.wifiEnabled }, cells = if (r.cellEnabled) r.cells else emptyList(),
            selectedOnly = s.onlySelected, packages = s.packages,
        )
    }

    private fun routePoints(id: String): List<LatLon>? = store.routes.items.value.firstOrNull { it.id == id }?.points

    /** Playback plan of a saved route (cached per route). */
    fun plan(routeId: String): RoutePlan? = synchronized(plans) {
        plans[routeId]?.let { return it }
        val points = routePoints(routeId) ?: return null
        RoutePlan(routeId, points).also { plans[routeId] = it }
    }

    // ---- Capabilities -------------------------------------------------------------------------

    fun capabilities(): Capabilities {
        val c = app
        fun granted(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val caps = Capabilities(
            preciseLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            locationEnabled = runCatching { if (Build.VERSION.SDK_INT >= 28) lm!!.isLocationEnabled else lm!!.isProviderEnabled("gps") }.getOrDefault(false),
            notifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            overlay = canDrawOverlays(c),
            phone = c.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            mockSelected = runCatching {
                (c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
                    .checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false),
            nearbyWifi = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.NEARBY_WIFI_DEVICES),
            framework = bridge.framework.value,
            hookActive = bridge.ping() || bridge.hookActive,
        )
        _caps.value = caps
        return caps
    }

    // ---- Simulation ---------------------------------------------------------------------------

    private val running get() = store.runtime.value.enabled

    fun ensureReady() {
        val s = store.settings.value
        if (!s.privacyAccepted) fail("请先阅读并同意使用说明。")
        val c = capabilities()
        if (!c.preciseLocation) fail("请先授予精确位置权限。")
        if (!c.locationEnabled) fail("请先打开系统定位开关。")
        if (s.xposed) {
            if (c.framework == null) fail("未连接到 Xposed 框架：请在框架管理器中启用 Geoshift。")
            if (!c.hookActive) fail("Geoshift 模块尚未在系统框架中运行：请确认作用域包含「系统框架」并重启手机。")
        } else if (!c.mockSelected) {
            fail("请在开发者选项的「选择模拟位置信息应用」中选择 Geoshift。")
        }
    }

    /** Real GPS fix (simulation must be off). Blocks up to 20 s. */
    @SuppressLint("MissingPermission")
    fun deviceLocation(): LatLon {
        if (!capabilities().preciseLocation) fail("请先授予精确位置权限。")
        if (running) fail("请先停止模拟，再获取原始位置。")
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) fail("请先打开系统定位。")
        val start = SystemClock.elapsedRealtimeNanos()
        val latch = CountDownLatch(1)
        val result = AtomicReference<android.location.Location?>()
        val listener = LocationListener { fix ->
            if (fix.elapsedRealtimeNanos >= start && !fix.mockFlag) { result.set(fix); latch.countDown() }
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            latch.await(20, TimeUnit.SECONDS)
        } catch (_: SecurityException) {
            fail("位置权限已撤销，请重新授予精确位置权限。")
        } finally {
            runCatching { lm.removeUpdates(listener) }
        }
        val fix = result.get() ?: fail("暂未获取新的 GPS 位置，请到室外后重试。")
        return LatLon(fix.latitude, fix.longitude)
    }

    fun setLocation(p: LatLon) {
        Validate.coordinate(p)
        store.updateRuntime { it.copy(position = p, error = "") }
        if (running) SimulationService.nudge()
    }

    fun startPoint(p: LatLon? = null) {
        p?.let(::setLocation)
        ensureReady()
        store.updateRuntime { it.copy(mode = SimMode.POINT) }
        start()
    }

    fun selectRoute(id: String) {
        if (running) fail("请先停止当前模拟，再切换路线。")
        val route = store.routes.find(id)
        Validate.route(route.points)
        store.updateRuntime { it.copy(mode = SimMode.ROUTE, routeId = id, routeName = route.name, position = route.points.first(), progress = 0.0, routeIndex = 0) }
        // A route drawn for driving plays at driving speed, and so on.
        route.profile.speed?.let { speed ->
            if (store.settings.value.speedMode != speed) {
                store.updateSettings { it.copy(speedMode = speed) }
                store.log("按路线出行方式切换为${speed.label}速度（${speed.metresPerSecond} m/s）")
            }
        }
    }

    fun startRoute(id: String? = null) {
        if (id != null) {
            if (running) stop()
            selectRoute(id)
        }
        ensureReady()
        val r = store.runtime.value
        if (r.routeId.isEmpty() || plan(r.routeId) == null) fail("请先选择一条路线。")
        store.updateRuntime { it.copy(mode = SimMode.ROUTE) }
        start()
    }

    /** Starts (or restarts) the current mode from its beginning. */
    fun start() {
        val s = store.settings.value
        val now = SystemClock.elapsedRealtime()
        store.updateRuntime { r ->
            val anchor = if (r.routeMode) RouteAnchor(r.routeId, 0.0, now, s.speed, r.repeat) else null
            val first = if (r.routeMode) plan(r.routeId)?.points?.firstOrNull() ?: r.position else r.position
            r.copy(enabled = true, paused = false, error = "", anchor = anchor, position = first, progress = 0.0,
                routeIndex = 0, noiseSeed = SecureRandom().nextLong())
        }
        SimulationService.start(app)
    }

    fun stop() {
        store.updateRuntime { it.copy(enabled = false, paused = false, speed = 0.0, anchor = null) }
        SimulationService.stop(app)
    }

    fun pauseRoute(pause: Boolean) {
        val r = store.runtime.value
        if (!r.enabled || !r.routeMode) return
        // At the end, "continue" would stop again at once: play the route again from the start.
        if (!pause && r.routeFinished) { start(); return }
        val now = SystemClock.elapsedRealtime()
        val anchor = r.anchor ?: return
        val plan = plan(anchor.routeId) ?: return
        store.updateRuntime {
            if (pause) it.copy(paused = true, speed = 0.0, anchor = anchor.copy(distance = plan.distanceAt(anchor, it.paused, now), at = now))
            else it.copy(paused = false, anchor = anchor.copy(at = now))
        }
        SimulationService.nudge()
    }

    /** Once / loop / back-and-forth; while playing, the route continues from where it is. */
    fun setRepeat(mode: Repeat) {
        val now = SystemClock.elapsedRealtime()
        store.updateRuntime { r ->
            val anchor = r.anchor
            val plan = anchor?.let { plan(it.routeId) }
            if (r.enabled && r.routeMode && anchor != null && plan != null)
                r.copy(repeat = mode, anchor = anchor.copy(distance = plan.reanchor(anchor, r.paused, now, mode), at = now, repeat = mode))
            else r.copy(repeat = mode)
        }
        SimulationService.nudge()
    }

    /** Moves a playing route forward to now and records where it is (for the UI and test providers). */
    fun advanceRoute(): Boolean {
        val r = store.runtime.value
        val anchor = r.anchor ?: return false
        if (!r.enabled || !r.routeMode || r.paused) return false
        val plan = plan(anchor.routeId) ?: return false
        val now = SystemClock.elapsedRealtime()
        val s = store.settings.value
        var a = anchor
        if (kotlin.math.abs(a.speed - s.speed) > 1e-9) {
            // Speed setting changed: continue from here at the new speed.
            a = a.copy(distance = plan.distanceAt(a, false, now), at = now, speed = s.speed)
        }
        val p = plan.live(a, false, now)
        if (p.finished) {
            store.updateRuntime { it.copy(position = p.point, progress = 1.0, routeIndex = p.index, paused = true, speed = 0.0,
                anchor = a.copy(distance = plan.total, at = now)) }
            store.log("路线已完成，保持终点位置；可重新开始或停止。")
            return true
        }
        store.updateRuntime(persist = a !== anchor) {
            it.copy(position = p.point, bearing = p.bearing, speed = plan.speedAt(a, false, now), progress = p.progress, routeIndex = p.index, anchor = a)
        }
        return true
    }

    fun joystick(enabled: Boolean) {
        if (enabled) {
            val r = store.runtime.value
            if (r.enabled && r.routeMode) fail("路线运行时请先停止路线，再使用摇杆。")
            ensureReady()
            if (!capabilities().overlay) fail("请先在系统设置中允许显示悬浮窗。")
        }
        JoystickService.command(app, enabled)
    }

    /** One joystick step: move [metres] towards [heading]. */
    fun joystickStep(metres: Double, heading: Double, speed: Double) {
        store.updateRuntime(persist = false) { r ->
            if (metres <= 0) r.copy(speed = 0.0) else r.copy(position = Geo.move(r.position, metres, heading), bearing = heading, speed = speed)
        }
        if (running) SimulationService.nudge()
    }

    fun clearSimulation() {
        stop()
        JoystickService.command(app, false)
        store.updateRuntime { it.copy(wifiEnabled = false, cellEnabled = false, speed = 0.0) }
        store.updateSettings { it.copy(gnss = false) }
    }

    // ---- Records ------------------------------------------------------------------------------

    private fun newId() = UUID.randomUUID().toString()

    fun savePlace(name: String, p: LatLon, address: String = ""): String {
        Validate.coordinate(p)
        return newId().also { store.places.put(SavedPlace(it, Validate.name(name), p, address)) }
    }

    fun renamePlace(id: String, name: String) = store.places.put(store.places.find(id).copy(name = Validate.name(name)))

    fun saveRoute(name: String, points: List<LatLon>, waypoints: List<LatLon> = emptyList(), profile: RoadProfile = RoadProfile.STRAIGHT): String {
        // Long road routes can exceed the point limit; drop points that do not change the shape.
        val kept = simplifyRoute(points, Validate.MAX_ROUTE_POINTS)
        if (kept.size < points.size) store.log("路线点较多，已精简为 ${kept.size} 个点")
        Validate.route(kept)
        return newId().also { store.routes.put(SavedRoute(it, Validate.name(name), kept, if (profile.road) waypoints else emptyList(), profile)) }
    }

    fun delete(kind: RecordKind, ids: Set<String>) {
        if (kind == RecordKind.ROUTES) {
            val r = store.runtime.value
            if (r.routeId in ids) {
                if (r.enabled && r.routeMode) stop()
                store.updateRuntime { it.copy(mode = SimMode.POINT, routeId = "", routeName = "", progress = 0.0, routeIndex = 0) }
            }
            synchronized(plans) { ids.forEach { plans.remove(it) } }
        }
        list(kind).remove(ids)
    }

    private fun list(kind: RecordKind): RecordList<*> = when (kind) {
        RecordKind.PLACES -> store.places
        RecordKind.ROUTES -> store.routes
        RecordKind.WIFI -> store.wifiPresets
        RecordKind.CELLS -> store.cellPresets
        RecordKind.ENVIRONMENTS -> store.environments
    }

    // ---- Wi-Fi / cells / satellites -----------------------------------------------------------

    private fun requireXposed(feature: String) {
        val s = store.settings.value
        if (!s.privacyAccepted) fail("请先阅读并同意使用须知。")
        if (!s.xposed) fail("${feature}需要在设置中切换到 Xposed 模式。")
        if (!capabilities().hookActive) fail("未检测到 Geoshift 系统模块：请在 Xposed 框架中启用 Geoshift 并重启手机。")
    }

    fun saveWifiPreset(name: String, ssid: String, bssid: String, id: String? = null): String {
        Validate.wifi(ssid, bssid)
        return (id ?: newId()).also { store.wifiPresets.put(WifiPreset(it, Validate.name(name), ssid.trim(), bssid.trim().lowercase(Locale.ROOT))) }
    }

    fun applyWifiPreset(id: String) {
        val p = store.wifiPresets.find(id)
        requireXposed("Wi-Fi 模拟")
        store.updateRuntime { it.copy(wifi = p.spec, wifiPresetId = id, wifiEnabled = true) }
    }

    fun setWifiEnabled(enabled: Boolean) {
        if (enabled) {
            requireXposed("Wi-Fi 模拟")
            if (store.runtime.value.wifi == null) fail("请先选择或创建 Wi-Fi 预设。")
        }
        store.updateRuntime { it.copy(wifiEnabled = enabled) }
    }

    fun saveCellPreset(name: String, cell: CellSpec, id: String? = null): String {
        Validate.cell(cell)
        return (id ?: newId()).also { store.cellPresets.put(CellPreset(it, Validate.name(name), cell)) }
    }

    fun applyCellPreset(id: String) {
        val p = store.cellPresets.find(id)
        requireXposed("基站模拟")
        store.updateRuntime { it.copy(cells = listOf(p.cell), cellPresetId = id, cellEnabled = true) }
    }

    fun setCellEnabled(enabled: Boolean) {
        if (enabled) {
            requireXposed("基站模拟")
            if (store.runtime.value.cells.isEmpty()) fail("请先选择或创建基站预设。")
        }
        store.updateRuntime { it.copy(cellEnabled = enabled) }
    }

    fun setGnss(enabled: Boolean, count: Int) {
        if (enabled) {
            requireXposed("卫星模拟")
            if (Build.VERSION.SDK_INT < 30) fail("卫星状态模拟需要 Android 11 或更新版本。")
        }
        if (count !in 4..32) fail("卫星数量应为 4–32。")
        store.updateSettings { it.copy(gnss = enabled, satellites = count) }
    }

    fun scanWifi(): List<ScanRow<ScannedWifi>> = scanner.wifi().map {
        ScanRow(it.ssid.ifBlank { "未命名网络" }, "${it.bssid} · ${it.rssi} dBm · ${it.frequency} MHz", it)
    }

    fun scanCells(): List<ScanRow<ScannedCell>> = scanner.cells().map {
        ScanRow("${it.type} · ${carrier(it.mcc, it.mnc)}${if (it.registered) " · 已注册" else " · 邻区"}",
            "${if (it.type == "GSM") "LAC" else "TAC"} ${it.area ?: "-"} · CID ${it.cid ?: "-"} · ${it.dbm} dBm", it)
    }

    // ---- Environments -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun captureEnvironment(name: String, p: LatLon): String {
        Validate.coordinate(p)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val device = runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos } }.getOrNull()
        val cells = runCatching { scanner.cells() }.getOrDefault(emptyList())
        val wifi = runCatching { scanner.wifi() }.getOrDefault(emptyList())
        val record = EnvironmentRecord(newId(), Validate.name(name), p, cells = cells, wifi = wifi,
            deviceDistance = device?.let { Geo.distance(p, LatLon(it.latitude, it.longitude)) },
            note = if (device == null) "没有系统定位结果，选定坐标不证明采集设备当时位于此处。" else "",
            simulatedAtCapture = running)
        store.environments.put(record)
        return record.id
    }

    /** Loads the location, supported cells (registered first) and the first valid Wi-Fi of a record. */
    fun applyEnvironment(id: String): String {
        requireXposed("位置环境载入")
        if (running) fail("请先停止当前模拟。")
        val e = store.environments.find(id)
        val cells = e.cells.mapNotNull { it.toSpec() }.take(Validate.MAX_CELLS)
        val wifi = e.wifi.firstOrNull { runCatching { Validate.wifi(it.ssid, it.bssid) }.isSuccess }
        store.updateRuntime {
            it.copy(position = e.position, cells = cells, cellEnabled = cells.isNotEmpty(), cellPresetId = "",
                wifi = wifi?.let { w -> WifiSpec(w.ssid, w.bssid.lowercase(), w.rssi, w.frequency) }, wifiEnabled = wifi != null,
                wifiPresetId = "", environmentName = e.name)
        }
        store.log("环境配置已载入：${e.name}")
        return "已载入坐标 · 基站 ${cells.size} 条 · Wi-Fi ${if (wifi != null) "已配置" else "缺失"}"
    }

    // ---- Settings -----------------------------------------------------------------------------

    fun updateSettings(change: (Settings) -> Settings) {
        val old = store.settings.value
        val next = change(old)
        Validate.inRange(next.interval.toDouble(), 100.0, 60000.0, "上报间隔应为 100–60,000 毫秒。")
        Validate.inRange(next.speedMultiplier, 0.01, 1000.0, "速度倍率应为 0.01–1000。")
        Validate.inRange(next.accuracy, 0.0, 1_000_000.0, "精度范围为 0–1,000,000 米。")
        Validate.inRange(next.altitude, -12000.0, 100000.0, "海拔范围为 -12,000–100,000 米。")
        Validate.packages(next.packages)
        if (next.backend != old.backend) {
            if (running) fail("请先停止当前模拟。")
            if (next.backend != Backend.XPOSED) store.updateRuntime { it.copy(wifiEnabled = false, cellEnabled = false) }
        }
        store.updateSettings { if (next.backend != Backend.XPOSED) next.copy(gnss = false) else next }
        if (old.privacyAccepted && !next.privacyAccepted) {
            stop()
            JoystickService.command(app, false)
        }
    }

    fun setMapKey(key: String) {
        Validate.mapKey(key.trim())
        store.setMapKey(key.trim())
    }

    fun clearAll() {
        stop()
        JoystickService.command(app, false)
        RecordKind.entries.forEach { list(it).clear() }
        store.clearLogs()
    }

    // ---- Search -------------------------------------------------------------------------------

    private fun requireOnline() {
        if (!store.settings.value.privacyAccepted) fail("请先阅读并同意隐私说明，再使用在线地图或搜索。")
    }

    /** "lat, lon" goes straight to that point; AMap with a key, otherwise OpenStreetMap near [near]. */
    fun search(query: String, near: LatLon? = null): List<Place> {
        Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,，\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""").find(query)?.let { m ->
            val p = LatLon(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
            if (p.valid) return listOf(Place(String.format(Locale.ROOT, "%.6f, %.6f", p.lat, p.lon), "坐标（WGS-84）", p))
        }
        val q = query.trim()
        if (q.isEmpty() || q.length > 200) fail("请输入 1–200 字的地点关键词。")
        requireOnline()
        if (!amap.available) return OpenGeocoder.search(q, near)
        // AMap has almost no overseas places; try OpenStreetMap before reporting nothing.
        return amap.search(q).ifEmpty { runCatching { OpenGeocoder.search(q, near) }.getOrDefault(emptyList()) }
    }

    /** Address text for a point; never throws (it is looked up automatically as the position changes). */
    fun reverseGeocode(p: LatLon): String {
        if (!store.settings.value.privacyAccepted) return ""
        if (!amap.available) return runCatching { OpenGeocoder.reverse(p) }.getOrDefault("")
        return try {
            amap.reverse(p).ifBlank { runCatching { OpenGeocoder.reverse(p) }.getOrDefault("") }
        } catch (e: Amap.AmapError) {
            if (e.outOfCoverage) runCatching { OpenGeocoder.reverse(p) }.getOrDefault("") else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun checkMapKey(): String {
        requireOnline()
        try { amap.reverse(LatLon(39.9042, 116.4074)) } catch (e: Amap.AmapError) { fail(e.message.orEmpty()) }
        return "Web 服务 Key 验证通过，地址查询可用"
    }

    /**
     * Advisory (manual only): the public exit IP's location against the simulated point. The module
     * cannot forge the IP; a large gap means apps that cross-check IP geolocation can still tell.
     */
    fun checkExitIp(): String {
        requireOnline()
        val r = IpLocator.lookup(amap)
        val where = "出口 IP${if (r.ip.isNotEmpty()) " ${r.ip}" else ""} 定位：${r.place.ifEmpty { "未知" }}（${r.source}）"
        val run = store.runtime.value
        if (!run.enabled) return "$where。当前未在模拟，仅供参考。"
        val ip = r.position ?: return "$where。无法取得 IP 坐标，请自行判断是否与模拟点一致。"
        val km = Geo.distance(run.position, ip) / 1000
        val d = String.format(Locale.ROOT, "%,.0f", km)
        return when {
            km < 50 -> "$where。距模拟点约 $d km，基本一致。"
            km < 300 -> "$where。距模拟点约 $d km，偏差较大（约省级范围）。"
            else -> "$where。距模拟点约 $d km，明显不一致：钉钉等可据 IP 识别，请改用对应地区的代理/VPN。"
        }
    }

    // ---- Backup -------------------------------------------------------------------------------

    fun exportTo(uri: Uri) {
        val text = Backup.export(store)
        app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: fail("无法写入文件。")
    }

    fun importFrom(uri: Uri): Int {
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > 4 * 1024 * 1024) fail("导入文件不能超过 4 MB。")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: fail("无法读取文件。")
        return Backup.import(store, String(bytes))
    }

    companion object {
        @Volatile private var instance: Geoshift? = null
        fun get(context: Context): Geoshift = instance ?: synchronized(this) { instance ?: Geoshift(context).also { instance = it } }

        val CARRIERS = listOf(Triple("中国移动", "460", "00"), Triple("中国联通", "460", "01"), Triple("中国电信", "460", "11"), Triple("中国广电", "460", "15"))

        fun carrier(mcc: String, mnc: String): String = when {
            mcc != "460" -> "MCC $mcc / MNC $mnc"
            mnc in listOf("00", "02", "04", "07", "08") -> "中国移动"
            mnc in listOf("01", "06", "09") -> "中国联通"
            mnc in listOf("03", "05", "11") -> "中国电信"
            mnc == "15" -> "中国广电"
            else -> "未知运营商"
        }

        val VERSION: String get() = BuildConfig.VERSION_NAME
    }
}

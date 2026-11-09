package app.geoshift.data

import android.content.Context
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import app.geoshift.DeviceScanner
import app.geoshift.Geo
import app.geoshift.MapNetwork
import app.geoshift.MapSource
import app.geoshift.OverlayService
import app.geoshift.PortalStore
import app.geoshift.SimulationService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import android.location.Location
import android.location.LocationListener

/** User-facing message for a rejected operation. */
class PortalException(message: String) : Exception(message)

private fun fail(message: String): Nothing = throw PortalException(message)

/**
 * All operations the UI can perform. Each validates its input and throws [PortalException]
 * (or the backend's Exception) with a Chinese message on failure. Call off the main thread.
 */
class PortalActions(context: Context) {
    private val c = context.applicationContext

    // ---- State --------------------------------------------------------------------------------

    fun snapshot(): PortalState {
        reconcileDeletedRoute()
        pingHook()
        return PortalState.from(PortalStore.snapshot(c))
    }

    /** Versions before routeId could retain an already-deleted selected route. */
    private fun reconcileDeletedRoute() = synchronized(RECORD_LOCK) {
        // Cheap check first: only routes selected before routeId existed need the full comparison.
        if (PortalStore.runtimeFields(c, "routeId").optString("routeId").isNotEmpty()) return@synchronized
        if (PortalStore.routeCoordinates(c)[0].isEmpty()) return@synchronized
        val r = PortalStore.runtimeFields(c, "routeId", "routeName", "route", "mode", "enabled")
        val points = r.optJSONArray("route") ?: return@synchronized
        val exists = PortalStore.items(c, "routes").objects().any {
            it.optString("name") == r.optString("routeName") && it.optJSONArray("points")?.toString() == points.toString()
        }
        if (!exists) {
            if (r.optBoolean("enabled") && r.optString("mode") == "route") stop()
            PortalStore.updateRuntime(c, PortalStore.obj("route", JSONArray(), "routeId", "", "routeName", "",
                "routeIndex", 0, "progress", 0, "mode", "point"))
        }
    }

    fun status(): JSONObject {
        pingHook()
        return PortalStore.status(c)
    }

    /**
     * Asks the system-framework hook to answer. Records that it is alive, whether it injects location
     * system-wide (Android 12+), and merges the read reports sent by target apps.
     */
    fun pingHook() {
        try {
            val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val b = Bundle()
            lm.sendExtraCommand("geoshift", "PING", b)
            if (!b.getBoolean("active")) return
            PortalStore.markHookSeen(c)
            val prefs = PortalStore.state(c)
            val system = b.getBoolean("system")
            if (prefs.getBoolean("hookSystem", false) != system) prefs.edit().putBoolean("hookSystem", system).apply()
            val reports = JSONObject(b.getString("reports") ?: "{}")
            for (feature in reports.keys()) {
                val report = reports.optJSONObject(feature) ?: continue
                PortalStore.mergeReport(c, feature, report)
            }
        } catch (_: Exception) {
        }
    }

    /** Pings the hook and reports whether it answered within the last minute. */
    fun checkHook(): Boolean {
        pingHook()
        return PortalStore.hookActive(c)
    }

    private fun running() = PortalStore.runtimeFields(c, "enabled").optBoolean("enabled")

    private fun clearLocationReports() {
        for (feature in listOf("location", "location_system")) PortalStore.clearFeature(c, feature)
    }

    // ---- Location and simulation --------------------------------------------------------------

    fun deviceLocation(): LatLon {
        if (!PortalStore.preciseLocationGranted(c)) fail("请先授予精确位置权限。")
        if (running()) fail("请先停止模拟，再获取原始位置。")
        val manager = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: fail("系统定位服务不可用。")
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) fail("请先打开系统定位。")
        val start = SystemClock.elapsedRealtimeNanos()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Location?>()
        val listener = LocationListener { fix ->
            if (fix.elapsedRealtimeNanos >= start && !PortalStore.isMock(fix)) { result.set(fix); latch.countDown() }
        }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            if (!latch.await(20, TimeUnit.SECONDS)) fail("暂未获取新的 GPS 位置，请到室外后重试。")
        } catch (_: SecurityException) {
            fail("位置权限已撤销，请重新授予精确位置权限。")
        } finally { try { manager.removeUpdates(listener) } catch (_: SecurityException) {} }
        val best = result.get() ?: fail("暂未获取新的 GPS 位置，请到室外后重试。")
        if (best.elapsedRealtimeNanos < start || PortalStore.isMock(best)) fail("系统返回了旧位置或模拟位置，请稍后重试。")
        return LatLon(best.latitude, best.longitude)
    }

    fun setLocation(p: LatLon) {
        checkCoordinate(p)
        clearLocationReports()
        PortalStore.updateRuntime(c, PortalStore.obj("lat", p.lat, "lon", p.lon, "error", ""))
        if (running()) SimulationService.command(c, "UPDATE")
    }

    fun startPoint(p: LatLon? = null) {
        if (p != null) setLocation(p)
        SimulationService.ensureReady(c)
        clearLocationReports()
        PortalStore.updateRuntime(c, PortalStore.obj("mode", "point"))
        SimulationService.command(c, "START")
    }

    fun stop() = SimulationService.command(c, "STOP")

    fun clearSimulation() {
        SimulationService.command(c, "STOP")
        OverlayService.command(c, false)
        PortalStore.updateRuntime(c, PortalStore.obj("wifiEnabled", false, "cellEnabled", false, "speed", 0))
        PortalStore.updateSettings(c, PortalStore.obj("gnssEnabled", false))
        clearLocationReports()
    }

    fun selectRoute(id: String) {
        if (running()) fail("请先停止当前模拟，再切换路线。")
        val route = find("routes", id)
        val points = route.getJSONArray("points")
        validateRoute(points)
        val first = points.getJSONObject(0)
        PortalStore.updateRuntime(c, PortalStore.obj("route", points, "routeId", id, "routeName", route.optString("name"),
            "routeIndex", 0, "progress", 0, "lat", first.getDouble("lat"), "lon", first.getDouble("lon"), "mode", "route"))
        // A road route drawn for driving should play at driving speed, and so on.
        val speed = when (RoadProfile.from(route.optString("profile"))) {
            RoadProfile.FOOT -> SpeedMode.WALK
            RoadProfile.BIKE -> SpeedMode.BIKE
            RoadProfile.CAR -> SpeedMode.DRIVE
            RoadProfile.FLY -> SpeedMode.FLY
            RoadProfile.STRAIGHT -> null
        }
        if (speed != null && PortalStore.settings(c).optString("speedMode") != speed.key) {
            PortalStore.updateSettings(c, PortalStore.obj("speedMode", speed.key))
            PortalStore.log(c, "按路线出行方式切换为${speed.label}速度（${speed.metresPerSecond} m/s）")
        }
    }

    fun startRoute(id: String? = null) {
        if (id != null) {
            if (running()) stop()
            selectRoute(id)
        }
        SimulationService.ensureReady(c)
        if (PortalStore.routeCoordinates(c)[0].size < 2) fail("请先选择一条路线")
        PortalStore.updateRuntime(c, PortalStore.obj("mode", "route"))
        SimulationService.command(c, "START")
    }

    fun pauseRoute(pause: Boolean) {
        // At the end "continue" would stop again at once: play the route again from the start.
        SimulationService.command(c, if (pause) "PAUSE" else if (PortalStore.routeFinished(c)) "START" else "RESUME")
    }

    /** once / loop / bounce. While playing, the route continues from where it is now. */
    fun setRouteRepeat(mode: String) {
        if (!PortalStore.setRouteRepeat(c, mode)) fail("重复方式无效。")
        SimulationService.command(c, "UPDATE")
    }

    fun overlay(enabled: Boolean) {
        if (enabled) {
            val r = PortalStore.runtimeFields(c, "enabled", "mode")
            if (r.optBoolean("enabled") && r.optString("mode") == "route") fail("路线运行时请先停止路线，再使用摇杆。")
            SimulationService.ensureReady(c)
            if (!Settings.canDrawOverlays(c)) fail("请先在系统设置中允许显示悬浮窗。")
        }
        OverlayService.command(c, enabled)
    }

    // ---- Records ------------------------------------------------------------------------------

    fun saveLocation(name: String, p: LatLon, address: String = ""): String =
        saveRecord("locations", PortalStore.obj("name", name, "lat", p.lat, "lon", p.lon, "address", address))

    fun renameLocation(id: String, name: String) {
        val row = find("locations", id)
        row.put("name", name)
        saveRecord("locations", row)
    }

    /** points are what playback follows; waypoints and profile record how a road route was drawn. */
    fun saveRoute(name: String, points: List<LatLon>, waypoints: List<LatLon> = points, profile: RoadProfile = RoadProfile.STRAIGHT): String {
        // Long road routes can exceed the point limit; drop points that do not change the shape.
        val kept = simplifyRoute(points, MAX_ROUTE_POINTS)
        if (kept.size < points.size) PortalStore.log(c, "路线点较多，已精简为 ${kept.size} 个点（与原路线最大偏差 ${"%.1f".format(routeSimplifyTolerance(points, kept))} 米）")
        val array = JSONArray().apply { kept.forEach { put(it.toJson()) } }
        validateRoute(array)
        val route = PortalStore.obj("name", name, "points", array)
        if (profile != RoadProfile.STRAIGHT) {
            route.put("profile", profile.id)
            if (profile.road) route.put("waypoints", JSONArray().apply { waypoints.forEach { put(it.toJson()) } })
        }
        return saveRecord("routes", route)
    }

    fun delete(key: String, id: String) = synchronized(RECORD_LOCK) {
        val old = PortalStore.items(c, key)
        val deleted = old.objects().firstOrNull { it.optString("id") == id }
        if (key == "routes" && deleted != null) {
            val runtime = PortalStore.runtimeFields(c, "routeId", "routeName", "route", "mode", "enabled")
            val selectedId = runtime.optString("routeId")
            val legacyMatch = selectedId.isEmpty() && runtime.optString("routeName") == deleted.optString("name") &&
                runtime.optJSONArray("route")?.toString() == deleted.optJSONArray("points")?.toString()
            if (selectedId == id || legacyMatch) {
                if (runtime.optBoolean("enabled") && runtime.optString("mode") == "route") stop()
                PortalStore.updateRuntime(c, PortalStore.obj("route", JSONArray(), "routeId", "", "routeName", "",
                    "routeIndex", 0, "progress", 0, "mode", "point"))
            }
        }
        val next = JSONArray()
        for (row in old.objects()) if (row.optString("id") != id) next.put(row)
        PortalStore.saveItems(c, key, next)
    }

    private fun saveRecord(key: String, value: JSONObject): String = synchronized(RECORD_LOCK) {
        val row = JSONObject(value.toString())
        val name = row.optString("name").trim()
        if (name.isEmpty() || name.length > 120) fail("名称需要 1–120 字。")
        if (key == "locations" || key == "environments") checkCoordinate(LatLon.from(row))
        val id = row.optString("id").ifEmpty { UUID.randomUUID().toString() }
        row.put("id", id).put("name", name)
        if (!row.has("createdAt")) row.put("createdAt", System.currentTimeMillis())
        val next = JSONArray().put(row)
        for (item in PortalStore.items(c, key).objects()) if (item.optString("id") != id) next.put(item)
        if (next.length() > MAX_RECORDS) fail("记录已达 $MAX_RECORDS 条，请先导出并清理。")
        PortalStore.saveItems(c, key, next)
        id
    }

    private fun find(key: String, id: String): JSONObject =
        PortalStore.items(c, key).objects().firstOrNull { it.optString("id") == id } ?: fail("记录不存在，可能已经删除。")

    // ---- Wi-Fi / cell / GNSS ------------------------------------------------------------------

    fun saveWifiPreset(name: String, ssid: String, bssid: String, id: String? = null): String {
        val row = PortalStore.obj("name", name, "ssid", ssid.trim(), "bssid", bssid.trim().lowercase(Locale.ROOT))
        if (id != null) row.put("id", id)
        validateWifi(row)
        return saveRecord("wifiPresets", row)
    }

    fun applyWifiPreset(id: String) {
        val wifi = find("wifiPresets", id)
        validateWifi(wifi)
        requireHook("Wi-Fi 模拟")
        PortalStore.clearFeature(c, "wifi")
        PortalStore.updateRuntime(c, PortalStore.obj(
            "wifi", PortalStore.obj("ssid", wifi.optString("ssid"), "bssid", wifi.optString("bssid"), "presetId", id),
            "wifiEnabled", true))
    }

    fun setWifiEnabled(enabled: Boolean) {
        if (enabled) {
            requireHook("Wi-Fi 模拟")
            validateWifi(PortalStore.runtimeFields(c, "wifi").optJSONObject("wifi"))
        }
        PortalStore.clearFeature(c, "wifi")
        PortalStore.updateRuntime(c, PortalStore.obj("wifiEnabled", enabled))
    }

    fun saveCellPreset(row: JSONObject): String {
        validateCell(row, false)
        return saveRecord("cellPresets", row)
    }

    fun applyCellPreset(id: String) {
        val cell = find("cellPresets", id)
        validateCell(cell, true)
        requireHook("基站模拟")
        PortalStore.clearFeature(c, "cell")
        PortalStore.updateRuntime(c, PortalStore.obj("cell", cell, "cells", JSONArray().put(cell), "cellEnabled", true))
        PortalStore.log(c, "基站模拟配置已保存，等待白名单目标读取；不会改变真实网络注册。")
    }

    fun setCellEnabled(enabled: Boolean) {
        if (enabled) {
            requireHook("基站模拟")
            validateCell(PortalStore.runtimeFields(c, "cell").optJSONObject("cell"), true)
        }
        PortalStore.clearFeature(c, "cell")
        PortalStore.updateRuntime(c, PortalStore.obj("cellEnabled", enabled))
    }

    fun setGnss(enabled: Boolean, count: Int) {
        if (enabled) requireGnss()
        if (count !in 4..32) fail("卫星数量应为 4–32。")
        PortalStore.clearFeature(c, "gnss")
        PortalStore.updateSettings(c, PortalStore.obj("gnssEnabled", enabled, "satelliteCount", count))
    }

    fun scanWifi(): List<ScanRow> = DeviceScanner.wifi(c).optJSONArray("results").objects().map {
        ScanRow(it.optString("ssid").ifBlank { "未命名网络" }, "${it.optString("bssid")} · ${it.optInt("rssi")} dBm · ${it.optInt("frequency")} MHz", it)
    }

    fun scanCells(): List<ScanRow> = DeviceScanner.cells(c).optJSONArray("results").objects().map {
        val type = it.optString("networkType")
        val area = if (it.has("tac")) "TAC ${it.opt("tac")}" else "LAC ${it.opt("lac")}"
        val id = it.opt("cid") ?: it.opt("nci")
        ScanRow("$type · ${carrier(it.optString("mcc"), it.optString("mnc"))}${if (it.optBoolean("registered")) " · 已注册" else " · 邻区"}",
            "$area · CID $id · ${it.optInt("rssi")} dBm", it)
    }

    // ---- Environment records ------------------------------------------------------------------

    fun captureEnvironment(name: String, p: LatLon): String {
        checkCoordinate(p)
        val env = PortalStore.obj("name", name, "lat", p.lat, "lon", p.lon, "capturedAt", System.currentTimeMillis(),
            "source", "device_radio_scan", "coordinateSource", "selected_location")
        try {
            val current = PortalStore.lastKnownLocation(c)
            if (current != null) {
                env.put("deviceLocation", PortalStore.obj("lat", current.latitude, "lon", current.longitude,
                    "timestamp", current.time, "accuracy", current.accuracy, "mock", PortalStore.isMock(current)))
                env.put("selectedDistanceFromDeviceMeters", Geo.distance(p.lat, p.lon, current.latitude, current.longitude))
            } else {
                env.put("locationWarning", "没有系统定位结果，选定坐标不证明采集设备当时位于此处。")
            }
        } catch (e: Exception) {
            env.put("locationWarning", "无法核对设备位置：${e.message}")
        }
        env.put("cells", try { DeviceScanner.cells(c) } catch (e: Exception) { PortalStore.obj("results", JSONArray(), "error", e.message) })
        env.put("wifi", try { DeviceScanner.wifi(c) } catch (e: Exception) { PortalStore.obj("results", JSONArray(), "error", e.message) })
        env.put("simulationActiveAtCapture", running())
        return saveRecord("environments", env)
    }

    /** Loads coordinate, supported GSM/LTE cells (registered first) and the first valid Wi-Fi. */
    fun applyEnvironment(id: String): String {
        requireHook("位置环境载入")
        if (running()) fail("请先停止当前模拟")
        val record = find("environments", id)
        val cells = record.optJSONObject("cells")?.optJSONArray("results").objects().filter { validCell(it) }
        val supported = JSONArray().apply { cells.take(MAX_CELLS).forEach { put(it) } }
        val chosenCell = cells.firstOrNull { it.optBoolean("registered") } ?: cells.firstOrNull()
        val chosenWifi = record.optJSONObject("wifi")?.optJSONArray("results").objects()
            .firstOrNull { runCatching { validateWifi(it) }.isSuccess }
        val patch = PortalStore.obj("lat", record.getDouble("lat"), "lon", record.getDouble("lon"),
            "cellEnabled", chosenCell != null, "wifiEnabled", chosenWifi != null,
            "environmentName", record.optString("name"), "cells", supported)
        if (chosenCell != null) patch.put("cell", chosenCell)
        if (chosenWifi != null) patch.put("wifi", PortalStore.obj("ssid", chosenWifi.optString("ssid"), "bssid", chosenWifi.optString("bssid")))
        clearLocationReports()
        for (f in listOf("cell", "wifi")) PortalStore.clearFeature(c, f)
        PortalStore.updateRuntime(c, patch)
        PortalStore.log(c, "环境配置已载入：${record.optString("name")}")
        return "已载入坐标 · 基站 ${supported.length()} 条 · Wi-Fi ${if (chosenWifi != null) "已配置" else "缺失"}"
    }

    // ---- Settings -----------------------------------------------------------------------------

    fun saveSettings(patch: JSONObject) {
        val next = JSONObject()
        for (key in listOf("jitter", "enhanced", "secondReport", "debug", "privacyAccepted", "protectWifi", "spoofEnvironment"))
            if (patch.has(key)) next.put(key, patch.getBoolean(key))
        if (patch.has("interval")) next.put("interval", number(patch, "interval", 100.0, 60000.0, "上报间隔应为 100–60,000 毫秒。").toInt())
        if (patch.has("speedMultiplier")) next.put("speedMultiplier", number(patch, "speedMultiplier", 0.01, 1000.0, "速度倍率应为 0.01–1000。"))
        if (patch.has("accuracy")) next.put("accuracy", number(patch, "accuracy", 0.0, 1_000_000.0, "精度范围为 0–1,000,000 米。"))
        if (patch.has("altitude")) next.put("altitude", number(patch, "altitude", -12000.0, 100000.0, "海拔范围为 -12,000–100,000 米。"))
        if (patch.has("backend")) {
            val n = patch.getString("backend")
            if (n !in listOf("android_mock", "lsposed")) fail("模拟模式无效。")
            if (running()) fail("请先停止当前模拟")
            next.put("backend", n)
            if (n != "lsposed") {
                next.put("gnssEnabled", false)
                PortalStore.updateRuntime(c, PortalStore.obj("wifiEnabled", false, "cellEnabled", false))
            }
        }
        if (patch.has("speedMode")) {
            val n = patch.getString("speedMode")
            if (SpeedMode.entries.none { it.key == n }) fail("速度模式无效。")
            next.put("speedMode", n)
        }
        if (patch.has("theme")) next.put("theme", patch.optString("theme").takeIf { it in listOf("light", "dark", "system") } ?: "system")
        if (patch.has("allowedPackages")) {
            val n = patch.getString("allowedPackages").trim()
            if (n.length > 4000) fail("包名列表过长。")
            for (pkg in n.split(Regex("[,\\s]+")))
                if (pkg.isNotEmpty() && !pkg.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+")))
                    fail("请输入完整包名，多个包名用逗号或换行分隔。")
            next.put("allowedPackages", n)
        }
        if (patch.has("appScope")) {
            val n = patch.getString("appScope")
            if (n != "all" && n != "selected") fail("模拟范围无效。")
            next.put("appScope", n)
        }
        if (patch.has("mapSource")) {
            val source = patch.getString("mapSource")
            if (source !in MapSource.entries.map { it.key }) fail("地图类型无效。")
            next.put("mapSource", source)
        }
        if (patch.has("mapKey")) {
            val n = patch.getString("mapKey").trim()
            if (n.isNotEmpty() && !n.matches(Regex("[A-Za-z0-9]{16,128}"))) fail("地图 Key 格式无效。")
            next.put("mapKey", n)
        }
        if (next.has("allowedPackages") || next.has("backend")) {
            clearLocationReports()
            for (f in listOf("cell", "wifi", "gnss", "location")) PortalStore.clearFeature(c, f)
        }
        if (next.has("protectWifi") || next.has("spoofEnvironment")) PortalStore.clearFeature(c, "wifi_privacy")
        PortalStore.updateSettings(c, next)
        if (patch.has("privacyAccepted") && !patch.optBoolean("privacyAccepted")) {
            stop()
            OverlayService.command(c, false)
        }
    }

    fun clearLogs() = PortalStore.saveItems(c, "logs", JSONArray())

    fun clearAll() {
        stop()
        OverlayService.command(c, false)
        for (key in RECORD_KEYS) PortalStore.saveItems(c, key, JSONArray())
        clearLogs()
    }

    // ---- Search -------------------------------------------------------------------------------

    data class Place(val name: String, val address: String, val position: LatLon)

    /** AMap with a Web service key, otherwise keyless OpenStreetMap search near [near]. */
    fun search(query: String, near: LatLon? = null): List<Place> {
        // "lat, lon" (WGS-84, as GPS reports it) goes straight to that point, offline.
        Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,，\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""").find(query)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (app.geoshift.Geo.valid(lat, lon)) return listOf(Place(String.format(java.util.Locale.ROOT, "%.6f, %.6f", lat, lon), "坐标（WGS-84）", LatLon(lat, lon)))
        }
        if (PortalStore.mapKey(c).isEmpty()) {
            requireOnline()
            return OpenGeocoder.search(query, near)
        }
        val places = MapNetwork.search(c, query).optJSONArray("results").objects().map {
            Place(it.optString("name"), it.optString("address"), LatLon.from(it))
        }
        // AMap has almost no overseas places; try OpenStreetMap before reporting nothing found.
        return places.ifEmpty { runCatching { OpenGeocoder.search(query, near) }.getOrDefault(emptyList()) }
    }

    fun reverseGeocode(p: LatLon): String {
        if (PortalStore.mapKey(c).isEmpty()) {
            requireOnline()
            // Address text is optional; a busy public server should not raise an error on every tap.
            return runCatching { OpenGeocoder.reverse(p) }.getOrDefault("")
        }
        // Address text is optional and looked up automatically as the position changes: never raise.
        return try {
            // AMap answers places it has no address for with an empty array ("[]").
            MapNetwork.reverse(c, p.lat, p.lon).optString("address").takeUnless { it.isBlank() || it == "[]" }
                ?: runCatching { OpenGeocoder.reverse(p) }.getOrDefault("")
        } catch (e: MapNetwork.AmapException) {
            // Overseas points (20011 / 20800) fall back to OpenStreetMap; other errors show no address.
            if (e.outOfCoverage()) runCatching { OpenGeocoder.reverse(p) }.getOrDefault("") else ""
        } catch (e: Exception) { "" }
    }

    private fun requireOnline() {
        if (!PortalStore.settings(c).optBoolean("privacyAccepted")) fail("请先阅读并同意隐私说明，再使用在线地图或搜索。")
    }

    fun checkMapKey(): String {
        MapNetwork.reverse(c, 39.9042, 116.4074)
        return "Web 服务 Key 验证通过，地址查询可用"
    }

    // ---- Backup -------------------------------------------------------------------------------

    fun exportTo(uri: Uri) {
        c.contentResolver.openOutputStream(uri)?.use { it.write(PortalStore.exportData(c).toString(2).toByteArray()) }
            ?: throw IOException("无法写入文件。")
    }

    fun importFrom(uri: Uri): Int {
        val bytes = c.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > 4 * 1024 * 1024) throw IOException("导入文件不能超过 4 MB。")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw IOException("无法读取文件。")
        val imported = JSONObject(String(bytes))
        if (imported.optString("format") !in listOf("geoshift", "portal-rebuild") || imported.optInt("version") != 1)
            fail("文件不是受支持的 Geoshift / Portal 备份。")
        // Validate everything first so a bad file imports nothing.
        for (key in RECORD_KEYS) {
            val rows = imported.optJSONArray(key) ?: continue
            if (rows.length() > MAX_RECORDS) fail("记录数量过多。")
            for (row in rows.objects()) {
                if (row.optString("name").isBlank()) fail("记录缺少名称。")
                when (key) {
                    "locations", "environments" -> checkCoordinate(LatLon.from(row))
                    "routes" -> validateRoute(row.optJSONArray("points"))
                    "wifiPresets" -> validateWifi(row)
                    "cellPresets" -> validateCell(row, false)
                }
            }
        }
        var count = 0
        for (key in RECORD_KEYS) {
            val rows = imported.optJSONArray(key)?.objects() ?: continue
            rows.asReversed().forEach { saveRecord(key, it); count++ }
        }
        return count
    }

    // ---- Validation ---------------------------------------------------------------------------

    private fun requireHook(feature: String) {
        val s = PortalStore.settings(c)
        if (!s.optBoolean("privacyAccepted")) fail("请先阅读并同意使用须知。")
        if (s.optString("backend") != "lsposed") fail("${feature}需要在设置中切换到 LSPosed 模式。")
        pingHook()
        if (!PortalStore.hookActive(c)) fail("未检测到LSPosed：请在 LSPosed 中启用 Geoshift、勾选「系统框架」后重启手机。")
    }

    private fun requireGnss() {
        requireHook("GNSS 模拟")
        if (Build.VERSION.SDK_INT < 30) fail("GNSS 状态模拟需要 Android 11 或更新版本。")
    }

    companion object {
        private val RECORD_LOCK = Any()
        const val MAX_RECORDS = 1000
        const val MAX_CELLS = 32
        val RECORD_KEYS = listOf("locations", "routes", "wifiPresets", "cellPresets", "environments")

        val CARRIERS = listOf(
            Triple("中国移动", "460", "00"), Triple("中国联通", "460", "01"),
            Triple("中国电信", "460", "11"), Triple("中国广电", "460", "15"),
        )

        fun carrier(mcc: String, mnc: String): String = when {
            mcc != "460" -> "MCC $mcc / MNC $mnc"
            mnc in listOf("00", "02", "04", "07", "08") -> "中国移动"
            mnc in listOf("01", "06", "09") -> "中国联通"
            mnc in listOf("03", "05", "11") -> "中国电信"
            mnc == "15" -> "中国广电"
            else -> "未知运营商"
        }

        fun checkCoordinate(p: LatLon) {
            if (!Geo.valid(p.lat, p.lon)) fail("经纬度无效。")
        }

        private fun number(a: JSONObject, key: String, min: Double, max: Double, message: String): Double {
            val n = a.getDouble(key)
            if (!n.isFinite() || n < min || n > max) fail(message)
            return n
        }

        fun validateWifi(a: JSONObject?) {
            val ssid = a?.optString("ssid")?.trim().orEmpty()
            if (ssid.isEmpty() || ssid.toByteArray().size > 32) fail("SSID 不能为空，且不能超过 32 字节。")
            if (!a!!.optString("bssid").matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")))
                fail("BSSID 格式不正确，应为xx:xx:xx:xx:xx:xx")
        }

        fun validateRoute(points: JSONArray?) {
            if (points == null || points.length() < 2 || points.length() > MAX_ROUTE_POINTS) fail("路线需要 2–10,000 个点。")
            for (p in points.objects()) checkCoordinate(LatLon.from(p))
        }

        fun validCell(cell: JSONObject?) = runCatching { validateCell(cell, true) }.isSuccess

        /** forSimulation=false also accepts other network types for archiving. */
        fun validateCell(cell: JSONObject?, forSimulation: Boolean) {
            if (cell == null) fail("请先选择或创建基站预设。")
            val type = cell.optString("networkType", "LTE").uppercase(Locale.ROOT)
            if (type !in listOf("LTE", "GSM")) {
                if (forSimulation) fail("自定义基站模拟目前支持 LTE 和 GSM；其他类型可扫描、保存和查看。")
                return
            }
            val lte = type == "LTE"
            if (!cell.optString("mcc").matches(Regex("[0-9]{3}")) || !cell.optString("mnc").matches(Regex("[0-9]{2,3}")))
                fail("MCC 应为 3 位数字（中国为460），MNC 应为 2–3 位数字。")
            val cid = cell.optLong("cid", -1)
            if (cid < 0 || cid > (if (lte) 268435455L else 65535L)) fail("小区识别码 CID 超出 $type 范围。")
            val area = if (lte) "tac" else "lac"
            val areaId = cell.optLong(area, -1)
            if (areaId !in 0..65535) fail("${area.uppercase(Locale.ROOT)} 应为 0–65535。")
            if (lte && cell.optInt("pci", -1) !in 0..503) fail("LTE PCI 应为 0–503。")
            if (cell.optInt("rssi", -85) !in -160..-20) fail("信号强度应为 -160 到 -20 dBm。")
        }
    }
}

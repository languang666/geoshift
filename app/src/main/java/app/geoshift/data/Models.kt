package app.geoshift.data

import org.json.JSONArray
import org.json.JSONObject
import app.geoshift.MapSource

/** Typed view of [app.geoshift.PortalStore.snapshot]. Raw rows are kept for round-trips. */
data class PortalState(
    val version: String,
    val settings: SettingsState,
    val runtime: RuntimeState,
    val caps: Capabilities,
    val locations: List<SavedLocation>,
    val routes: List<SavedRoute>,
    val wifiPresets: List<WifiPreset>,
    val cellPresets: List<CellPreset>,
    val environments: List<EnvironmentRecord>,
    val logs: List<LogEntry>,
    val featureStatus: Map<String, FeatureStatus>,
) {
    /** Applies a lightweight [app.geoshift.PortalStore.status] poll (runtime without route). */
    fun withStatus(status: JSONObject): PortalState {
        val light = status.optJSONObject("runtime") ?: return this
        return copy(
            runtime = RuntimeState.from(light, runtime.route),
            caps = Capabilities.from(status.optJSONObject("capabilities") ?: JSONObject()),
            featureStatus = FeatureStatus.mapFrom(status.optJSONObject("featureStatus")),
        )
    }

    companion object {
        fun from(json: JSONObject) = PortalState(
            version = json.optJSONObject("app")?.optString("version").orEmpty(),
            settings = SettingsState.from(json.optJSONObject("settings") ?: JSONObject()),
            runtime = RuntimeState.from(json.optJSONObject("runtime") ?: JSONObject(), null),
            caps = Capabilities.from(json.optJSONObject("capabilities") ?: JSONObject()),
            locations = json.optJSONArray("locations").objects().map(SavedLocation::from),
            routes = json.optJSONArray("routes").objects().map(SavedRoute::from),
            wifiPresets = json.optJSONArray("wifiPresets").objects().map(WifiPreset::from),
            cellPresets = json.optJSONArray("cellPresets").objects().map(CellPreset::from),
            environments = json.optJSONArray("environments").objects().map(EnvironmentRecord::from),
            logs = json.optJSONArray("logs").objects().map { LogEntry(it.optLong("time"), it.optString("message")) },
            featureStatus = FeatureStatus.mapFrom(json.optJSONObject("featureStatus")),
        )
    }
}

data class LatLon(val lat: Double, val lon: Double) {
    fun toJson(): JSONObject = JSONObject().put("lat", lat).put("lon", lon)

    companion object {
        fun from(o: JSONObject) = LatLon(o.optDouble("lat"), o.optDouble("lon"))
    }
}

enum class SpeedMode(val key: String, val label: String, val metresPerSecond: Double) {
    WALK("walk", "步行", 1.4), RUN("run", "跑步", 3.0), BIKE("bike", "骑行", 6.0),
    DRIVE("drive", "驾驶", 13.9), FLY("fly", "飞行", 50.0);

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: WALK
    }
}

data class SettingsState(
    val backend: String,
    val interval: Int,
    val speedMultiplier: Double,
    val speedMode: SpeedMode,
    val accuracy: Double,
    val altitude: Double,
    val jitter: Boolean,
    val enhanced: Boolean,
    val secondReport: Boolean,
    val debug: Boolean,
    val privacyAccepted: Boolean,
    val theme: String,
    val allowedPackages: String,
    val gnssEnabled: Boolean,
    val satelliteCount: Int,
    val mapKeyConfigured: Boolean,
    val mapSource: MapSource,
    val amapAndroidKeyConfigured: Boolean,
    val amapAndroidKeyRevision: Long,
    val amapSdkAccepted: Boolean,
    val protectWifi: Boolean,
    /** LSPosed: hide real Wi-Fi, cells and satellites from apps while simulating. */
    val spoofEnvironment: Boolean,
) {
    val lsposed get() = backend == "lsposed"
    val allowedList get() = allowedPackages.split(Regex("[,\\s]+")).filter { it.isNotBlank() }

    companion object {
        fun from(o: JSONObject) = SettingsState(
            backend = o.optString("backend", "android_mock"),
            interval = o.optInt("interval", 1000),
            speedMultiplier = o.optDouble("speedMultiplier", 1.0),
            speedMode = SpeedMode.of(o.optString("speedMode", "walk")),
            accuracy = o.optDouble("accuracy", 5.0),
            altitude = o.optDouble("altitude", 0.0),
            jitter = o.optBoolean("jitter"),
            enhanced = o.optBoolean("enhanced", true),
            secondReport = o.optBoolean("secondReport"),
            debug = o.optBoolean("debug"),
            privacyAccepted = o.optBoolean("privacyAccepted"),
            theme = o.optString("theme", "system"),
            allowedPackages = o.optString("allowedPackages"),
            gnssEnabled = o.optBoolean("gnssEnabled"),
            satelliteCount = o.optInt("satelliteCount", 12),
            mapKeyConfigured = o.optBoolean("mapKeyConfigured"),
            mapSource = MapSource.fromKey(o.optString("mapSource", "osm")),
            amapAndroidKeyConfigured = o.optBoolean("amapAndroidKeyConfigured"),
            amapAndroidKeyRevision = o.optLong("amapAndroidKeyRevision"),
            amapSdkAccepted = o.optBoolean("amapSdkAccepted"),
            protectWifi = o.optBoolean("protectWifi"),
            spoofEnvironment = o.optBoolean("spoofEnvironment", true) || o.optBoolean("protectWifi"),
        )
    }
}

data class RuntimeState(
    val enabled: Boolean,
    val paused: Boolean,
    val mode: String,
    val position: LatLon,
    val bearing: Double,
    val speed: Double,
    val route: List<LatLon>,
    val routeName: String,
    val routeIndex: Int,
    val progress: Double,
    val overlay: Boolean,
    val error: String,
    val wifiEnabled: Boolean,
    val wifi: JSONObject,
    val cellEnabled: Boolean,
    val cell: JSONObject,
    val cellCount: Int,
    val environmentName: String,
) {
    val routeMode get() = mode == "route"

    companion object {
        fun from(o: JSONObject, keepRoute: List<LatLon>?) = RuntimeState(
            enabled = o.optBoolean("enabled"),
            paused = o.optBoolean("paused"),
            mode = o.optString("mode", "point"),
            position = LatLon(o.optDouble("lat", 39.9042), o.optDouble("lon", 116.4074)),
            bearing = o.optDouble("bearing", 0.0),
            speed = o.optDouble("speed", 0.0),
            route = keepRoute ?: o.optJSONArray("route").objects().map(LatLon::from),
            routeName = o.optString("routeName"),
            routeIndex = o.optInt("routeIndex"),
            progress = o.optDouble("progress", 0.0),
            overlay = o.optBoolean("overlay"),
            error = o.optString("error"),
            wifiEnabled = o.optBoolean("wifiEnabled"),
            wifi = o.optJSONObject("wifi") ?: JSONObject(),
            cellEnabled = o.optBoolean("cellEnabled"),
            cell = o.optJSONObject("cell") ?: JSONObject(),
            cellCount = o.optJSONArray("cells")?.length() ?: 0,
            environmentName = o.optString("environmentName"),
        )
    }
}

data class Capabilities(
    val preciseLocation: Boolean,
    val locationEnabled: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val phoneAvailable: Boolean,
    val mockSelected: Boolean,
    val hookActive: Boolean,
    val systemHook: Boolean,
    val nearbyWifi: Boolean,
    val gnssSimulation: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = Capabilities(
            preciseLocation = o.optBoolean("preciseLocation"),
            locationEnabled = o.optBoolean("locationEnabled"),
            notifications = o.optBoolean("notifications"),
            overlay = o.optBoolean("overlay"),
            phoneAvailable = o.optBoolean("phoneAvailable"),
            mockSelected = o.optBoolean("mockSelected"),
            hookActive = o.optBoolean("hookActive"),
            systemHook = o.optBoolean("systemHook"),
            nearbyWifi = o.optBoolean("nearbyWifi"),
            gnssSimulation = o.optBoolean("gnssSimulation"),
        )
    }
}

data class SavedLocation(val id: String, val name: String, val position: LatLon, val address: String, val createdAt: Long, val raw: JSONObject) {
    companion object {
        fun from(o: JSONObject) = SavedLocation(
            o.optString("id"), o.optString("name"), LatLon.from(o), o.optString("address"), o.optLong("createdAt"), o)
    }
}

data class SavedRoute(val id: String, val name: String, val points: List<LatLon>, val createdAt: Long,
                      val profile: RoadProfile = RoadProfile.STRAIGHT, val waypointCount: Int = points.size) {
    val length: Double by lazy { routeLength(points) }

    companion object {
        fun from(o: JSONObject): SavedRoute {
            val points = o.optJSONArray("points").objects().map(LatLon::from)
            val profile = RoadProfile.from(o.optString("profile"))
            return SavedRoute(o.optString("id"), o.optString("name"), points, o.optLong("createdAt"), profile,
                if (profile == RoadProfile.STRAIGHT) points.size else o.optJSONArray("waypoints")?.length() ?: points.size)
        }
    }
}

data class WifiPreset(val id: String, val name: String, val ssid: String, val bssid: String, val raw: JSONObject) {
    companion object {
        fun from(o: JSONObject) = WifiPreset(o.optString("id"), o.optString("name"), o.optString("ssid"), o.optString("bssid"), o)
    }
}

data class CellPreset(val id: String, val name: String, val type: String, val mcc: String, val mnc: String, val raw: JSONObject) {
    val area get() = if (type == "GSM") raw.opt("lac") else raw.opt("tac")
    val cid get() = raw.opt("cid") ?: raw.opt("nci")

    companion object {
        fun from(o: JSONObject) = CellPreset(
            o.optString("id"), o.optString("name"), o.optString("networkType", "LTE"), o.optString("mcc"), o.optString("mnc"), o)
    }
}

data class EnvironmentRecord(val id: String, val name: String, val position: LatLon, val capturedAt: Long,
                             val cellCount: Int, val wifiCount: Int, val simulated: Boolean, val raw: JSONObject) {
    companion object {
        fun from(o: JSONObject) = EnvironmentRecord(
            o.optString("id"), o.optString("name"), LatLon.from(o), o.optLong("capturedAt", o.optLong("createdAt")),
            o.optJSONObject("cells")?.optJSONArray("results")?.length() ?: 0,
            o.optJSONObject("wifi")?.optJSONArray("results")?.length() ?: 0,
            o.optBoolean("simulationActiveAtCapture"), o)
    }
}

data class LogEntry(val time: Long, val message: String)

data class FeatureStatus(val ok: Boolean, val time: Long, val error: String, val pkg: String,
                         val stage: String = "read", val detail: String = "", val process: String = "") {
    companion object {
        fun mapFrom(o: JSONObject?): Map<String, FeatureStatus> {
            if (o == null) return emptyMap()
            return o.keys().asSequence().mapNotNull { key ->
                o.optJSONObject(key)?.let { key to FeatureStatus(it.optBoolean("ok"), it.optLong("time"), it.optString("error"), it.optString("package"),
                    it.optString("stage", "read"), it.optString("detail"), it.optString("process")) }
            }.toMap()
        }
    }
}

/** A row from a Wi-Fi or cell scan. */
data class ScanRow(val title: String, val subtitle: String, val raw: JSONObject)

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun routeLength(points: List<LatLon>): Double =
    points.zipWithNext { a, b -> app.geoshift.Geo.distance(a.lat, a.lon, b.lat, b.lon) }.sum()

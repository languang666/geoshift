package app.geoshift.data

import app.geoshift.core.CellSpec
import app.geoshift.core.CellType
import app.geoshift.core.Geo
import app.geoshift.core.LatLon
import app.geoshift.core.Repeat
import app.geoshift.core.RouteAnchor
import app.geoshift.core.WifiSpec
import app.geoshift.map.MapSource
import kotlinx.serialization.Serializable

/** User-facing message for a rejected operation. */
class UserError(message: String) : Exception(message)

fun fail(message: String): Nothing = throw UserError(message)

/** How simulated locations reach apps. */
enum class Backend(val label: String) {
    /** Android test providers ("模拟位置信息应用"); no root or Xposed. */
    MOCK("系统模拟位置"),
    /** System-wide hooks through an Xposed framework (libxposed API 101+). */
    XPOSED("Xposed 系统模块"),
}

enum class SpeedMode(val label: String, val metresPerSecond: Double) {
    WALK("步行", 1.4), RUN("跑步", 3.0), BIKE("骑行", 6.0), DRIVE("驾驶", 13.9), FLY("飞行", 50.0)
}

/** How a route section between two tapped points is drawn. [road]: snapped to real roads. */
enum class RoadProfile(val label: String, val road: Boolean, val osrm: String) {
    STRAIGHT("直线", false, ""),
    FOOT("步行", true, "foot"),
    BIKE("骑行", true, "bike"),
    CAR("驾车", true, "car"),
    /** Straight lines between the tapped points, played at flying speed. */
    FLY("飞行", false, "");

    /** Playback speed that suits a route drawn this way. */
    val speed: SpeedMode? get() = when (this) {
        FOOT -> SpeedMode.WALK; BIKE -> SpeedMode.BIKE; CAR -> SpeedMode.DRIVE; FLY -> SpeedMode.FLY; STRAIGHT -> null
    }
}

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

@Serializable
data class Settings(
    val backend: Backend = Backend.MOCK,
    val privacyAccepted: Boolean = false,
    /** Reporting interval, ms. */
    val interval: Int = 1000,
    val speedMode: SpeedMode = SpeedMode.WALK,
    val speedMultiplier: Double = 1.0,
    val accuracy: Double = 5.0,
    val altitude: Double = 0.0,
    val jitter: Boolean = false,
    /** Test providers: keep reporting while standing still (some apps stop listening otherwise). */
    val keepReporting: Boolean = true,
    /** Test providers: report each fix twice (helps apps that drop the first one). */
    val doubleReport: Boolean = false,
    /** Xposed: hide the real Wi-Fi, cells, satellites and BLE beacons while simulating. */
    val hideEnvironment: Boolean = true,
    val gnss: Boolean = false,
    val satellites: Int = 12,
    /** Xposed: only these apps see the simulation. */
    val onlySelected: Boolean = false,
    val packages: List<String> = emptyList(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val mapSource: MapSource = MapSource.AMAP,
    val mapTipDismissed: Boolean = false,
    val roadProfile: RoadProfile = RoadProfile.STRAIGHT,
    val searchHistory: List<String> = emptyList(),
) {
    val xposed get() = backend == Backend.XPOSED
    /** Ground speed for the movement mode and multiplier, m/s. */
    val speed get() = speedMode.metresPerSecond * speedMultiplier.coerceIn(0.01, 1000.0)
}

enum class SimMode { POINT, ROUTE }

/** The simulation as it stands. Nothing keeps running across an app process restart. */
@Serializable
data class Runtime(
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val mode: SimMode = SimMode.POINT,
    val position: LatLon = LatLon(39.9042, 116.4074),
    val bearing: Double = 0.0,
    val speed: Double = 0.0,
    val routeId: String = "",
    val routeName: String = "",
    val repeat: Repeat = Repeat.ONCE,
    /** Set while a route plays (see RoutePlan). */
    val anchor: RouteAnchor? = null,
    val progress: Double = 0.0,
    val routeIndex: Int = 0,
    val joystick: Boolean = false,
    val error: String = "",
    val wifiEnabled: Boolean = false,
    val wifi: WifiSpec? = null,
    val wifiPresetId: String = "",
    val cellEnabled: Boolean = false,
    val cells: List<CellSpec> = emptyList(),
    val cellPresetId: String = "",
    val environmentName: String = "",
    val noiseSeed: Long = 0,
) {
    val routeMode get() = mode == SimMode.ROUTE
    /** Played to the end and holding there; "continue" then starts again. */
    val routeFinished get() = routeMode && paused && repeat == Repeat.ONCE && progress >= 0.999
}

// ---- Records ------------------------------------------------------------------------------------

interface Record {
    val id: String
    val name: String
    val createdAt: Long
}

@Serializable
data class SavedPlace(
    override val id: String,
    override val name: String,
    val position: LatLon,
    val address: String = "",
    override val createdAt: Long = System.currentTimeMillis(),
) : Record

@Serializable
data class SavedRoute(
    override val id: String,
    override val name: String,
    /** What playback follows. */
    val points: List<LatLon>,
    /** The tapped points of a road route. */
    val waypoints: List<LatLon> = emptyList(),
    val profile: RoadProfile = RoadProfile.STRAIGHT,
    override val createdAt: Long = System.currentTimeMillis(),
) : Record {
    val length by lazy { Geo.routeLength(points) }
    val waypointCount get() = if (profile.road && waypoints.isNotEmpty()) waypoints.size else points.size
}

@Serializable
data class WifiPreset(
    override val id: String,
    override val name: String,
    val ssid: String,
    val bssid: String,
    override val createdAt: Long = System.currentTimeMillis(),
) : Record {
    val spec get() = WifiSpec(ssid, bssid.lowercase())
}

@Serializable
data class CellPreset(
    override val id: String,
    override val name: String,
    val cell: CellSpec,
    override val createdAt: Long = System.currentTimeMillis(),
) : Record

/** One access point from a real scan. */
@Serializable
data class ScannedWifi(val ssid: String, val bssid: String, val rssi: Int, val frequency: Int)

/** One cell from a real scan; [type] may be one the simulation cannot reproduce (NR, WCDMA, CDMA). */
@Serializable
data class ScannedCell(
    val type: String,
    val mcc: String = "",
    val mnc: String = "",
    val cid: Long? = null,
    val area: Int? = null,
    val pci: Int? = null,
    val channel: Int? = null,
    val dbm: Int = -85,
    val registered: Boolean = false,
) {
    /** The simulated form, for LTE, GSM and NR cells with complete ids (an unreported signal becomes -85 dBm). */
    fun toSpec(): CellSpec? {
        val t = when (type) { "LTE" -> CellType.LTE; "GSM" -> CellType.GSM; "NR" -> CellType.NR; else -> return null }
        val spec = CellSpec(t, mcc, mnc, cid ?: return null, area ?: return null, pci ?: 0, channel ?: 0, dbm, registered)
        return spec.takeIf { Validate.cellOk(it) } ?: spec.copy(dbm = -85).takeIf { Validate.cellOk(it) }
    }
}

/** A real radio scan saved at a place, to replay later (location + cells + Wi-Fi). */
@Serializable
data class EnvironmentRecord(
    override val id: String,
    override val name: String,
    val position: LatLon,
    override val createdAt: Long = System.currentTimeMillis(),
    val cells: List<ScannedCell> = emptyList(),
    val wifi: List<ScannedWifi> = emptyList(),
    /** Where the phone said it was when scanning, and how far that was from [position]. */
    val deviceDistance: Double? = null,
    val note: String = "",
    val simulatedAtCapture: Boolean = false,
) : Record

@Serializable
data class LogEntry(val time: Long, val message: String)

/** One diagnostics report from a hooked process. */
data class Report(val ok: Boolean, val time: Long, val stage: String, val detail: String, val process: String)

/** What the device and framework allow right now. */
data class Capabilities(
    val preciseLocation: Boolean = false,
    val locationEnabled: Boolean = false,
    val notifications: Boolean = false,
    val overlay: Boolean = false,
    val phone: Boolean = false,
    val mockSelected: Boolean = false,
    val nearbyWifi: Boolean = false,
    /** An Xposed framework service is bound (module enabled). */
    val framework: Framework? = null,
    /** system_server answered a PING within the last minute. */
    val hookActive: Boolean = false,
    val gnssSimulation: Boolean = android.os.Build.VERSION.SDK_INT >= 30,
)

data class Framework(val name: String, val version: String, val api: Int, val scope: List<String>, val remote: Boolean)

/** A place from search. */
data class Place(val name: String, val address: String, val position: LatLon)

/** A row of a real Wi-Fi or cell scan, for pickers. */
data class ScanRow<T>(val title: String, val subtitle: String, val value: T)

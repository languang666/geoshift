package app.geoshift.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the hooks need, written by the app to the framework's remote preferences and read in
 * system_server, the phone process and the Bluetooth process. Times are SystemClock.elapsedRealtime()
 * milliseconds, which every process on the device shares.
 */
@Serializable
data class SimConfig(
    /**
     * When the device booted (wall clock − elapsedRealtime, ms) as the app saw it. A config from an
     * earlier boot is ignored: after a reboot this moves by at least the whole previous uptime.
     */
    val bootAt: Long = 0,
    /** elapsedRealtime when written; a joystick step sent after this overrides [position]. */
    val writtenAt: Long = 0,
    /** Location simulation is running (LSPosed backend). */
    val active: Boolean = false,
    val paused: Boolean = false,
    val position: LatLon = LatLon(39.9042, 116.4074),
    /** Point mode: current speed (joystick) and heading. */
    val speed: Double = 0.0,
    val bearing: Double = 0.0,
    /** Set while a route plays; the hooks play it themselves from this anchor. */
    val route: RouteAnchor? = null,
    val noiseSeed: Long = 0,
    val accuracy: Double = 5.0,
    val altitude: Double = 0.0,
    val jitter: Boolean = false,
    /** How often (ms) the system pump reports a simulated fix. */
    val interval: Int = 1000,
    /** Hide the real Wi-Fi, cells and satellites from apps while location simulation runs. */
    val hideEnvironment: Boolean = true,
    /** Show the simulated sky even when location simulation is off. */
    val gnss: Boolean = false,
    val satellites: Int = 12,
    /** Simulated Wi-Fi connection / scan; applies whenever set. */
    val wifi: WifiSpec? = null,
    /** Simulated cells (first is the serving cell); applies whenever non-empty. */
    val cells: List<CellSpec> = emptyList(),
    /** Only the apps in [packages] see the simulation; otherwise every app does. */
    val selectedOnly: Boolean = false,
    val packages: List<String> = emptyList(),
) {
    val locationOn get() = active
    val environmentHidden get() = active && hideEnvironment
    val gnssOn get() = gnss || environmentHidden
    val cellsOn get() = cells.isNotEmpty() || environmentHidden
    val wifiOn get() = wifi != null || environmentHidden

    fun encode(): String = JSON.encodeToString(serializer(), this)

    /** Whether this config was written during the boot that started at [bootAt] (±[BOOT_TOLERANCE_MS]). */
    fun sameBoot(currentBootAt: Long) = bootAt > 0 && kotlin.math.abs(bootAt - currentBootAt) < BOOT_TOLERANCE_MS

    companion object {
        const val BOOT_TOLERANCE_MS = 60_000L
        /** Boot moment on this device right now. */
        fun bootMoment(wallMs: Long, elapsedMs: Long) = wallMs - elapsedMs
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(text: String): SimConfig = JSON.decodeFromString(serializer(), text)
        val OFF = SimConfig()
    }
}

enum class Repeat { ONCE, LOOP, BOUNCE }

/**
 * Route playback anchor: at [at] (elapsedRealtime ms) the route had played [distance] metres
 * (unrolled for loops), moving at base [speed] m/s. The points travel separately (see [routeId]).
 */
@Serializable
data class RouteAnchor(
    val routeId: String,
    val distance: Double = 0.0,
    val at: Long = 0,
    val speed: Double = 0.0,
    val repeat: Repeat = Repeat.ONCE,
)

@Serializable
data class WifiSpec(
    val ssid: String,
    val bssid: String,
    val rssi: Int = -48,
    val frequency: Int = 2437,
) {
    val safeRssi get() = rssi.coerceIn(-127, 0)
    val safeFrequency get() = if (frequency in 2400..7125) frequency else 2437
}

enum class CellType { LTE, GSM }

@Serializable
data class CellSpec(
    val type: CellType = CellType.LTE,
    val mcc: String,
    val mnc: String,
    val cid: Long,
    /** TAC for LTE, LAC for GSM. */
    val area: Int,
    val pci: Int = 0,
    /** EARFCN for LTE, ARFCN for GSM. */
    val channel: Int = 0,
    val dbm: Int = -85,
    val registered: Boolean = true,
)

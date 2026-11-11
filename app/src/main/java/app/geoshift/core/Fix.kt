package app.geoshift.core

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The device clocks the simulation reads; swapped for a fixed clock in tests. */
interface Clock {
    /** SystemClock.elapsedRealtime(). */
    fun elapsedMs(): Long
    fun elapsedNanos(): Long
    /** System.currentTimeMillis(). */
    fun wallMs(): Long

    companion object {
        val SYSTEM = object : Clock {
            override fun elapsedMs() = android.os.SystemClock.elapsedRealtime()
            override fun elapsedNanos() = android.os.SystemClock.elapsedRealtimeNanos()
            override fun wallMs() = System.currentTimeMillis()
        }
    }
}

/** What a real GNSS fix reports about the satellites behind it (AOSP GnssLocationProvider extras). */
data class GpsSummary(val satellites: Int, val meanCn0: Int, val maxCn0: Int)

/** A simulated location, independent of android.location.Location. */
data class Fix(
    val provider: String,
    val point: LatLon,
    val accuracy: Float,
    val wallTime: Long,
    val elapsedNanos: Long,
    val altitude: Double? = null,
    val verticalAccuracy: Float? = null,
    val speed: Float? = null,
    val speedAccuracy: Float? = null,
    val bearing: Float? = null,
    val bearingAccuracy: Float? = null,
    val gps: GpsSummary? = null,
)

/**
 * Builds the simulated fix. Real fixes are never perfectly still: position drifts by a few metres and
 * accuracy, altitude and speed wobble. The wobble follows the clock, so every app sees the same fix
 * at the same moment and batched deliveries can replay past fixes exactly.
 */
object FixBuilder {
    /** Where the simulation is at [atMs]: the route position while a route plays, else the set point. */
    fun position(config: SimConfig, plan: RoutePlan?, atMs: Long): Triple<LatLon, Double, Double> {
        val anchor = config.route
        if (anchor != null && plan != null && plan.id == anchor.routeId) {
            val p = plan.live(anchor, config.paused, atMs)
            return Triple(p.point, p.bearing, if (config.paused) 0.0 else plan.speedAt(anchor, p, atMs))
        }
        return Triple(config.position, config.bearing, if (config.paused) 0.0 else config.speed)
    }

    /**
     * The fix [provider] reports at clock reading [atMs] (elapsedRealtime). A time in the future or
     * not positive means now.
     */
    fun build(config: SimConfig, provider: String, atMs: Long, clock: Clock, plan: RoutePlan?): Fix {
        val nowClock = clock.elapsedMs()
        val now = if (atMs > nowClock || atMs <= 0) nowClock else atMs
        val age = nowClock - now
        var (point, bearing, speed) = position(config, plan, now)
        val network = provider == "network"
        val t = now / 1000.0
        val seed = config.noiseSeed
        val base = max(1.0, config.accuracy)
        // Network fixes come from cell / Wi-Fi databases and are much coarser than satellite fixes.
        val accuracy = if (network) max(base * 4, 18.0) * (1 + 0.15 * Noise.drift(t, seed, 41))
        else base * (1 + 0.2 * Noise.drift(t, seed, 13))
        // The same drift for every provider: apps that mix gps / network / fused must see one point.
        val drift = min(3.0, base * 0.35)
        var east = drift * Noise.drift(t, seed, 7)
        var north = drift * Noise.drift(t, seed, 29)
        if (config.jitter) {
            // Correlated extra motion, at most 1 m radially, without one-second steps.
            east += Noise.smooth(t, 3.7, seed, 71) / sqrt(2.0)
            north += Noise.smooth(t, 4.9, seed, 97) / sqrt(2.0)
        }
        val offset = hypot(east, north)
        if (offset > 0) point = Geo.move(point, offset, Math.toDegrees(atan2(east, north)))
        val wall = clock.wallMs() - age
        // "Now" for live fixes: the framework drops fixes older than the last one it delivered.
        val elapsedNanos = clock.elapsedNanos() - age * 1_000_000L
        if (network) return Fix(provider, point, accuracy.toFloat(), wall, elapsedNanos)
        val moving = speed > 0
        // A still receiver reports a few cm/s.
        val reported = if (moving) speed else 0.12 * (1 + Noise.drift(t, seed, 52))
        return Fix(
            provider = provider,
            point = point,
            accuracy = accuracy.toFloat(),
            wallTime = wall,
            elapsedNanos = elapsedNanos,
            altitude = config.altitude + 1.5 * Noise.drift(t, seed, 33),
            verticalAccuracy = (accuracy * 1.6).toFloat(),
            speed = reported.toFloat(),
            speedAccuracy = (0.4 + 0.2 * (1 + Noise.drift(t, seed, 61))).toFloat(),
            bearing = if (moving) bearing.toFloat() else null,
            bearingAccuracy = if (moving) 10f else null,
            gps = if (provider == "gps") Sky.summary(Sky.at(config, now)) else null,
        )
    }

    /**
     * Replacement for a real fix: keeps its moment (so a batch keeps its history) unless that moment
     * is over ten minutes old, which would make the simulated fix equally stale.
     */
    fun replacing(config: SimConfig, provider: String?, originalElapsedNanos: Long, clock: Clock, plan: RoutePlan?): Fix {
        val name = provider?.takeUnless { it.isEmpty() || it == "passive" } ?: "fused"
        var at = clock.elapsedMs()
        if (originalElapsedNanos > 0) {
            val original = originalElapsedNanos / 1_000_000L
            if (at - original < 10 * 60_000L) at = min(at, original)
        }
        return build(config, name, at, clock, plan)
    }
}

/** Satellites and their constellations, as in android.location.GnssStatus. */
object Constellation {
    const val GPS = 1
    const val GLONASS = 3
    const val BEIDOU = 5
    const val GALILEO = 6
}

data class Satellite(
    val constellation: Int,
    val svid: Int,
    val carrierHz: Float,
    val elevation: Double,
    val azimuth: Double,
    val cn0: Double,
    val used: Boolean,
)

/**
 * The simulated sky. Satellites keep fixed ids but drift across the sky over minutes and their signal
 * wobbles, as a real receiver shows; values follow the clock. GnssStatus, gps extras and NMEA are all
 * built from this, so they always agree.
 */
object Sky {
    private val CONSTELLATIONS = intArrayOf(Constellation.GPS, Constellation.BEIDOU, Constellation.GALILEO, Constellation.GLONASS)
    private val CARRIER_HZ = floatArrayOf(1575.42e6f, 1561.098e6f, 1575.42e6f, 1602.0e6f)
    private val MAX_SVID = intArrayOf(32, 63, 36, 24)

    fun count(config: SimConfig) = config.satellites.coerceIn(4, 32)
    fun usedCount(count: Int) = min(count, max(4, count * 3 / 4))

    fun at(config: SimConfig, atMs: Long): List<Satellite> {
        val count = count(config)
        val minutes = atMs / 60000.0
        val seed = config.noiseSeed
        val out = ArrayList<Satellite>(count)
        for (i in 0 until count) {
            val k = i % CONSTELLATIONS.size
            val svid = 1 + (i / CONSTELLATIONS.size * 7 + k * 3) % MAX_SVID[k]
            var carrier = CARRIER_HZ[k]
            // GLONASS satellites each use their own channel, 0.5625 MHz apart.
            if (CONSTELLATIONS[k] == Constellation.GLONASS) carrier += (svid % 14 - 7) * 0.5625e6f
            val elevation = (15 + (i * 37) % 70 + 6 * kotlin.math.sin(minutes / 40.0 + i) +
                3 * Noise.smooth(atMs / 1000.0, 180.0, seed, 101L + i)).coerceIn(5.0, 89.9)
            var azimuth = ((i * 137) % 360 + minutes * 0.5 * (if (i % 2 == 0) 1 else -1)) % 360
            if (azimuth < 0) azimuth += 360
            val signal = (18 + elevation * 0.28 + 2.5 * Noise.smooth(atMs / 1000.0, 19.7, seed, 211L + i)).coerceIn(15.0, 48.0)
            out.add(Satellite(CONSTELLATIONS[k], svid, carrier, elevation, azimuth, signal, false))
        }
        // Strongest signals are used in the fix; every consumer shares the same flags.
        val used = out.sortedByDescending { it.cn0 }.take(usedCount(count)).toSet()
        return out.map { if (it in used) it.copy(used = true) else it }
    }

    fun summary(sky: List<Satellite>): GpsSummary {
        val used = sky.filter { it.used }
        if (used.isEmpty()) return GpsSummary(0, 0, 0)
        return GpsSummary(used.size, used.sumOf { it.cn0 }.div(used.size).roundToInt(), used.maxOf { it.cn0 }.roundToInt())
    }
}

package app.geoshift.core

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS-84 point. Everything stored or sent to the hooks is WGS-84; maps convert only for display. */
@Serializable
data class LatLon(val lat: Double, val lon: Double) {
    val valid get() = Geo.valid(lat, lon)
}

/** Coordinates in degrees, distances in metres, bearings in degrees clockwise from north. */
object Geo {
    const val EARTH_RADIUS = 6371008.8
    private const val A = 6378245.0
    private const val EE = 0.006693421622965943

    fun valid(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    private fun check(lat: Double, lon: Double) =
        require(valid(lat, lon)) { "经纬度超出范围或不是有限数值" }

    fun distance(a: LatLon, b: LatLon) = distance(a.lat, a.lon, b.lat, b.lon)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        check(lat1, lon1); check(lat2, lon2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS * 2 * atan2(sqrt(max(0.0, h)), sqrt(max(0.0, 1 - min(1.0, h))))
    }

    fun bearing(a: LatLon, b: LatLon) = bearing(a.lat, a.lon, b.lat, b.lon)

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        check(lat1, lon1); check(lat2, lon2)
        val a = Math.toRadians(lat1)
        val b = Math.toRadians(lat2)
        val d = Math.toRadians(lon2 - lon1)
        return normalizeBearing(Math.toDegrees(atan2(sin(d) * cos(b), cos(a) * sin(b) - sin(a) * cos(b) * cos(d))))
    }

    fun normalizeBearing(degrees: Double): Double {
        require(degrees.isFinite()) { "方向必须是有限数值" }
        return ((degrees % 360) + 360) % 360
    }

    /** Moves along a great circle. */
    fun move(from: LatLon, metres: Double, degrees: Double): LatLon {
        check(from.lat, from.lon)
        require(metres.isFinite() && metres >= 0) { "移动距离必须是非负有限数值" }
        val angle = metres / EARTH_RADIUS
        val heading = Math.toRadians(normalizeBearing(degrees))
        val lat = Math.toRadians(from.lat)
        val lon = Math.toRadians(from.lon)
        val nextLat = asin((sin(lat) * cos(angle) + cos(lat) * sin(angle) * cos(heading)).coerceIn(-1.0, 1.0))
        val nextLon = lon + atan2(sin(heading) * sin(angle) * cos(lat), cos(angle) - sin(lat) * sin(nextLat))
        return LatLon(Math.toDegrees(nextLat), wrapLongitude(Math.toDegrees(nextLon)))
    }

    fun interpolate(a: LatLon, b: LatLon, fraction: Double): LatLon {
        require(fraction.isFinite()) { "进度必须是有限数值" }
        if (fraction <= 0) return a
        if (fraction >= 1) return b
        return move(a, distance(a, b) * fraction, bearing(a, b))
    }

    /** Smallest signed difference b - a in degrees, in (-180, 180]. */
    fun turn(a: Double, b: Double) = ((b - a) % 360 + 540) % 360 - 180

    fun wgsToGcj(p: LatLon): LatLon {
        check(p.lat, p.lon)
        if (outsideChina(p)) return p
        val (dLat, dLon) = delta(p.lat, p.lon)
        return LatLon(p.lat + dLat, p.lon + dLon)
    }

    /** Iterative inverse of the conventional GCJ-02 transform. */
    fun gcjToWgs(p: LatLon): LatLon {
        check(p.lat, p.lon)
        if (outsideChina(p)) return p
        var lat = p.lat
        var lon = p.lon
        repeat(12) {
            val c = wgsToGcj(LatLon(lat, lon))
            val dLat = c.lat - p.lat
            val dLon = c.lon - p.lon
            lat -= dLat
            lon -= dLon
            if (max(abs(dLat), abs(dLon)) < 1e-8) return LatLon(lat, lon)
        }
        return LatLon(lat, lon)
    }

    private fun outsideChina(p: LatLon) = p.lon < 72.004 || p.lon > 137.8347 || p.lat < 0.8293 || p.lat > 55.8271

    private fun delta(lat: Double, lon: Double): Pair<Double, Double> {
        val x = lon - 105
        val y = lat - 35
        var dLat = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        dLat += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        dLat += (20 * sin(y * PI) + 40 * sin(y * PI / 3)) * 2 / 3
        dLat += (160 * sin(y * PI / 12) + 320 * sin(y * PI / 30)) * 2 / 3
        var dLon = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        dLon += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        dLon += (20 * sin(x * PI) + 40 * sin(x * PI / 3)) * 2 / 3
        dLon += (150 * sin(x * PI / 12) + 300 * sin(x * PI / 30)) * 2 / 3
        val radians = Math.toRadians(lat)
        val magic = 1 - EE * sin(radians) * sin(radians)
        val s = sqrt(magic)
        return dLat * 180 / ((A * (1 - EE) / (magic * s)) * PI) to dLon * 180 / ((A / s * cos(radians)) * PI)
    }

    private fun wrapLongitude(value: Double) = ((value + 180) % 360 + 360) % 360 - 180

    fun routeLength(points: List<LatLon>) = points.zipWithNext { a, b -> distance(a, b) }.sum()
}

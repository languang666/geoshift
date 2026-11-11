package app.geoshift.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Route playback as a pure function of time, so the app and system_server compute the same position
 * without talking to each other. The points are sent once; every config carries a [RouteAnchor]
 * (distance played at a clock reading, base speed, repeat mode).
 *
 * Speed = base speed × time wave (80–120 %, closed-form integral) × pace, where pace (≤ 1) depends on
 * the position: it eases in after the start, slows for corners and eases out before the end. Both
 * stay exact by moving in "effort" units: effort(x) = ∫ 1/pace from the start, so effort advances by
 * exactly [travelled] and is mapped back to a distance through a per-route, per-speed table.
 */
class RoutePlan(val id: String, val points: List<LatLon>) {
    val open = Track(points, closed = false)
    /** Length of the open route in metres. */
    val total get() = open.length

    @Volatile private var closedTrack: Track? = null

    /** A loop drives back from the end to the start along a closing straight section. */
    fun track(repeat: Repeat): Track {
        if (repeat != Repeat.LOOP) return open
        return closedTrack ?: Track(points, closed = true).also { closedTrack = it }
    }

    /** Distance played (unrolled) at [nowMs] from an anchor; [paused] freezes it. */
    fun distanceAt(anchor: RouteAnchor, paused: Boolean, nowMs: Long): Double {
        if (paused) return anchor.distance
        val effort = travelled(anchor.speed, anchor.at, nowMs)
        if (effort <= 0) return anchor.distance
        return distanceOf(anchor.repeat, anchor.speed, effortOf(anchor.repeat, anchor.speed, anchor.distance) + effort)
    }

    fun effortOf(repeat: Repeat, v: Double, d: Double): Double {
        val t = track(repeat)
        val length = t.length
        if (length < 0.01) return max(0.0, d)
        val p = t.pace(v)
        if (d <= 0) return d
        if (repeat == Repeat.ONCE) return if (d >= length) p.total + (d - length) else p.effort(d)
        val lap = floor(d / length).toLong()
        val w = d - lap * length
        val base = lap * p.total
        if (repeat == Repeat.LOOP || (lap and 1L) == 0L) return base + p.effort(w)
        return base + p.total - p.effort(length - w) // Coming back: the route read backwards.
    }

    fun distanceOf(repeat: Repeat, v: Double, e: Double): Double {
        val t = track(repeat)
        val length = t.length
        if (length < 0.01) return max(0.0, e)
        val p = t.pace(v)
        if (e <= 0) return e
        if (repeat == Repeat.ONCE) return if (e >= p.total) length + (e - p.total) else p.distance(e)
        val lap = floor(e / p.total).toLong()
        val rest = e - lap * p.total
        if (repeat == Repeat.LOOP || (lap and 1L) == 0L) return lap * length + p.distance(rest)
        return lap * length + length - p.distance(p.total - rest)
    }

    data class Position(
        val point: LatLon,
        val bearing: Double,
        /** Unrolled distance played. */
        val distance: Double,
        /** 0–1 along the current lap. */
        val progress: Double,
        val index: Int,
        val finished: Boolean,
        /** Metres from the track start, and whether it is being played backwards. */
        val along: Double,
        val reversed: Boolean,
        /** Speed fraction here (corners, start, end); multiplies base speed × time wave. */
        val pace: Double,
    )

    /** Position after playing [distance] metres (unrolled) at base speed [v]. */
    fun at(repeat: Repeat, v: Double, distance: Double): Position {
        val t = track(repeat)
        val length = t.length
        val d = max(0.0, distance)
        var x: Double
        var reversed = false
        if (repeat == Repeat.ONCE || length < 0.01) {
            if (d >= length) {
                val end = t.point(length)
                return Position(end.at, t.finalBearing(), max(length, d), 1.0, end.index, true, length, false, 0.0)
            }
            x = d
        } else {
            val lap = floor(d / length).toLong()
            x = d - lap * length
            if (repeat == Repeat.BOUNCE && (lap and 1L) == 1L) {
                x = length - x
                reversed = true
            }
        }
        val p = t.point(x)
        val bearing = if (reversed) Geo.normalizeBearing(p.bearing + 180) else p.bearing
        val pace = if (v > 0) t.pace(v).at(x) else 1.0
        return Position(p.at, bearing, d, if (length <= 0) 1.0 else x / length, p.index, false, x, reversed, pace)
    }

    /** Position for a running config at [nowMs]. */
    fun live(anchor: RouteAnchor, paused: Boolean, nowMs: Long) =
        at(anchor.repeat, anchor.speed, distanceAt(anchor, paused, nowMs))

    /** Ground speed at [nowMs] for a running anchor. */
    fun speedAt(anchor: RouteAnchor, paused: Boolean, nowMs: Long): Double {
        if (paused) return 0.0
        val p = live(anchor, false, nowMs)
        return if (p.finished) 0.0 else anchor.speed * speedFactor(nowMs / 1000.0) * p.pace
    }

    /**
     * Anchor distance that continues from the current point after switching the repeat mode, so the
     * position does not jump.
     */
    fun reanchor(anchor: RouteAnchor, paused: Boolean, nowMs: Long, next: Repeat): Double {
        val p = at(anchor.repeat, anchor.speed, distanceAt(anchor, paused, nowMs))
        val x = min(p.along, total) // The closing section of a loop is not part of the open route.
        return if (next == Repeat.BOUNCE && p.reversed) 2 * total - x else x
    }

    companion object {
        /** Speed varies by up to this fraction of the base speed. */
        const val SPEED_SWING = 0.2
        /** Comfortable acceleration and braking, m/s². */
        const val ACCEL = 1.5
        private const val WAVE_SEED = 0.4

        fun speedFactor(seconds: Double) = 1 + SPEED_SWING * Noise.wave(seconds, WAVE_SEED)

        /** Metres travelled at base speed v, full pace, between two clock readings (ms). */
        fun travelled(v: Double, fromMs: Long, toMs: Long): Double {
            if (v <= 0 || toMs <= fromMs) return 0.0
            val a = fromMs / 1000.0
            val b = toMs / 1000.0
            return v * ((b - a) + SPEED_SWING * (Noise.waveIntegral(b, WAVE_SEED) - Noise.waveIntegral(a, WAVE_SEED)))
        }

        /** Stable id for a list of points; changes whenever any point changes. */
        fun idOf(points: List<LatLon>): String {
            var h = 1125899906842597L
            for (p in points) {
                h = 31 * h + p.lat.toRawBits()
                h = 31 * h + p.lon.toRawBits()
            }
            return points.size.toString() + "-" + java.lang.Long.toHexString(h)
        }
    }
}

/** A polyline with duplicate points removed; closed tracks end where they start. */
class Track(src: List<LatLon>, closed: Boolean) {
    val points: List<LatLon>
    val cumulative: DoubleArray
    val bearings: DoubleArray
    val length: Double
    val closed: Boolean
    @Volatile private var cachedPace: Pace? = null

    init {
        val out = ArrayList<LatLon>(src.size + 1)
        for (p in src) if (out.isEmpty() || Geo.distance(out.last(), p) >= 0.05) out.add(p)
        if (closed && out.size >= 2) {
            if (Geo.distance(out.last(), out.first()) < 0.05) out.removeAt(out.lastIndex)
            out.add(out.first())
        }
        if (out.isEmpty() && src.isNotEmpty()) out.add(src.first())
        points = out
        this.closed = closed && out.size >= 3
        cumulative = DoubleArray(out.size)
        bearings = DoubleArray(max(0, out.size - 1))
        for (i in 1 until out.size) {
            cumulative[i] = cumulative[i - 1] + Geo.distance(out[i - 1], out[i])
            bearings[i - 1] = Geo.bearing(out[i - 1], out[i])
        }
        length = if (out.isEmpty()) 0.0 else cumulative[out.size - 1]
    }

    class Point(val at: LatLon, val bearing: Double, val index: Int)

    fun finalBearing() = if (bearings.isEmpty()) 0.0 else bearings.last()

    private fun segment(x: Double): Int {
        val last = points.size - 1
        var lo = 0
        var hi = last
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulative[mid] <= x) lo = mid else hi = mid - 1
        }
        return lo.coerceIn(0, max(0, last - 1))
    }

    private fun segLength(i: Int) = cumulative[i + 1] - cumulative[i]

    /** Point and smoothed heading at [x] metres from the start (clamped). */
    fun point(x0: Double): Point {
        if (points.size < 2) return Point(points.firstOrNull() ?: LatLon(0.0, 0.0), 0.0, 0)
        val x = x0.coerceIn(0.0, length)
        val i = segment(x)
        val seg = segLength(i)
        val fraction = if (seg < 1e-6) 0.0 else (x - cumulative[i]) / seg
        return Point(Geo.interpolate(points[i], points[i + 1], fraction), heading(i, x), i)
    }

    /** Segment heading, blended with the neighbour's near each end so turns are gradual. */
    private fun heading(i: Int, x: Double): Double {
        val segments = bearings.size
        val own = bearings[i]
        val prev = if (i > 0) i - 1 else if (closed) segments - 1 else -1
        val next = if (i < segments - 1) i + 1 else if (closed) 0 else -1
        val fromStart = x - cumulative[i]
        val toEnd = cumulative[i + 1] - x
        if (prev >= 0) {
            val w = min(TURN_BLEND, min(segLength(prev), segLength(i)) / 2)
            if (w > 0.01 && fromStart < w) return blend(bearings[prev], own, 0.5 + fromStart / (2 * w))
        }
        if (next >= 0) {
            val w = min(TURN_BLEND, min(segLength(next), segLength(i)) / 2)
            if (w > 0.01 && toEnd < w) return blend(own, bearings[next], 0.5 - toEnd / (2 * w))
        }
        return own
    }

    private fun blend(a: Double, b: Double, t: Double) = Geo.normalizeBearing(a + Geo.turn(a, b) * t)

    /** Raw point at [x], wrapping on closed tracks. */
    private fun raw(x0: Double): LatLon {
        val x = if (closed) ((x0 % length) + length) % length else x0.coerceIn(0.0, length)
        val i = segment(x)
        val seg = segLength(i)
        return Geo.interpolate(points[i], points[i + 1], if (seg < 1e-6) 0.0 else (x - cumulative[i]) / seg)
    }

    /** Turn at vertex k in degrees (0 = straight on, 180 = U-turn), measured over TURN_SPAN. */
    private fun turn(k: Int): Double {
        val c = cumulative[k]
        val back = raw(if (closed) c - TURN_SPAN else max(0.0, c - TURN_SPAN))
        val fwd = raw(if (closed) c + TURN_SPAN else min(length, c + TURN_SPAN))
        if (Geo.distance(back, points[k]) < 0.5 || Geo.distance(points[k], fwd) < 0.5) return 0.0
        return abs(Geo.turn(Geo.bearing(back, points[k]), Geo.bearing(points[k], fwd)))
    }

    /** Speed profile for base speed v; cached for the last speed used. */
    fun pace(v: Double): Pace {
        cachedPace?.let { if (it.v == v) return it }
        return Pace(this, v).also { cachedPace = it }
    }

    /** A slowdown: pace falls linearly to [min] at [at], over [before] metres before and [after] after. */
    private class Dip(val at: Double, val before: Double, val after: Double, val min: Double) {
        val left get() = at - before
        val right get() = at + after
        fun value(x: Double): Double =
            if (x < at) { if (before <= 0) 1.0 else min + (1 - min) * (at - x) / before }
            else { if (after <= 0) (if (x == at) min else 1.0) else min + (1 - min) * (x - at) / after }
    }

    /**
     * Piecewise-linear pace over the track and its effort integral. Pace is evaluated at every dip
     * break point and taken as linear between them, so effort and its inverse are closed-form.
     */
    class Pace internal constructor(t: Track, val v: Double) {
        private val x: DoubleArray
        private val g: DoubleArray
        private val e: DoubleArray
        val total: Double

        init {
            val length = t.length
            val dips = ArrayList<Dip>()
            if (v > 0 && length > 0.01) {
                if (!t.closed) {
                    // Ease in from a walking pace and ease out again before the end.
                    val startPace = (0.5 / v).coerceIn(0.05, 1.0)
                    val ramp = min(length / 3, max(0.5, (v * v - 0.25) / (2 * RoutePlan.ACCEL)))
                    if (startPace < 1) {
                        dips.add(Dip(0.0, 0.0, ramp, startPace))
                        dips.add(Dip(length, ramp, 0.0, startPace))
                    }
                }
                // Interior vertices; on a closed track also the start, where laps join.
                val last = t.points.size - 1
                for (k in (if (t.closed) 0 else 1) until last) {
                    val angle = t.turn(k)
                    if (angle < 20) continue
                    // Corner speed: never slower than walking, at most a 70 % cut for a U-turn.
                    val corner = max(min(v, 1.4), v * (1 - 0.7 * angle / 180))
                    if (corner >= v * 0.98) continue
                    val brake = max(3.0, (v * v - corner * corner) / (2 * RoutePlan.ACCEL))
                    val c = t.cumulative[k]
                    dips.add(Dip(c, brake, brake, corner / v))
                    if (t.closed && k == 0) dips.add(Dip(length, brake, brake, corner / v))
                }
            }
            val knots = ArrayList<Double>(2 + dips.size * 3)
            knots.add(0.0); knots.add(length)
            for (d in dips) {
                knots.add(d.left.coerceIn(0.0, length))
                knots.add(d.at.coerceIn(0.0, length))
                knots.add(d.right.coerceIn(0.0, length))
            }
            knots.sort()
            val unique = ArrayList<Double>(knots.size)
            for (k in knots) if (unique.isEmpty() || k - unique.last() > 1e-6) unique.add(k)
            x = unique.toDoubleArray()
            g = DoubleArray(x.size)
            dips.sortBy { it.left }
            var span = 0.0
            for (d in dips) span = max(span, d.before + d.after)
            val lefts = DoubleArray(dips.size) { dips[it].left }
            for (i in x.indices) {
                var value = 1.0
                var j = lowerBound(lefts, x[i] - span - 1e-9)
                while (j < lefts.size && lefts[j] <= x[i] + 1e-9) {
                    val d = dips[j]
                    if (x[i] <= d.right + 1e-9) value = min(value, d.value(x[i]))
                    j++
                }
                g[i] = if (value > 1 - 1e-9) 1.0 else max(0.05, value)
            }
            e = DoubleArray(x.size)
            for (i in 1 until x.size) e[i] = e[i - 1] + pieceEffort(x[i] - x[i - 1], g[i - 1], g[i], x[i] - x[i - 1])
            total = if (x.isEmpty()) 0.0 else e.last()
        }

        private fun lowerBound(a: DoubleArray, key: Double): Int {
            var lo = 0
            var hi = a.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (a[mid] < key) lo = mid + 1 else hi = mid
            }
            return lo
        }

        private fun piece(a: DoubleArray, value: Double): Int {
            var lo = 0
            var hi = a.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (a[mid] <= value) lo = mid else hi = mid - 1
            }
            return lo.coerceIn(0, max(0, a.size - 2))
        }

        /** Pace at [pos] metres. */
        fun at(pos: Double): Double {
            if (x.size < 2) return 1.0
            val i = piece(x, pos)
            val h = x[i + 1] - x[i]
            val f = if (h <= 0) 0.0 else ((pos - x[i]) / h).coerceIn(0.0, 1.0)
            return g[i] + (g[i + 1] - g[i]) * f
        }

        /** Effort from the start to [pos] metres. */
        fun effort(pos: Double): Double {
            if (x.size < 2) return pos
            if (pos <= 0) return 0.0
            if (pos >= x.last()) return total
            val i = piece(x, pos)
            return e[i] + pieceEffort(pos - x[i], g[i], g[i + 1], x[i + 1] - x[i])
        }

        /** Metres from the start after [effort]. */
        fun distance(effort: Double): Double {
            if (x.size < 2) return effort
            if (effort <= 0) return 0.0
            if (effort >= total) return x.last()
            val i = piece(e, effort)
            return x[i] + pieceDistance(effort - e[i], g[i], g[i + 1], x[i + 1] - x[i])
        }

        private companion object {
            /** Effort over the first [d] metres of a piece of length h whose pace goes linearly g0 → g1. */
            fun pieceEffort(d: Double, g0: Double, g1: Double, h: Double): Double {
                if (h <= 0 || d <= 0) return 0.0
                val slope = (g1 - g0) / h
                if (abs(slope) < 1e-12) return d / g0
                return ln((g0 + slope * d) / g0) / slope
            }

            fun pieceDistance(effort: Double, g0: Double, g1: Double, h: Double): Double {
                if (h <= 0 || effort <= 0) return 0.0
                val slope = (g1 - g0) / h
                if (abs(slope) < 1e-12) return min(h, effort * g0)
                return min(h, g0 * (exp(effort * slope) - 1) / slope)
            }
        }
    }

    companion object {
        /** Bearings blend over this distance on each side of a vertex instead of snapping. */
        const val TURN_BLEND = 8.0
        /** Turning is measured between the directions this far before and after a vertex. */
        const val TURN_SPAN = 15.0
    }
}

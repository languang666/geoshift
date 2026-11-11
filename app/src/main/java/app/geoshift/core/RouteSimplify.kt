package app.geoshift.core

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Douglas–Peucker on a local flat projection: keeps the route's shape and drops points that lie
 * within the tolerance of the line through their neighbours. The tolerance starts at 1 m and
 * doubles until the route fits [limit] points. Short routes come back unchanged.
 */
fun simplifyRoute(points: List<LatLon>, limit: Int): List<LatLon> {
    if (points.size <= limit || points.size < 3) return points
    var tolerance = 1.0
    while (true) {
        val kept = douglasPeucker(points, tolerance)
        if (kept.size <= limit || tolerance > 5_000) return kept
        tolerance *= 2
    }
}

/** Largest distance (m) from a dropped point to the simplified line; for the log message. */
fun routeSimplifyTolerance(original: List<LatLon>, kept: List<LatLon>): Double {
    if (kept.size >= original.size) return 0.0
    val proj = Projection(original)
    val keptSet = kept.toHashSet()
    var worst = 0.0
    var from = 0
    for (i in 1 until original.size) {
        if (original[i] !in keptSet) continue
        for (j in from + 1 until i) worst = maxOf(worst, proj.offset(original[j], original[from], original[i]))
        from = i
    }
    return worst
}

private class Projection(points: List<LatLon>) {
    private val k = cos(Math.toRadians(points.sumOf { it.lat } / points.size)) * 111_320.0
    fun x(p: LatLon) = p.lon * k
    fun y(p: LatLon) = p.lat * 110_574.0

    /** Distance (m) from p to the segment a–b. */
    fun offset(p: LatLon, a: LatLon, b: LatLon): Double {
        val ax = x(a); val ay = y(a)
        val dx = x(b) - ax; val dy = y(b) - ay
        val px = x(p) - ax; val py = y(p) - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9) return hypot(px, py)
        val t = ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - t * dx, py - t * dy)
    }
}

private fun douglasPeucker(points: List<LatLon>, tolerance: Double): List<LatLon> {
    val proj = Projection(points)
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    val stack = ArrayDeque<IntArray>()
    stack.addLast(intArrayOf(0, points.lastIndex))
    while (stack.isNotEmpty()) {
        val (from, to) = stack.removeLast().let { it[0] to it[1] }
        var worst = -1.0
        var index = -1
        for (i in from + 1 until to) {
            val d = proj.offset(points[i], points[from], points[to])
            if (d > worst) { worst = d; index = i }
        }
        if (index >= 0 && worst > tolerance) {
            keep[index] = true
            stack.addLast(intArrayOf(from, index))
            stack.addLast(intArrayOf(index, to))
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

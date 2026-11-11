package app.geoshift.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RoutePlanTest {
    /** An L-shaped route: 300 m north, then 300 m east. */
    private val start = LatLon(39.9, 116.4)
    private val corner = Geo.move(start, 300.0, 0.0)
    private val end = Geo.move(corner, 300.0, 90.0)
    private val plan = RoutePlan(RoutePlan.idOf(listOf(start, corner, end)), listOf(start, corner, end))

    @Test fun idChangesWithPoints() {
        assertFalse(plan.id == RoutePlan.idOf(listOf(start, end)))
        assertEquals(plan.id, RoutePlan.idOf(listOf(start, corner, end)))
    }

    @Test fun effortAndDistanceAreInverse() {
        for (repeat in Repeat.entries) for (v in listOf(1.4, 13.9, 50.0)) {
            var d = 0.0
            while (d < plan.total * 3) {
                val e = plan.effortOf(repeat, v, d)
                assertEquals("$repeat v=$v d=$d", d, plan.distanceOf(repeat, v, e), 1e-6)
                d += 7.3
            }
        }
    }

    @Test fun onceStopsAtTheEnd() {
        val anchor = RouteAnchor(plan.id, 0.0, 0, 13.9, Repeat.ONCE)
        val p = plan.live(anchor, false, 10 * 60_000L)
        assertTrue(p.finished)
        assertTrue(Geo.distance(p.point, end) < 0.5)
        assertEquals(0.0, plan.speedAt(anchor, false, 10 * 60_000L), 0.0)
    }

    @Test fun slowsDownForTheCorner() {
        val v = 13.9
        val pace = plan.open.pace(v)
        assertTrue(pace.at(300.0) < 0.75)
        assertEquals(1.0, pace.at(150.0), 1e-9)
        // Starts from walking pace.
        assertTrue(pace.at(0.0) < 0.1)
    }

    @Test fun pauseFreezesAndResumeContinues() {
        val anchor = RouteAnchor(plan.id, 0.0, 0, 3.0, Repeat.ONCE)
        val at = 60_000L
        val d = plan.distanceAt(anchor, false, at)
        val paused = anchor.copy(distance = d, at = at)
        assertEquals(d, plan.distanceAt(paused, true, at + 30_000), 0.0)
        val resumed = paused.copy(at = at + 30_000)
        assertTrue(plan.distanceAt(resumed, false, at + 31_000) > d)
    }

    @Test fun loopAndBounceKeepMoving() {
        for (repeat in listOf(Repeat.LOOP, Repeat.BOUNCE)) {
            val anchor = RouteAnchor(plan.id, 0.0, 0, 13.9, repeat)
            val p = plan.live(anchor, false, 30 * 60_000L)
            assertFalse(p.finished)
            assertTrue(plan.speedAt(anchor, false, 30 * 60_000L) > 0)
        }
    }

    @Test fun reanchorDoesNotJump() {
        val anchor = RouteAnchor(plan.id, 0.0, 0, 3.0, Repeat.BOUNCE)
        for (t in listOf(100_000L, 250_000L, 333_000L)) {
            val before = plan.live(anchor, false, t).point
            for (next in Repeat.entries) {
                val moved = RouteAnchor(plan.id, plan.reanchor(anchor, false, t, next), t, 3.0, next)
                assertTrue("$next at $t", Geo.distance(before, plan.live(moved, false, t).point) < 0.5)
            }
        }
    }

    @Test fun travelledMatchesAverageSpeed() {
        // The ±20 % wave averages out over a long time.
        val hour = RoutePlan.travelled(10.0, 0, 3_600_000L)
        assertTrue(abs(hour - 36_000) < 36_000 * 0.05)
        assertEquals(0.0, RoutePlan.travelled(10.0, 5, 5), 0.0)
    }
}

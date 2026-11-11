package app.geoshift.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixTest {
    private val config = SimConfig(boot = 3, active = true, position = LatLon(31.2304, 121.4737), noiseSeed = 42, accuracy = 5.0)

    @Test fun sameMomentSameFix() {
        val clock = TestClock()
        val a = FixBuilder.build(config, "gps", 0, clock, null)
        val b = FixBuilder.build(config, "fused", 0, clock, null)
        // Every provider reports the same point, so apps mixing them see one position.
        assertEquals(a.point, b.point)
        assertTrue(Geo.distance(a.point, config.position) <= 3.0)
    }

    @Test fun networkIsCoarseAndHasNoMotion() {
        val f = FixBuilder.build(config, "network", 0, TestClock(), null)
        assertTrue(f.accuracy >= 18f)
        assertNull(f.altitude)
        assertNull(f.speed)
        assertNull(f.gps)
    }

    @Test fun gpsCarriesSatelliteSummaryMatchingTheSky() {
        val clock = TestClock()
        val f = FixBuilder.build(config, "gps", 0, clock, null)
        val sky = Sky.at(config, clock.elapsed)
        assertNotNull(f.gps)
        assertEquals(sky.count { it.used }, f.gps!!.satellites)
        // Standing still: a few cm/s, no bearing.
        assertTrue(f.speed!! < 0.5f)
        assertNull(f.bearing)
    }

    @Test fun historicalFixesKeepTheirMoment() {
        val clock = TestClock()
        val past = clock.elapsed - 4000
        val f = FixBuilder.replacing(config, "gps", past * 1_000_000L, clock, null)
        assertEquals(past * 1_000_000L, f.elapsedNanos)
        assertEquals(clock.wall - 4000, f.wallTime)
        // A fix older than ten minutes would make the simulated one equally stale: use now.
        val stale = FixBuilder.replacing(config, "gps", (clock.elapsed - 11 * 60_000) * 1_000_000L, clock, null)
        assertEquals(clock.elapsedNanos(), stale.elapsedNanos)
        assertEquals("fused", FixBuilder.replacing(config, "passive", 0, clock, null).provider)
    }

    @Test fun routeMovesTheFix() {
        val a = LatLon(39.9, 116.4)
        val b = Geo.move(a, 2000.0, 45.0)
        val plan = RoutePlan("r", listOf(a, b))
        val clock = TestClock(elapsed = 1_000_000L)
        val c = config.copy(route = RouteAnchor("r", 0.0, clock.elapsed, 10.0))
        val first = FixBuilder.build(c, "gps", 0, clock, plan)
        clock.advance(60_000)
        val later = FixBuilder.build(c, "gps", 0, clock, plan)
        assertTrue(Geo.distance(first.point, later.point) > 300)
        assertNotNull(later.bearing)
        assertEquals(45.0, later.bearing!!.toDouble(), 2.0)
        // A route the process does not hold falls back to the set position.
        val unknown = FixBuilder.build(c, "gps", 0, clock, null)
        assertTrue(Geo.distance(unknown.point, c.position) <= 3.0)
    }

    @Test fun configRoundTripsAndIgnoresUnknownKeys() {
        val c = config.copy(wifi = WifiSpec("Cafe", "02:11:22:33:44:55"), cells = listOf(CellSpec(mcc = "460", mnc = "00", cid = 1234, area = 5)),
            selectedOnly = true, packages = listOf("com.example"), route = RouteAnchor("x", 1.0, 2, 3.0, Repeat.LOOP))
        assertEquals(c, SimConfig.decode(c.encode()))
        val withExtra = c.encode().dropLast(1) + ",\"future\":1}"
        assertEquals(c, SimConfig.decode(withExtra))
        assertTrue(c.cellsOn && c.wifiOn && c.environmentHidden)
        assertTrue(!SimConfig.OFF.locationOn && !SimConfig.OFF.cellsOn)
    }
}

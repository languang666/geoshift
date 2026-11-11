package app.geoshift.core

import app.geoshift.map.MapSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class GeoTest {
    @Test fun distanceAndBearing() {
        assertEquals(0.0, Geo.distance(LatLon(0.0, 0.0), LatLon(0.0, 0.0)), 0.0)
        assertEquals(111195.0, Geo.distance(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), 2.0)
        assertEquals(0.0, Geo.bearing(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 1e-4)
        assertEquals(90.0, Geo.bearing(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), 1e-4)
        assertFalse(Geo.valid(Double.NaN, 0.0))
        assertFalse(Geo.valid(0.0, 181.0))
    }

    @Test fun interpolateAcrossDateLine() {
        val mid = Geo.interpolate(LatLon(0.0, 179.0), LatLon(0.0, -179.0), 0.5)
        assertEquals(180.0, abs(mid.lon), 1e-4)
    }

    @Test fun moveThenMeasure() {
        val r = Random(923)
        repeat(300) {
            val a = LatLon(r.nextDouble(-80.0, 80.0), r.nextDouble(-179.0, 179.0))
            val d = r.nextDouble(0.0, 50_000.0)
            val b = Geo.move(a, d, r.nextDouble(0.0, 360.0))
            assertEquals(d, Geo.distance(a, b), maxOf(0.01, d * 1e-9))
        }
    }

    @Test fun gcjRoundTripAndOutsideChina() {
        val london = LatLon(51.5, -0.12)
        assertEquals(london, Geo.wgsToGcj(london))
        for (p in listOf(LatLon(39.9042, 116.4074), LatLon(31.2304, 121.4737), LatLon(22.5431, 114.0579), LatLon(30.5728, 104.0668))) {
            val shifted = Geo.distance(p, Geo.wgsToGcj(p))
            assertTrue(shifted in 100.0..1000.0)
            // Switching map layers back and forth must not walk the point away.
            var q = p
            repeat(50) { q = MapSource.AMAP.toWgs(MapSource.AMAP.toDisplay(q)) }
            assertTrue(Geo.distance(p, q) < 0.2)
        }
    }

    @Test fun turnWrapsAround() {
        assertEquals(2.0, Geo.turn(359.0, 1.0), 1e-9)
        assertEquals(-2.0, Geo.turn(1.0, 359.0), 1e-9)
    }
}

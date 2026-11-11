package app.geoshift.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaTest {
    private val config = SimConfig(active = true, position = LatLon(22.5431, 114.0579), satellites = 16, noiseSeed = 7)
    private val clock = TestClock()
    private val fix = FixBuilder.build(config, "gps", 0, clock, null)
    private val sky = Sky.at(config, clock.elapsed)

    private fun valid(sentence: String): Boolean {
        val body = sentence.trimEnd().substringBefore('*')
        val sum = body.drop(1).fold(0) { a, c -> a xor c.code }
        return sentence.trimEnd().substringAfter('*') == String.format("%02X", sum)
    }

    @Test fun replacesPositionSentencesWithSameType() {
        for (type in listOf("GGA", "RMC", "VTG", "GLL", "GNS", "ZDA")) {
            val out = Nmea.replace("\$GP$type,real,data*00", fix, sky)
            assertEquals(1, out.size)
            assertTrue(out[0].startsWith("\$GP$type,"))
            assertTrue(valid(out[0]))
        }
        val gga = Nmea.replace("\$GNGGA,x*00", fix, sky).single()
        assertTrue(gga.contains("2232.5") && gga.contains(",N,") && gga.contains(",E,"))
    }

    @Test fun dropsProprietaryTextAndUnknown() {
        for (line in listOf("\$PGLOR,1*00", "\$GPTXT,01,01,02,ANTSTATUS=OK*00", "\$GQGSV,1,1,00*00", "garbage", null))
            assertTrue(Nmea.replace(line, fix, sky).isEmpty())
    }

    @Test fun gsvPartCountMatchesWhatArrives() {
        val gps = sky.filter { it.constellation == Constellation.GPS }
        val parts = maxOf(1, (gps.size + 3) / 4)
        // The real chip says it sends one part; the simulated sky needs [parts]: all follow the first.
        val out = Nmea.replace("\$GPGSV,1,1,03,01,40,083,46*00", fix, sky)
        assertEquals(parts, out.size)
        out.forEachIndexed { i, s ->
            assertTrue(s.startsWith("\$GPGSV,$parts,${i + 1},"))
            assertTrue(valid(s))
        }
        // A part the simulated sky does not have is dropped.
        assertTrue(Nmea.replace("\$GPGSV,9,9,40*00", fix, sky).isEmpty())
    }

    @Test fun gsaListsOnlyUsedSatellites() {
        val gsa = Nmea.replace("\$GNGSA,A,3*00", fix, sky).single()
        val ids = gsa.split(',').drop(3).take(12).filter { it.isNotEmpty() }
        assertEquals(minOf(12, sky.count { it.used }), ids.size)
    }

    @Test fun skyIsDeterministicAndAgreesWithSummary() {
        assertEquals(sky, Sky.at(config, clock.elapsed))
        assertEquals(Sky.usedCount(16), sky.count { it.used })
        assertTrue(sky.all { it.elevation in 5.0..89.9 && it.cn0 in 15.0..48.0 })
    }
}

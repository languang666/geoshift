package app.geoshift.core

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * NMEA sentences for the simulated fix and sky. A real GNSS chip keeps sending NMEA while apps
 * listen; dropping it all would leave "a GPS fix but no NMEA", which is easy to spot. Each real
 * sentence is swapped for the simulated sentence of the same talker and type, so the chip's rhythm
 * is kept. Proprietary, free-text and unknown sentences are dropped.
 */
object Nmea {
    /**
     * Simulated sentences for one real sentence: usually one, none to drop it, and more for the last
     * GSV part when the simulated sky needs more parts than the real chip sent.
     */
    fun replace(real: String?, fix: Fix, sky: List<Satellite>): List<String> {
        val line = real?.trim() ?: return emptyList()
        if (line.length < 7 || line[0] != '$' || line[1] == 'P') return emptyList()
        val talker = line.substring(1, 3)
        val type = line.substring(3, 6)
        val constellation = constellationOf(talker)
        val body = when (type) {
            "GGA" -> gga(talker, fix, sky)
            "RMC" -> rmc(talker, fix)
            "VTG" -> vtg(talker, fix)
            "GLL" -> gll(talker, fix)
            "GNS" -> gns(talker, fix, sky)
            "ZDA" -> zda(talker, fix)
            "GSA" -> if (constellation < 0) return emptyList() else gsa(talker, fix, sky, constellation)
            "GSV" -> return if (constellation < 0) emptyList() else gsv(talker, sky, line, constellation)
            else -> return emptyList()
        }
        return listOf(withChecksum(body))
    }

    fun withChecksum(body: String): String {
        var sum = 0
        for (i in 1 until body.length) sum = sum xor body[i].code
        return body + String.format(Locale.US, "*%02X", sum) + "\r\n"
    }

    /** Constellation of a talker id, 0 for combined (GN), or -1 for one the simulated sky lacks. */
    fun constellationOf(talker: String) = when (talker) {
        "GP" -> Constellation.GPS
        "GL" -> Constellation.GLONASS
        "GA" -> Constellation.GALILEO
        "BD", "GB" -> Constellation.BEIDOU
        "GN" -> 0
        else -> -1
    }

    /** Satellite number as NMEA writes it (GLONASS slots are 65–96). */
    private fun nmeaId(s: Satellite) = if (s.constellation == Constellation.GLONASS) 64 + s.svid else s.svid

    private fun utc(f: Fix) = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { timeInMillis = f.wallTime }

    private fun time(f: Fix): String {
        val c = utc(f)
        return String.format(Locale.US, "%02d%02d%02d.%02d", c[Calendar.HOUR_OF_DAY], c[Calendar.MINUTE], c[Calendar.SECOND], c[Calendar.MILLISECOND] / 10)
    }

    private fun date(f: Fix): String {
        val c = utc(f)
        return String.format(Locale.US, "%02d%02d%02d", c[Calendar.DAY_OF_MONTH], c[Calendar.MONTH] + 1, c[Calendar.YEAR] % 100)
    }

    private fun lat(v: Double): String {
        val a = abs(v)
        val d = a.toInt()
        return String.format(Locale.US, "%02d%08.5f,%s", d, (a - d) * 60, if (v >= 0) "N" else "S")
    }

    private fun lon(v: Double): String {
        val a = abs(v)
        val d = a.toInt()
        return String.format(Locale.US, "%03d%08.5f,%s", d, (a - d) * 60, if (v >= 0) "E" else "W")
    }

    private fun hdop(f: Fix) = min(5.0, max(0.6, f.accuracy / 5.0))
    private fun used(sky: List<Satellite>) = sky.count { it.used }
    private fun knots(f: Fix) = (f.speed ?: 0f) * 1.943844
    private fun course(f: Fix) = f.bearing?.let { String.format(Locale.US, "%.1f", it) } ?: ""
    private fun altitude(f: Fix) = f.altitude ?: 0.0

    private fun gga(t: String, f: Fix, sky: List<Satellite>) = String.format(Locale.US, "$%sGGA,%s,%s,%s,1,%02d,%.1f,%.1f,M,0.0,M,,",
        t, time(f), lat(f.point.lat), lon(f.point.lon), used(sky), hdop(f), altitude(f))

    private fun rmc(t: String, f: Fix) = String.format(Locale.US, "$%sRMC,%s,A,%s,%s,%.3f,%s,%s,,,A",
        t, time(f), lat(f.point.lat), lon(f.point.lon), knots(f), course(f), date(f))

    private fun vtg(t: String, f: Fix) = String.format(Locale.US, "$%sVTG,%s,T,,M,%.3f,N,%.3f,K,A", t, course(f), knots(f), knots(f) * 1.852)

    private fun gll(t: String, f: Fix) = String.format(Locale.US, "$%sGLL,%s,%s,%s,A,A", t, lat(f.point.lat), lon(f.point.lon), time(f))

    private fun gns(t: String, f: Fix, sky: List<Satellite>) = String.format(Locale.US, "$%sGNS,%s,%s,%s,AAAA,%02d,%.1f,%.1f,0.0,,,V",
        t, time(f), lat(f.point.lat), lon(f.point.lon), used(sky), hdop(f), altitude(f))

    private fun zda(t: String, f: Fix): String {
        val c = utc(f)
        return String.format(Locale.US, "$%sZDA,%s,%02d,%02d,%04d,00,00", t, time(f), c[Calendar.DAY_OF_MONTH], c[Calendar.MONTH] + 1, c[Calendar.YEAR])
    }

    private fun gsa(t: String, f: Fix, sky: List<Satellite>, constellation: Int): String {
        val b = StringBuilder("$").append(t).append("GSA,A,3")
        var n = 0
        for (s in sky) {
            if (!s.used || (constellation != 0 && s.constellation != constellation) || n == 12) continue
            b.append(',').append(String.format(Locale.US, "%02d", nmeaId(s)))
            n++
        }
        while (n < 12) { b.append(','); n++ }
        val h = hdop(f)
        return b.append(String.format(Locale.US, ",%.1f,%.1f,%.1f", h * 1.4, h, h * 1.1)).toString()
    }

    /**
     * The simulated GSV part with the same number as the real one (none when the simulated sky has
     * fewer parts). On the real chip's last part, the parts it never sent follow, so the part count in
     * the header always matches the parts that arrive.
     */
    private fun gsv(t: String, sky: List<Satellite>, real: String, constellation: Int): List<String> {
        val view = sky.filter { constellation == 0 || it.constellation == constellation }
        val f = real.split(',', '*')
        val realParts = f.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        val part = f.getOrNull(2)?.toIntOrNull() ?: return emptyList()
        val parts = max(1, (view.size + 3) / 4)
        if (part < 1 || part > parts) return emptyList()
        val last = if (part == realParts) parts else part
        return (part..last).map { n ->
            val b = StringBuilder(String.format(Locale.US, "$%sGSV,%d,%d,%02d", t, parts, n, view.size))
            for (i in (n - 1) * 4 until min(view.size, n * 4)) {
                val s = view[i]
                b.append(String.format(Locale.US, ",%02d,%02d,%03d,%02d", nmeaId(s), s.elevation.roundToLong(),
                    s.azimuth.roundToLong() % 360, s.cn0.roundToLong()))
            }
            withChecksum(b.toString())
        }
    }
}

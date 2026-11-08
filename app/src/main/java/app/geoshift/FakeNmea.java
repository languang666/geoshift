package app.geoshift;

import android.location.GnssStatus;
import android.location.Location;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONObject;

/**
 * NMEA sentences for the simulated fix and sky. A real GNSS chip keeps sending NMEA while apps listen;
 * dropping it all would leave "a GPS fix but no NMEA", which is easy to spot. Each real sentence is
 * swapped for the simulated sentence of the same talker and type, so the rhythm of the chip is kept.
 */
final class FakeNmea {
    private FakeNmea() {}

    /** Simulated replacement for one real sentence, or null to drop it (proprietary / unknown). */
    static String replace(JSONObject config, String real) throws Exception {
        if (real == null) return null;
        String line = real.trim();
        if (line.length() < 7 || line.charAt(0) != '$' || line.charAt(1) == 'P') return null;
        String talker = line.substring(1, 3), type = line.substring(3, 6);
        Location l = FakeLocation.build(config, "gps");
        List<FakeEnvironment.Sat> sky = FakeEnvironment.sky(config);
        String body;
        switch (type) {
            case "GGA": body = gga(talker, l, sky); break;
            case "RMC": body = rmc(talker, l); break;
            case "VTG": body = vtg(talker, l); break;
            case "GLL": body = gll(talker, l); break;
            case "GNS": body = gns(talker, l, sky); break;
            case "ZDA": body = zda(talker, l); break;
            case "GSA": body = gsa(talker, l, sky, constellationOf(talker)); break;
            case "GSV": return gsv(talker, sky, line);
            case "TXT": return real; // Chip status text (antenna, firmware); carries no position.
            default: return null;
        }
        return withChecksum(body);
    }

    static String withChecksum(String body) {
        int sum = 0;
        for (int i = 1; i < body.length(); i++) sum ^= body.charAt(i);
        return body + String.format(Locale.US, "*%02X", sum) + "\r\n";
    }

    /** Constellation of a talker id, or 0 for combined (GN) / unknown. */
    static int constellationOf(String talker) {
        switch (talker) {
            case "GP": return GnssStatus.CONSTELLATION_GPS;
            case "GL": return GnssStatus.CONSTELLATION_GLONASS;
            case "GA": return GnssStatus.CONSTELLATION_GALILEO;
            case "BD": case "GB": return GnssStatus.CONSTELLATION_BEIDOU;
            default: return 0;
        }
    }

    /** Satellite number as NMEA writes it (GLONASS slots are 65-96). */
    private static int nmeaId(FakeEnvironment.Sat s) {
        return s.constellation == GnssStatus.CONSTELLATION_GLONASS ? 64 + s.svid : s.svid;
    }

    private static Calendar utc(Location l) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.setTimeInMillis(l.getTime());
        return c;
    }

    private static String time(Location l) {
        Calendar c = utc(l);
        return String.format(Locale.US, "%02d%02d%02d.%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND) / 10);
    }

    private static String date(Location l) {
        Calendar c = utc(l);
        return String.format(Locale.US, "%02d%02d%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100);
    }

    private static String lat(double v) {
        double a = Math.abs(v);
        int d = (int) a;
        return String.format(Locale.US, "%02d%08.5f,%s", d, (a - d) * 60, v >= 0 ? "N" : "S");
    }

    private static String lon(double v) {
        double a = Math.abs(v);
        int d = (int) a;
        return String.format(Locale.US, "%03d%08.5f,%s", d, (a - d) * 60, v >= 0 ? "E" : "W");
    }

    private static double hdop(Location l) { return Math.max(0.6, Math.min(5, l.getAccuracy() / 5.0)); }

    private static int used(List<FakeEnvironment.Sat> sky) {
        int n = 0;
        for (FakeEnvironment.Sat s : sky) if (s.used) n++;
        return n;
    }

    private static double knots(Location l) { return (l.hasSpeed() ? l.getSpeed() : 0) * 1.943844; }

    private static String course(Location l) {
        return l.hasBearing() ? String.format(Locale.US, "%.1f", l.getBearing()) : "";
    }

    private static String gga(String t, Location l, List<FakeEnvironment.Sat> sky) {
        return String.format(Locale.US, "$%sGGA,%s,%s,%s,1,%02d,%.1f,%.1f,M,0.0,M,,", t, time(l), lat(l.getLatitude()),
                lon(l.getLongitude()), used(sky), hdop(l), l.getAltitude());
    }

    private static String rmc(String t, Location l) {
        return String.format(Locale.US, "$%sRMC,%s,A,%s,%s,%.3f,%s,%s,,,A", t, time(l), lat(l.getLatitude()),
                lon(l.getLongitude()), knots(l), course(l), date(l));
    }

    private static String vtg(String t, Location l) {
        return String.format(Locale.US, "$%sVTG,%s,T,,M,%.3f,N,%.3f,K,A", t, course(l), knots(l), knots(l) * 1.852);
    }

    private static String gll(String t, Location l) {
        return String.format(Locale.US, "$%sGLL,%s,%s,%s,A,A", t, lat(l.getLatitude()), lon(l.getLongitude()), time(l));
    }

    private static String gns(String t, Location l, List<FakeEnvironment.Sat> sky) {
        return String.format(Locale.US, "$%sGNS,%s,%s,%s,AAAA,%02d,%.1f,%.1f,0.0,,,V", t, time(l), lat(l.getLatitude()),
                lon(l.getLongitude()), used(sky), hdop(l), l.getAltitude());
    }

    private static String zda(String t, Location l) {
        Calendar c = utc(l);
        return String.format(Locale.US, "$%sZDA,%s,%02d,%02d,%04d,00,00", t, time(l), c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
    }

    private static String gsa(String t, Location l, List<FakeEnvironment.Sat> sky, int constellation) {
        StringBuilder b = new StringBuilder("$").append(t).append("GSA,A,3");
        int n = 0;
        for (FakeEnvironment.Sat s : sky) {
            if (!s.used || (constellation != 0 && s.constellation != constellation) || n == 12) continue;
            b.append(',').append(String.format(Locale.US, "%02d", nmeaId(s)));
            n++;
        }
        for (; n < 12; n++) b.append(',');
        double h = hdop(l);
        return b.append(String.format(Locale.US, ",%.1f,%.1f,%.1f", h * 1.4, h, h * 1.1)).toString();
    }

    /** The simulated GSV part with the same number as the real one, or null when there is none. */
    private static String gsv(String t, List<FakeEnvironment.Sat> sky, String real) {
        int constellation = constellationOf(t);
        List<FakeEnvironment.Sat> view = new ArrayList<>();
        for (FakeEnvironment.Sat s : sky) if (constellation == 0 || s.constellation == constellation) view.add(s);
        String[] f = real.split("[,*]");
        int part;
        try { part = Integer.parseInt(f[2]); } catch (Exception e) { return null; }
        int parts = Math.max(1, (view.size() + 3) / 4);
        if (part < 1 || part > parts) return null;
        StringBuilder b = new StringBuilder(String.format(Locale.US, "$%sGSV,%d,%d,%02d", t, parts, part, view.size()));
        for (int i = (part - 1) * 4; i < Math.min(view.size(), part * 4); i++) {
            FakeEnvironment.Sat s = view.get(i);
            b.append(String.format(Locale.US, ",%02d,%02d,%03d,%02d", nmeaId(s), Math.round(s.elevation),
                    Math.round(s.azimuth) % 360, Math.round(s.cn0)));
        }
        return withChecksum(b.toString());
    }
}

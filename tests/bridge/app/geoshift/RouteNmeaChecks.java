package app.geoshift;
import android.location.Location;
import org.json.JSONObject;

/** RoutePlan (route playback in the system hook) and FakeNmea (simulated NMEA) checks. */
public final class RouteNmeaChecks {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        // Straight route north, about 1112 m long, three points.
        double[] lat = {30.0, 30.005, 30.01}, lon = {120.0, 120.0, 120.0};
        RoutePlan plan = new RoutePlan(RoutePlan.idOf(lat, lon), lat, lon);
        check(Math.abs(plan.total - 1112) < 5, "route length " + plan.total);
        check(!RoutePlan.idOf(lat, lon).equals(RoutePlan.idOf(new double[]{30.0, 30.005, 30.011}, lon)), "id changes with points");
        RoutePlan.Position mid = plan.at(plan.total / 2);
        check(Math.abs(mid.lat - 30.005) < 1e-4 && !mid.finished, "half way is the middle point");
        check(Math.abs(mid.bearing) < 1 || Math.abs(mid.bearing - 360) < 1, "bearing north " + mid.bearing);
        check(plan.at(plan.total + 50).finished && plan.at(-5).lat == 30.0, "clamped at both ends");

        // Travelled distance: about speed * time, never below 80 % or above 120 % of it.
        for (long span : new long[]{1000, 7000, 60000, 600000}) {
            double d = RoutePlan.travelled(2.0, 5_000_000, 5_000_000 + span);
            check(d >= 2.0 * span / 1000 * 0.8 - 1e-6 && d <= 2.0 * span / 1000 * 1.2 + 1e-6, "travelled " + span + " -> " + d);
        }
        // Additive: two halves equal the whole (app and system agree however often they compute).
        double whole = RoutePlan.travelled(1.4, 1000, 91000);
        double parts = RoutePlan.travelled(1.4, 1000, 37000) + RoutePlan.travelled(1.4, 37000, 91000);
        check(Math.abs(whole - parts) < 1e-6, "travelled is additive");
        // Speed actually varies.
        double lo = 9, hi = 0;
        for (int s = 0; s < 600; s += 3) { double f = RoutePlan.speedFactor(s); lo = Math.min(lo, f); hi = Math.max(hi, f); }
        check(lo < 0.9 && hi > 1.1 && lo >= 0.8 - 1e-9 && hi <= 1.2 + 1e-9, "speed swing " + lo + ".." + hi);

        // Anchors: paused keeps the distance; running moves from the anchor.
        JSONObject r = new JSONObject().put("routeAnchorDist", 100.0).put("routeAnchorAt", 10_000L).put("routeSpeed", 2.0);
        check(RoutePlan.distanceAt(r, 20_000) > 100 + 10, "running moves");
        r.put("paused", true);
        check(RoutePlan.distanceAt(r, 20_000) == 100, "paused stays");

        // Live position only for the installed plan.
        JSONObject config = new JSONObject()
                .put("settings", new JSONObject().put("accuracy", 5).put("satelliteCount", 12))
                .put("runtime", new JSONObject().put("enabled", true).put("lat", 1.0).put("lon", 2.0).put("mode", "route")
                        .put("planId", plan.id).put("routeAnchorDist", 0.0).put("routeAnchorAt", 123456L).put("routeSpeed", 1.4));
        RoutePlan.install(null);
        check(RoutePlan.live(config) == null, "no plan installed");
        RoutePlan.install(plan);
        RoutePlan.Position live = RoutePlan.live(config);
        check(live != null && Math.abs(live.lat - 30.0) < 1e-6, "live at anchor (stub clock = anchor)");
        Location fake = FakeLocation.build(config, "gps");
        check(Math.abs(fake.getLatitude() - 30.0) < 1e-3 && Math.abs(fake.getLongitude() - 120.0) < 1e-3, "FakeLocation follows the route");
        check(fake.getSpeed() > 1.0 && fake.getSpeed() < 1.8, "route speed with swing " + fake.getSpeed());
        config.getJSONObject("runtime").put("planId", "other");
        check(Math.abs(FakeLocation.build(config, "gps").getLatitude() - 1.0) < 1e-3, "other plan -> runtime position");
        config.getJSONObject("runtime").put("planId", plan.id);

        // NMEA: same talker and type, valid checksum, simulated position.
        String gga = FakeNmea.replace(config, "$GPGGA,120000.00,3954.000,N,11624.000,E,1,08,0.9,50.0,M,0.0,M,,*00");
        check(gga != null && gga.startsWith("$GPGGA,"), "GGA talker " + gga);
        String[] g = gga.split(",");
        double nLat = Integer.parseInt(g[2].substring(0, 2)) + Double.parseDouble(g[2].substring(2)) / 60;
        double nLon = Integer.parseInt(g[4].substring(0, 3)) + Double.parseDouble(g[4].substring(3)) / 60;
        check(Math.abs(nLat - 30.0) < 1e-4 && Math.abs(nLon - 120.0) < 1e-4 && g[3].equals("N") && g[5].equals("E"), "GGA position " + gga);
        check(validChecksum(gga), "GGA checksum " + gga);
        String rmc = FakeNmea.replace(config, "$GNRMC,120000.00,A,3954.0,N,11624.0,E,0.0,,010125,,,A*00");
        check(rmc != null && rmc.startsWith("$GNRMC,") && validChecksum(rmc), "RMC " + rmc);
        String gsv1 = FakeNmea.replace(config, "$GPGSV,3,1,10,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45*75");
        check(gsv1 != null && gsv1.startsWith("$GPGSV,1,1,03,") && validChecksum(gsv1), "GPS GSV " + gsv1);
        check(FakeNmea.replace(config, "$GPGSV,3,2,10,01,40,083,46*75") == null, "extra GSV part dropped");
        String bd = FakeNmea.replace(config, "$GBGSV,1,1,02,07,40,083,46*75");
        check(bd != null && bd.startsWith("$GBGSV,"), "BeiDou GSV keeps talker");
        String gl = FakeNmea.replace(config, "$GLGSV,1,1,01,70,40,083,46*75");
        check(gl != null && gl.matches("\\$GLGSV,1,1,03,(6[5-9]|[7-8][0-9]|9[0-6]),.*\\r\\n"), "GLONASS ids 65-96 " + gl);
        check(FakeNmea.replace(config, "$PQGSV,whatever*00") == null, "proprietary dropped");
        String gsa = FakeNmea.replace(config, "$GNGSA,A,3,01,02,,,,,,,,,,,1.2,0.9,0.8,1*00");
        check(gsa != null && gsa.split(",").length >= 18 && validChecksum(gsa), "GSA " + gsa);
        System.out.println("PASS: " + checks + " route playback / NMEA checks.");
    }

    private static boolean validChecksum(String s) {
        String t = s.trim();
        int star = t.indexOf('*');
        int sum = 0;
        for (int i = 1; i < star; i++) sum ^= t.charAt(i);
        return Integer.parseInt(t.substring(star + 1), 16) == sum;
    }
}

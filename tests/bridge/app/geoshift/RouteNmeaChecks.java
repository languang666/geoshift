package app.geoshift;
import android.location.Location;
import org.json.JSONObject;

/** RoutePlan (route playback in the system hook) and FakeNmea (simulated NMEA) checks. */
public final class RouteNmeaChecks {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static double gap(RoutePlan.Position a, RoutePlan.Position b) { return Geo.distance(a.lat, a.lon, b.lat, b.lon); }

    /** Corners, start/end easing, repeat modes and anchor consistency. */
    private static void routeProfileChecks() throws Exception {
        // L-shaped: ~556 m north, then ~558 m east (a 90° turn), plus a duplicated point.
        double[] lat = {30.0, 30.005, 30.005, 30.005}, lon = {120.0, 120.0, 120.0, 120.0058};
        RoutePlan plan = new RoutePlan(RoutePlan.idOf(lat, lon), lat, lon);
        double corner = plan.open.cumulative[1], L = plan.total;
        check(plan.open.lat.length == 3, "duplicate point dropped");
        double drive = 13.9, walk = 1.4;
        RoutePlan.Track.Pace p = plan.open.pace(drive);
        check(p.at(corner) < 0.8 && p.at(corner) > 0.3, "driving slows for a 90° corner " + p.at(corner));
        check(p.at(corner / 2) == 1 && p.at(0) < 0.1 && p.at(L) < 0.1, "full speed between, slow at both ends");
        RoutePlan.Track.Pace w = plan.open.pace(walk);
        check(w.at(corner) == 1 && w.at(L / 2) == 1, "walking keeps its pace round a corner");
        // Heading turns gradually through the corner rather than snapping.
        double turning = plan.at(corner).bearing;
        check(turning > 30 && turning < 60, "bearing blends at the corner " + turning);
        check(Math.abs(plan.at(corner - 20).bearing) < 1 || Math.abs(plan.at(corner - 20).bearing - 360) < 1, "straight before the blend");
        // Effort <-> distance round trip, all modes.
        for (String mode : new String[]{RoutePlan.ONCE, RoutePlan.LOOP, RoutePlan.BOUNCE})
            for (double d : new double[]{0.3, 10, corner, L - 1, L + 37, 2 * L + 400, 5 * L + 3}) {
                if (RoutePlan.ONCE.equals(mode) && d > L) continue;
                double back = plan.distanceOf(mode, drive, plan.effortOf(mode, drive, d));
                check(Math.abs(back - d) < 1e-6, mode + " effort round trip " + d + " -> " + back);
            }
        // Re-anchoring part way gives the same position as one long run (app and system agree).
        JSONObject a = new JSONObject().put("routeAnchorDist", 0.0).put("routeAnchorAt", 1_000L).put("routeSpeed", drive);
        for (String mode : new String[]{RoutePlan.ONCE, RoutePlan.LOOP, RoutePlan.BOUNCE}) {
            a.put("routeRepeat", mode).put("routeAnchorDist", 0.0).put("routeAnchorAt", 1_000L);
            double direct = plan.distanceAt(a, 151_000);
            double middle = plan.distanceAt(a, 63_000);
            JSONObject b = new JSONObject().put("routeSpeed", drive).put("routeRepeat", mode).put("routeAnchorDist", middle).put("routeAnchorAt", 63_000L);
            check(Math.abs(plan.distanceAt(b, 151_000) - direct) < 1e-6, mode + " re-anchor is seamless");
        }
        // Reported speed matches how fast the position actually moves, corners and ends included.
        a.put("routeRepeat", RoutePlan.BOUNCE).put("routeAnchorDist", 0.0).put("routeAnchorAt", 0L);
        for (long t = 500; t < 400_000; t += 1_700) {
            double d0 = plan.distanceAt(a, t), d1 = plan.distanceAt(a, t + 50);
            RoutePlan.Position pos = plan.at(RoutePlan.BOUNCE, drive, d0);
            double reported = drive * RoutePlan.speedFactor(t / 1000.0) * pos.pace, actual = (d1 - d0) / 0.05;
            check(Math.abs(reported - actual) < 0.03 * drive, "speed matches movement at " + t + ": " + reported + " vs " + actual);
        }
        // Played once, the whole route takes longer than at constant speed (easing) but not much.
        a.put("routeRepeat", RoutePlan.ONCE);
        long done = 0;
        for (long t = 0; t < 1_000_000 && done == 0; t += 500) if (plan.at(RoutePlan.ONCE, drive, plan.distanceAt(a, t)).finished) done = t;
        check(done > L / drive * 1000 && done < (L / drive + 40) * 1000, "once finishes in " + done + " ms");
        // Loop: closes back to the start, wraps without a jump.
        RoutePlan.Track loop = plan.track(RoutePlan.LOOP);
        check(loop.length > L + 700 && loop.length < L + 850, "loop adds the closing section " + loop.length);
        double lap = loop.length;
        check(gap(plan.at(RoutePlan.LOOP, drive, lap - 0.05), plan.at(RoutePlan.LOOP, drive, lap + 0.05)) < 0.2, "loop seam is continuous");
        check(Math.abs(plan.at(RoutePlan.LOOP, drive, lap * 3 + 5).along - 5) < 1e-6, "loop wraps by laps");
        // Bounce: comes back reversed, facing the other way, and returns to the start.
        RoutePlan.Position back = plan.at(RoutePlan.BOUNCE, drive, L + 100);
        check(back.reversed && Math.abs(back.along - (L - 100)) < 1e-6, "bounce returns along the route");
        double ahead = plan.at(RoutePlan.ONCE, drive, L - 100).bearing;
        check(Math.abs(((back.bearing - ahead) % 360 + 540) % 360 - 180) > 170, "bounce faces back " + back.bearing + " vs " + ahead);
        check(gap(plan.at(RoutePlan.BOUNCE, drive, 2 * L), plan.at(RoutePlan.ONCE, drive, 0)) < 0.01, "bounce reaches the start");
        check(gap(plan.at(RoutePlan.BOUNCE, drive, L - 0.05), plan.at(RoutePlan.BOUNCE, drive, L + 0.05)) < 0.2, "bounce turnaround is continuous");
        // Switching mode keeps the current point.
        JSONObject run = new JSONObject().put("routeAnchorDist", L + 100).put("routeAnchorAt", 1_000L).put("routeSpeed", drive)
                .put("paused", true).put("routeRepeat", RoutePlan.BOUNCE);
        double toOnce = plan.reanchor(run, 1_000, RoutePlan.ONCE);
        check(Math.abs(toOnce - (L - 100)) < 1e-6, "bounce -> once keeps the point");
        run.put("routeRepeat", RoutePlan.ONCE).put("routeAnchorDist", L - 100);
        check(Math.abs(plan.reanchor(run, 1_000, RoutePlan.BOUNCE) - (L - 100)) < 1e-6, "once -> bounce keeps the point");
        run.put("routeRepeat", RoutePlan.LOOP).put("routeAnchorDist", L + 50);
        check(Math.abs(plan.reanchor(run, 1_000, RoutePlan.ONCE) - L) < 1e-6, "loop closing section -> once ends at the finish");
        check(RoutePlan.repeat(new JSONObject().put("routeRepeat", "bogus")).equals(RoutePlan.ONCE), "unknown repeat mode plays once");
    }

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
        check(plan.distanceAt(r, 20_000) > 100 + 10, "running moves");
        r.put("paused", true);
        check(plan.distanceAt(r, 20_000) == 100, "paused stays");

        routeProfileChecks();

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
        // At the start the route eases in from a slow walk.
        check(fake.getSpeed() > 0.3 && fake.getSpeed() < 0.7, "route starts slowly " + fake.getSpeed());
        config.getJSONObject("runtime").put("routeAnchorDist", plan.total / 2);
        fake = FakeLocation.build(config, "gps");
        check(fake.getSpeed() > 1.0 && fake.getSpeed() < 1.8, "route speed with swing " + fake.getSpeed());
        config.getJSONObject("runtime").put("routeAnchorDist", 0.0);
        config.getJSONObject("runtime").put("planId", "other");
        check(Math.abs(FakeLocation.build(config, "gps").getLatitude() - 1.0) < 1e-3, "other plan -> runtime position");
        config.getJSONObject("runtime").put("planId", plan.id);

        // Long uptime must not produce negative azimuths (Java remainder keeps the sign).
        for (long at : new long[]{0, 24*60*60_000L, 7*24*60*60_000L, 365*24*60*60_000L}) {
            boolean valid = true;
            for (FakeEnvironment.Sat sat : FakeEnvironment.sky(config, at))
                valid &= sat.azimuth >= 0 && sat.azimuth < 360 && sat.elevation >= 0 && sat.elevation <= 90;
            check(valid, "satellite angles valid at uptime " + at);
        }

        java.util.List<FakeEnvironment.Sat> observations = FakeEnvironment.sky(config, 123456L);
        int usedCount = 0; double sumSignal = 0, maxSignal = 0;
        for (FakeEnvironment.Sat sat : observations) if (sat.used) {
            usedCount++; sumSignal += sat.cn0; maxSignal = Math.max(maxSignal, sat.cn0);
        }
        android.os.Bundle summary = FakeEnvironment.gpsExtras(config, 123456L);
        check(summary.getInt("satellites") == usedCount, "GNSS used flags and fix satellite count agree");
        check(summary.getInt("meanCn0") == (int)Math.round(sumSignal / usedCount)
                && summary.getInt("maxCn0") == (int)Math.round(maxSignal), "fix signal summary matches the same satellite observations");
        config.getJSONObject("runtime").put("noiseSeed", 876543L);
        check(FakeEnvironment.sky(config, 123456L).get(0).cn0 != observations.get(0).cn0, "satellite signal varies between sessions");
        config.getJSONObject("runtime").put("noiseSeed", 0L);

        // NMEA: same talker and type, valid checksum, simulated position.
        check(FakeNmea.replace(config, "$GPTXT,01,01,02,unvalidated receiver payload*00") == null, "free-form receiver text is withheld");
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
        // Real chip sends one GPS GSV part, simulated sky needs more: all parts follow it.
        JSONObject many = new JSONObject()
                .put("settings", new JSONObject().put("accuracy", 5).put("satelliteCount", 24))
                .put("runtime", new JSONObject().put("enabled", true).put("lat", 1.0).put("lon", 2.0));
        java.util.List<String> gsvParts = FakeNmea.replaceAll(many, "$GPGSV,1,1,03,01,40,083,46,02,17,308,41,12,07,344,39*75");
        check(gsvParts.size() >= 2, "missing GSV parts appended " + gsvParts.size());
        for (int i = 0; i < gsvParts.size(); i++)
            check(gsvParts.get(i).startsWith("$GPGSV," + gsvParts.size() + "," + (i + 1) + ",") && validChecksum(gsvParts.get(i)), "GSV part " + gsvParts.get(i));
        check(FakeNmea.replaceAll(config, "$GQGSV,1,1,01,193,40,083,46*75").isEmpty(), "QZSS GSV dropped");
        check(FakeNmea.replaceAll(config, "$GQGSA,A,3,193,,,,,,,,,,,,1.2,0.9,0.8*00").isEmpty(), "QZSS GSA dropped");
        // One burst, one fix: GGA and RMC carry the same time.
        String t1 = FakeNmea.replace(config, "$GPGGA,1*00").split(",")[1], t2 = FakeNmea.replace(config, "$GPRMC,1*00").split(",")[1];
        check(t1.equals(t2), "burst shares time " + t1 + " " + t2);
        // Jitter is the same for every caller within a second.
        config.getJSONObject("settings").put("jitter", true);
        Location j1 = FakeLocation.build(config, "gps"), j2 = FakeLocation.build(config, "fused");
        check(j1.getLatitude() == j2.getLatitude() && j1.getLongitude() == j2.getLongitude(), "jitter shared across providers");
        // A past moment (batched delivery): earlier route position and timestamps, same drift rules.
        config.getJSONObject("settings").put("jitter", false);
        config.getJSONObject("runtime").put("routeAnchorAt", 100000L).put("routeSpeed", 1.4);
        Location nowFix = FakeLocation.build(config, "gps", 123456L), earlier = FakeLocation.build(config, "gps", 113456L);
        check(Math.abs(nowFix.getTime() - earlier.getTime() - 10000) <= 50, "past fix time " + (nowFix.getTime() - earlier.getTime()));
        check(nowFix.getElapsedRealtimeNanos() - earlier.getElapsedRealtimeNanos() == 10_000_000_000L, "past fix clock");
        double moved = app.geoshift.Geo.distance(earlier.getLatitude(), earlier.getLongitude(), nowFix.getLatitude(), nowFix.getLongitude());
        check(moved > 8 && moved < 25, "route moved between the two moments " + moved);
        check(FakeLocation.build(config, "gps", 999999999L).getElapsedRealtimeNanos() == nowFix.getElapsedRealtimeNanos(), "future moment clamps to now");
        // Session-specific noise remains bounded, continuous and identical across consumers.
        config.getJSONObject("runtime").put("mode","point").put("noiseSeed",12345L);
        config.getJSONObject("settings").put("jitter",true);
        Location beforeBoundary=FakeLocation.build(config,"gps",119999L),afterBoundary=FakeLocation.build(config,"gps",120000L);
        check(Geo.distance(beforeBoundary.getLatitude(),beforeBoundary.getLongitude(),afterBoundary.getLatitude(),afterBoundary.getLongitude())<0.01,"jitter has no one-second boundary jump");
        Location seededGps=FakeLocation.build(config,"gps",120000L),seededFused=FakeLocation.build(config,"fused",120000L);
        check(seededGps.getLatitude()==seededFused.getLatitude()&&seededGps.getLongitude()==seededFused.getLongitude(),"seeded providers share one position");
        Location repeated=FakeLocation.build(config,"gps",120000L);
        check(repeated.getLatitude()==seededGps.getLatitude()&&repeated.getAltitude()==seededGps.getAltitude(),"historical seeded fix replays exactly");
        config.getJSONObject("runtime").put("noiseSeed",67890L);
        Location otherSession=FakeLocation.build(config,"gps",120000L);
        check(otherSession.getLatitude()!=seededGps.getLatitude()||otherSession.getLongitude()!=seededGps.getLongitude(),"new session produces a different drift pattern");
        boolean bounded=true,changed=false;
        double firstNoise=FakeLocation.noise(0,3.7,12345,71);
        for(int i=0;i<1000;i++){
            double n=FakeLocation.noise(i*0.23,3.7,12345,71);
            bounded&=n>=-1&&n<=1;
            changed|=Math.abs(n-firstNoise)>0.1;
        }
        check(bounded&&changed,"smooth noise varies within its bounds");
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

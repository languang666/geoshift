package app.geoshift;

import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;

/**
 * Builds the simulated Location from a pushed config. Used in system_server and target apps.
 * Real fixes are never perfectly still: position drifts by a few metres and accuracy, altitude and
 * speed wobble. The wobble here follows the clock, so every app sees the same fix at the same time.
 */
final class FakeLocation {
    private FakeLocation() {}

    /** Smooth value in [-1, 1] that changes over tens of seconds, deterministic for a given time. */
    static double wave(double seconds, double seed) {
        return 0.6 * Math.sin(seconds / 23.0 + seed) + 0.4 * Math.sin(seconds / 61.0 + seed * 2.7);
    }

    /** Pseudo-random value in [0, 1) fixed for a (value, salt) pair. */
    private static double unit(long value, long salt) {
        long z = value * salt + 0x632BE59BD9B4E019L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    /** Smooth, bounded noise with no repeating sine period; replayable for historical fixes. */
    static double noise(double seconds, double period, long seed, long channel) {
        double position = seconds / period;
        long bucket = (long) Math.floor(position);
        double f = position - bucket;
        double blend = f * f * f * (f * (f * 6 - 15) + 10);
        double a = unit(bucket ^ seed, channel) * 2 - 1;
        double b = unit((bucket + 1) ^ seed, channel) * 2 - 1;
        return a + (b - a) * blend;
    }

    private static double drift(double seconds, long seed, long channel) {
        return 0.7 * noise(seconds, 17.3, seed, channel)
                + 0.3 * noise(seconds, 53.1, seed, channel ^ 0xD6E8FEB86659FD93L);
    }

    /** Reuse a fix's monotonic timestamp so every delivery layer preserves a batch's history. */
    static Location replace(JSONObject config, Object original) throws Exception {
        String provider = original instanceof Location ? ((Location) original).getProvider() : null;
        if (provider == null || provider.isEmpty() || "passive".equals(provider)) provider = "fused";
        long at = SystemClock.elapsedRealtime();
        if (original instanceof Location) {
            long nanos = ((Location) original).getElapsedRealtimeNanos();
            // A long-stale cached fix would make the fake one equally stale, and SDKs drop stale fixes.
            if (nanos > 0 && at - nanos / 1_000_000L < 10 * 60_000L) at = Math.min(at, nanos / 1_000_000L);
        }
        return build(config, provider, at);
    }

    static Location build(JSONObject config, String provider) throws Exception {
        return build(config, provider, SystemClock.elapsedRealtime());
    }

    /**
     * The simulated fix as it was at clock reading [atMs] (SystemClock.elapsedRealtime). Batched
     * deliveries carry several past fixes; each gets the position and timestamps of its own moment.
     */
    static Location build(JSONObject config, String provider, long atMs) throws Exception {
        JSONObject r = config.getJSONObject("runtime"), s = config.getJSONObject("settings");
        double lat = r.getDouble("lat"), lon = r.getDouble("lon");
        double routeSpeed = -1, routeBearing = 0;
        long clock = SystemClock.elapsedRealtime();
        long now = atMs > clock || atMs <= 0 ? clock : atMs;
        long age = clock - now;
        // A route keeps moving here even while the app sleeps (see RoutePlan).
        RoutePlan.Position route = RoutePlan.live(config, now);
        if (route != null) {
            lat = route.lat;
            lon = route.lon;
            routeBearing = route.bearing;
            boolean moving = !r.optBoolean("paused") && !route.finished;
            routeSpeed = moving ? r.optDouble("routeSpeed", 0) * RoutePlan.speedFactor(now / 1000.0) * route.pace : 0;
        }
        boolean network = "network".equals(provider);
        double t = now / 1000.0;
        long seed = r.optLong("noiseSeed", 0);
        double base = Math.max(1, s.optDouble("accuracy", 5));
        // Network fixes come from cell / Wi-Fi databases and are much coarser than satellite fixes.
        double accuracy = network ? Math.max(base * 4, 18) * (1 + 0.15 * drift(t, seed, 41))
                : base * (1 + 0.2 * drift(t, seed, 13));
        // Same drift for every provider: apps that mix gps / network / fused fixes must see one point,
        // otherwise their dot hops between two positions.
        double drift = Math.min(3, base * 0.35);
        double east = drift * drift(t, seed, 7), north = drift * drift(t, seed, 29);
        if (s.optBoolean("jitter")) {
            // Correlated extra motion, at most 1 m radially, without one-second step changes.
            east += noise(t, 3.7, seed, 71) / Math.sqrt(2);
            north += noise(t, 4.9, seed, 97) / Math.sqrt(2);
        }
        double distance = Math.hypot(east, north);
        if (distance > 0) {
            double[] p = Geo.move(lat, lon, distance, Math.toDegrees(Math.atan2(east, north)));
            lat = p[0];
            lon = p[1];
        }
        Location l = new Location(provider);
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setAccuracy((float) accuracy);
        l.setTime(System.currentTimeMillis() - age);
        // "Now" for live fixes: the framework drops fixes older than the last delivered one.
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - age * 1_000_000L);
        if (!network) {
            // Satellite-based fixes carry altitude and motion; network fixes do not.
            l.setAltitude(s.optDouble("altitude", 0) + 1.5 * drift(t, seed, 33));
            double speed = routeSpeed >= 0 ? routeSpeed : r.optBoolean("paused") ? 0 : r.optDouble("speed", 0);
            boolean moving = speed > 0;
            if (!moving) speed = 0.12 * (1 + drift(t, seed, 52)); // A still receiver reports a few cm/s.
            l.setSpeed((float) speed);
            if ("gps".equals(provider)) l.setExtras(FakeEnvironment.gpsExtras(config, now));
            if (moving) l.setBearing((float) (route != null ? routeBearing : r.optDouble("bearing", 0)));
            if (Build.VERSION.SDK_INT >= 26) {
                l.setVerticalAccuracyMeters((float) (accuracy * 1.6));
                l.setSpeedAccuracyMetersPerSecond((float) (0.4 + 0.2 * (1 + drift(t, seed, 61))));
                if (moving) l.setBearingAccuracyDegrees(10f);
            }
        }
        // Framework/app hooks deliver this fix directly, without a test provider.
        // A newly constructed Location has no mock flag on older Android versions.
        if (Build.VERSION.SDK_INT >= 31) {
            l.setMock(false);
        }
        return l;
    }
}

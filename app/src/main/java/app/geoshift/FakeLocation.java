package app.geoshift;

import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.concurrent.ThreadLocalRandom;

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

    static Location build(JSONObject config, String provider) throws Exception {
        JSONObject r = config.getJSONObject("runtime"), s = config.getJSONObject("settings");
        double lat = r.getDouble("lat"), lon = r.getDouble("lon");
        boolean network = "network".equals(provider);
        double t = SystemClock.elapsedRealtime() / 1000.0;
        double base = Math.max(1, s.optDouble("accuracy", 5));
        // Network fixes come from cell / Wi-Fi databases and are much coarser than satellite fixes.
        double accuracy = network ? Math.max(base * 4, 18) * (1 + 0.15 * wave(t, 4.1))
                : base * (1 + 0.2 * wave(t, 1.3));
        double drift = Math.min(network ? 8 : 3, accuracy * 0.35);
        double east = drift * wave(t, 0.7), north = drift * wave(t, 2.9);
        if (s.optBoolean("jitter")) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double angle = random.nextDouble() * 2 * Math.PI, d = random.nextDouble();
            east += d * Math.sin(angle);
            north += d * Math.cos(angle);
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
        l.setTime(System.currentTimeMillis());
        // Must be "now": the framework drops fixes older than the last delivered one.
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (!network) {
            // Satellite-based fixes carry altitude and motion; network fixes do not.
            l.setAltitude(s.optDouble("altitude", 0) + 1.5 * wave(t, 3.3));
            double speed = r.optBoolean("paused") ? 0 : r.optDouble("speed", 0);
            boolean moving = speed > 0;
            if (!moving) speed = 0.12 * (1 + wave(t, 5.2)); // A still receiver reports a few cm/s.
            l.setSpeed((float) speed);
            if ("gps".equals(provider)) l.setExtras(FakeEnvironment.gpsExtras(config));
            if (moving) l.setBearing((float) r.optDouble("bearing", 0));
            if (Build.VERSION.SDK_INT >= 26) {
                l.setVerticalAccuracyMeters((float) (accuracy * 1.6));
                l.setSpeedAccuracyMetersPerSecond((float) (0.4 + 0.2 * (1 + wave(t, 6.1))));
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

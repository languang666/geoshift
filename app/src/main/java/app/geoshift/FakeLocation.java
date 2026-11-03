package app.geoshift;

import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.concurrent.ThreadLocalRandom;

/** Builds the simulated Location from a pushed config. Used in system_server and target apps. */
final class FakeLocation {
    private FakeLocation() {}

    static Location build(JSONObject config, String provider) throws Exception {
        JSONObject r = config.getJSONObject("runtime"), s = config.getJSONObject("settings");
        double lat = r.getDouble("lat"), lon = r.getDouble("lon");
        if (s.optBoolean("jitter")) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double[] p = Geo.move(lat, lon, random.nextDouble(), random.nextDouble() * 360);
            lat = p[0];
            lon = p[1];
        }
        Location l = new Location(provider);
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setAltitude(s.optDouble("altitude", 0));
        l.setAccuracy((float) s.optDouble("accuracy", 5));
        l.setSpeed((float) (r.optBoolean("paused") ? 0 : r.optDouble("speed", 0)));
        l.setBearing((float) r.optDouble("bearing", 0));
        l.setTime(System.currentTimeMillis());
        // Must be "now": the framework drops fixes older than the last delivered one.
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (Build.VERSION.SDK_INT >= 26) {
            l.setVerticalAccuracyMeters(3f);
            l.setSpeedAccuracyMetersPerSecond(0.5f);
            l.setBearingAccuracyDegrees(10f);
        }
        // Framework/app hooks deliver this fix directly, without a test provider.
        // A newly constructed Location has no mock flag on older Android versions.
        if (Build.VERSION.SDK_INT >= 31) {
            l.setMock(false);
        }
        return l;
    }
}

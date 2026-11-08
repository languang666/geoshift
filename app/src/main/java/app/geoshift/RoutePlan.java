package app.geoshift;

import android.os.SystemClock;
import org.json.JSONObject;

/**
 * Route playback as a pure function of time, so the app and the system hook compute the same
 * position without talking to each other. The app sends the points once (ROUTE command) and an
 * anchor with every config: distance along the route at a moment of SystemClock.elapsedRealtime(),
 * the base speed and whether playback is paused. The system hook then keeps moving along the route
 * while the app sleeps or is frozen.
 *
 * Speed is not constant: it follows a smooth wave between 80 % and 120 % of the base speed, like a
 * person or car never holding a perfectly steady pace. The wave has a closed-form integral, so the
 * distance travelled stays exact for any time span.
 */
final class RoutePlan {
    /** Speed varies by up to this fraction of the base speed. */
    static final double SPEED_SWING = 0.2;

    final String id;
    final double[] lat, lon;
    /** Distance from the start to each point, in metres. */
    final double[] cumulative;
    final double total;

    private static volatile RoutePlan installed;

    RoutePlan(String id, double[] lat, double[] lon) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
        cumulative = new double[lat.length];
        for (int i = 1; i < lat.length; i++)
            cumulative[i] = cumulative[i - 1] + Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        total = lat.length == 0 ? 0 : cumulative[lat.length - 1];
    }

    /** Stable id for a list of points; changes whenever any point changes. */
    static String idOf(double[] lat, double[] lon) {
        long h = 1125899906842597L;
        for (int i = 0; i < lat.length; i++) {
            h = 31 * h + Double.doubleToLongBits(lat[i]);
            h = 31 * h + Double.doubleToLongBits(lon[i]);
        }
        return lat.length + "-" + Long.toHexString(h);
    }

    static void install(RoutePlan plan) { installed = plan; }

    static RoutePlan installed() { return installed; }

    /** Speed factor at a time: 1 + SPEED_SWING * wave, wave in [-1, 1]. */
    static double speedFactor(double seconds) {
        return 1 + SPEED_SWING * FakeLocation.wave(seconds, 0.4);
    }

    /** Integral of FakeLocation.wave(t, 0.4) dt. */
    private static double waveIntegral(double t) {
        double seed = 0.4;
        return -0.6 * 23.0 * Math.cos(t / 23.0 + seed) - 0.4 * 61.0 * Math.cos(t / 61.0 + seed * 2.7);
    }

    /** Metres travelled at base speed v (m/s) between two clock readings in milliseconds. */
    static double travelled(double v, long fromMs, long toMs) {
        if (v <= 0 || toMs <= fromMs) return 0;
        double a = fromMs / 1000.0, b = toMs / 1000.0;
        return v * ((b - a) + SPEED_SWING * (waveIntegral(b) - waveIntegral(a)));
    }

    /** Distance along the route at a clock reading, from the anchor fields of a runtime object. */
    static double distanceAt(JSONObject runtime, long nowMs) {
        double start = runtime.optDouble("routeAnchorDist", 0);
        if (runtime.optBoolean("paused")) return start;
        return start + travelled(runtime.optDouble("routeSpeed", 0), runtime.optLong("routeAnchorAt", nowMs), nowMs);
    }

    static final class Position {
        final double lat, lon, bearing, distance, progress;
        final int index;
        final boolean finished;

        Position(double lat, double lon, double bearing, double distance, double progress, int index, boolean finished) {
            this.lat = lat;
            this.lon = lon;
            this.bearing = bearing;
            this.distance = distance;
            this.progress = progress;
            this.index = index;
            this.finished = finished;
        }
    }

    Position at(double distance) {
        int last = lat.length - 1;
        if (distance >= total || last < 1) {
            int from = Math.max(0, last - 1);
            double bearing = last < 1 ? 0 : Geo.bearing(lat[from], lon[from], lat[last], lon[last]);
            return new Position(lat[Math.max(0, last)], lon[Math.max(0, last)], bearing, total, 1, last, true);
        }
        double d = Math.max(0, distance);
        // Last point whose cumulative distance is <= d.
        int lo = 0, hi = last;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (cumulative[mid] <= d) lo = mid;
            else hi = mid - 1;
        }
        int i = Math.min(lo, last - 1);
        double length = cumulative[i + 1] - cumulative[i];
        double fraction = length < 0.01 ? 0 : (d - cumulative[i]) / length;
        double[] p = Geo.interpolate(lat[i], lon[i], lat[i + 1], lon[i + 1], fraction);
        return new Position(p[0], p[1], Geo.bearing(lat[i], lon[i], lat[i + 1], lon[i + 1]), d,
                total <= 0 ? 1 : d / total, i, false);
    }

    /**
     * The live route position for a config, or null when the config is not playing the installed
     * route (point mode, another route, or no route installed in this process).
     */
    static Position live(JSONObject config, long nowMs) {
        JSONObject r = config == null ? null : config.optJSONObject("runtime");
        if (r == null || !"route".equals(r.optString("mode"))) return null;
        RoutePlan plan = installed;
        String id = r.optString("planId", "");
        if (plan == null || id.isEmpty() || !id.equals(plan.id)) return null;
        return plan.at(distanceAt(r, nowMs));
    }

    static Position live(JSONObject config) { return live(config, SystemClock.elapsedRealtime()); }
}

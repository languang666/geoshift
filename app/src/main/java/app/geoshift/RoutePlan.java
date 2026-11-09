package app.geoshift;

import android.os.SystemClock;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Route playback as a pure function of time, so the app and the system hook compute the same
 * position without talking to each other. The app sends the points once (ROUTE command) and an
 * anchor with every config: distance along the route at a moment of SystemClock.elapsedRealtime(),
 * the base speed, the repeat mode and whether playback is paused. The system hook then keeps moving
 * along the route while the app sleeps or is frozen.
 *
 * Speed = base speed x time wave (80-120 %, closed-form integral) x pace, where pace (<= 1) depends
 * on the position: it eases in after the start, slows for corners and eases out before the end.
 * Both factors stay exact by moving in "effort" units: effort(x) = integral of 1 / pace from the
 * start, so effort advances by exactly travelled(v, from, to) and is mapped back to a distance
 * through a per-route, per-speed table.
 */
final class RoutePlan {
    /** Speed varies by up to this fraction of the base speed. */
    static final double SPEED_SWING = 0.2;
    /** Comfortable acceleration and braking, m/s². */
    static final double ACCEL = 1.5;
    /** Repeat modes (runtime "routeRepeat"): stop at the end, loop back to the start, or go back and forth. */
    static final String ONCE = "once", LOOP = "loop", BOUNCE = "bounce";

    final String id;
    /** The points as sent; [open] is what playback follows. */
    final double[] lat, lon;
    final Track open;
    /** Length of the open route in metres. */
    final double total;
    private volatile Track closed;

    private static volatile RoutePlan installed;

    RoutePlan(String id, double[] lat, double[] lon) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
        open = new Track(lat, lon, false);
        total = open.length;
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

    static String repeat(JSONObject runtime) {
        String mode = runtime == null ? ONCE : runtime.optString("routeRepeat", ONCE);
        return LOOP.equals(mode) || BOUNCE.equals(mode) ? mode : ONCE;
    }

    /** A loop drives back from the end to the start along a closing straight section. */
    Track track(String mode) {
        if (!LOOP.equals(mode)) return open;
        Track t = closed;
        if (t == null) closed = t = new Track(lat, lon, true);
        return t;
    }

    // ---- Time -----------------------------------------------------------------------------------

    /** Speed factor at a time: 1 + SPEED_SWING * wave, wave in [-1, 1]. */
    static double speedFactor(double seconds) {
        return 1 + SPEED_SWING * FakeLocation.wave(seconds, 0.4);
    }

    /** Integral of FakeLocation.wave(t, 0.4) dt. */
    private static double waveIntegral(double t) {
        double seed = 0.4;
        return -0.6 * 23.0 * Math.cos(t / 23.0 + seed) - 0.4 * 61.0 * Math.cos(t / 61.0 + seed * 2.7);
    }

    /** Metres travelled at base speed v (m/s), full pace, between two clock readings in milliseconds. */
    static double travelled(double v, long fromMs, long toMs) {
        if (v <= 0 || toMs <= fromMs) return 0;
        double a = fromMs / 1000.0, b = toMs / 1000.0;
        return v * ((b - a) + SPEED_SWING * (waveIntegral(b) - waveIntegral(a)));
    }

    // ---- Distance <-> effort --------------------------------------------------------------------

    /**
     * Distance played so far at a clock reading, from the anchor fields of a runtime object. The
     * distance is unrolled: a loop or back-and-forth keeps counting past the route length.
     */
    double distanceAt(JSONObject runtime, long nowMs) {
        double start = runtime.optDouble("routeAnchorDist", 0);
        if (runtime.optBoolean("paused")) return start;
        double v = runtime.optDouble("routeSpeed", 0);
        double effort = travelled(v, runtime.optLong("routeAnchorAt", nowMs), nowMs);
        if (effort <= 0) return start;
        String mode = repeat(runtime);
        return distanceOf(mode, v, effortOf(mode, v, start) + effort);
    }

    double effortOf(String mode, double v, double d) {
        Track t = track(mode);
        double length = t.length;
        if (length < 0.01) return Math.max(0, d);
        Track.Pace p = t.pace(v);
        if (d <= 0) return d;
        if (ONCE.equals(mode)) return d >= length ? p.total + (d - length) : p.effort(d);
        long lap = (long) Math.floor(d / length);
        double w = d - lap * length;
        double base = lap * p.total;
        if (LOOP.equals(mode) || (lap & 1) == 0) return base + p.effort(w);
        return base + p.total - p.effort(length - w); // Coming back: the route read backwards.
    }

    double distanceOf(String mode, double v, double e) {
        Track t = track(mode);
        double length = t.length;
        if (length < 0.01) return Math.max(0, e);
        Track.Pace p = t.pace(v);
        if (e <= 0) return e;
        if (ONCE.equals(mode)) return e >= p.total ? length + (e - p.total) : p.distance(e);
        long lap = (long) Math.floor(e / p.total);
        double rest = e - lap * p.total;
        if (LOOP.equals(mode) || (lap & 1) == 0) return lap * length + p.distance(rest);
        return lap * length + length - p.distance(p.total - rest);
    }

    // ---- Position -------------------------------------------------------------------------------

    static final class Position {
        final double lat, lon, bearing, distance, progress;
        /** Position along the track (metres from its start) and whether it is being played backwards. */
        final double along;
        final boolean reversed;
        /** Speed fraction at this point (corners, start, end); multiplies base speed x time wave. */
        final double pace;
        final int index;
        final boolean finished;

        Position(double lat, double lon, double bearing, double distance, double progress, int index, boolean finished,
                 double along, boolean reversed, double pace) {
            this.lat = lat;
            this.lon = lon;
            this.bearing = bearing;
            this.distance = distance;
            this.progress = progress;
            this.index = index;
            this.finished = finished;
            this.along = along;
            this.reversed = reversed;
            this.pace = pace;
        }
    }

    /** Played once, without a speed profile. */
    Position at(double distance) { return at(ONCE, 0, distance); }

    /** Position after playing [distance] metres (unrolled) in [mode] at base speed [v]. */
    Position at(String mode, double v, double distance) {
        Track t = track(mode);
        double length = t.length;
        double d = Math.max(0, distance), x;
        boolean reversed = false;
        if (ONCE.equals(mode) || length < 0.01) {
            if (d >= length) {
                Track.Point end = t.point(length);
                return new Position(end.lat, end.lon, t.finalBearing(), Math.max(length, d), 1, end.index, true,
                        length, false, 0);
            }
            x = d;
        } else {
            long lap = (long) Math.floor(d / length);
            x = d - lap * length;
            if (BOUNCE.equals(mode) && (lap & 1) == 1) {
                x = length - x;
                reversed = true;
            }
        }
        Track.Point p = t.point(x);
        double bearing = reversed ? Geo.normalizeBearing(p.bearing + 180) : p.bearing;
        double pace = v > 0 ? t.pace(v).at(x) : 1;
        return new Position(p.lat, p.lon, bearing, d, length <= 0 ? 1 : x / length, p.index, false, x, reversed, pace);
    }

    /**
     * Anchor distance that continues from the current point after switching the repeat mode, so the
     * position does not jump.
     */
    double reanchor(JSONObject runtime, long nowMs, String next) {
        String mode = repeat(runtime);
        double v = runtime.optDouble("routeSpeed", 0);
        Position p = at(mode, v, distanceAt(runtime, nowMs));
        double x = Math.min(p.along, total); // The closing section of a loop is not part of the open route.
        if (BOUNCE.equals(next)) return p.reversed ? 2 * total - x : x;
        return x;
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
        String mode = repeat(r);
        return plan.at(mode, r.optDouble("routeSpeed", 0), plan.distanceAt(r, nowMs));
    }

    static Position live(JSONObject config) { return live(config, SystemClock.elapsedRealtime()); }

    // ---- Geometry -------------------------------------------------------------------------------

    /** A polyline with duplicate points removed; closed tracks end where they start. */
    static final class Track {
        /** Bearings blend over this distance on each side of a vertex instead of snapping. */
        static final double TURN_BLEND = 8;
        /** Turning is measured between the directions this far before and after a vertex. */
        static final double TURN_SPAN = 15;

        final double[] lat, lon, cumulative, bearings;
        final double length;
        final boolean closed;
        private volatile Pace pace;

        Track(double[] srcLat, double[] srcLon, boolean closed) {
            int n = Math.min(srcLat.length, srcLon.length);
            double[] la = new double[n + 1], lo = new double[n + 1];
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (m > 0 && Geo.distance(la[m - 1], lo[m - 1], srcLat[i], srcLon[i]) < 0.05) continue;
                la[m] = srcLat[i];
                lo[m] = srcLon[i];
                m++;
            }
            if (closed && m >= 2) {
                if (Geo.distance(la[m - 1], lo[m - 1], la[0], lo[0]) < 0.05) m--;
                la[m] = la[0];
                lo[m] = lo[0];
                m++;
            }
            if (m == 0 && n > 0) { la[0] = srcLat[0]; lo[0] = srcLon[0]; m = 1; }
            lat = Arrays.copyOf(la, m);
            lon = Arrays.copyOf(lo, m);
            this.closed = closed && m >= 3;
            cumulative = new double[m];
            bearings = new double[Math.max(0, m - 1)];
            for (int i = 1; i < m; i++) {
                cumulative[i] = cumulative[i - 1] + Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i]);
                bearings[i - 1] = Geo.bearing(lat[i - 1], lon[i - 1], lat[i], lon[i]);
            }
            length = m == 0 ? 0 : cumulative[m - 1];
        }

        static final class Point {
            final double lat, lon, bearing;
            final int index;
            Point(double lat, double lon, double bearing, int index) { this.lat = lat; this.lon = lon; this.bearing = bearing; this.index = index; }
        }

        double finalBearing() { return bearings.length == 0 ? 0 : bearings[bearings.length - 1]; }

        private int segment(double x) {
            int last = lat.length - 1;
            int lo = 0, hi = last;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (cumulative[mid] <= x) lo = mid;
                else hi = mid - 1;
            }
            return Math.max(0, Math.min(lo, last - 1));
        }

        /** Point and smoothed heading at [x] metres from the start (clamped). */
        Point point(double x) {
            if (lat.length < 2) return new Point(lat.length == 0 ? 0 : lat[0], lat.length == 0 ? 0 : lon[0], 0, 0);
            x = Math.max(0, Math.min(length, x));
            int i = segment(x);
            double seg = cumulative[i + 1] - cumulative[i];
            double fraction = seg < 1e-6 ? 0 : (x - cumulative[i]) / seg;
            double[] p = Geo.interpolate(lat[i], lon[i], lat[i + 1], lon[i + 1], fraction);
            return new Point(p[0], p[1], heading(i, x), i);
        }

        private double segLength(int i) { return cumulative[i + 1] - cumulative[i]; }

        /** Segment heading, blended with the neighbour's near each end so turns are gradual. */
        private double heading(int i, double x) {
            int segments = bearings.length;
            double own = bearings[i];
            int prev = i > 0 ? i - 1 : closed ? segments - 1 : -1;
            int next = i < segments - 1 ? i + 1 : closed ? 0 : -1;
            double fromStart = x - cumulative[i], toEnd = cumulative[i + 1] - x;
            if (prev >= 0) {
                double w = Math.min(TURN_BLEND, Math.min(segLength(prev), segLength(i)) / 2);
                if (w > 0.01 && fromStart < w) return blend(bearings[prev], own, 0.5 + fromStart / (2 * w));
            }
            if (next >= 0) {
                double w = Math.min(TURN_BLEND, Math.min(segLength(next), segLength(i)) / 2);
                if (w > 0.01 && toEnd < w) return blend(own, bearings[next], 0.5 - toEnd / (2 * w));
            }
            return own;
        }

        private static double blend(double a, double b, double t) {
            double d = ((b - a) % 360 + 540) % 360 - 180;
            return Geo.normalizeBearing(a + d * t);
        }

        /** Raw point (no heading) at [x], wrapping on closed tracks. */
        private double[] raw(double x) {
            if (closed) x = ((x % length) + length) % length;
            else x = Math.max(0, Math.min(length, x));
            int i = segment(x);
            double seg = segLength(i);
            return Geo.interpolate(lat[i], lon[i], lat[i + 1], lon[i + 1], seg < 1e-6 ? 0 : (x - cumulative[i]) / seg);
        }

        /** Turn at vertex k in degrees (0 = straight on, 180 = U-turn), measured over TURN_SPAN. */
        private double turn(int k) {
            double c = cumulative[k];
            double[] back = raw(closed ? c - TURN_SPAN : Math.max(0, c - TURN_SPAN));
            double[] fwd = raw(closed ? c + TURN_SPAN : Math.min(length, c + TURN_SPAN));
            if (Geo.distance(back[0], back[1], lat[k], lon[k]) < 0.5 || Geo.distance(lat[k], lon[k], fwd[0], fwd[1]) < 0.5) return 0;
            double in = Geo.bearing(back[0], back[1], lat[k], lon[k]), out = Geo.bearing(lat[k], lon[k], fwd[0], fwd[1]);
            return Math.abs(((out - in) % 360 + 540) % 360 - 180);
        }

        /** Speed profile for base speed v; cached for the last speed used. */
        Pace pace(double v) {
            Pace p = pace;
            if (p != null && p.v == v) return p;
            p = new Pace(this, v);
            pace = p;
            return p;
        }

        /** A slowdown: pace falls linearly to [min] at [at], over [before] metres before and [after] after. */
        private static final class Dip {
            final double at, before, after, min;
            Dip(double at, double before, double after, double min) { this.at = at; this.before = before; this.after = after; this.min = min; }
            double left() { return at - before; }
            double right() { return at + after; }
            double value(double x) {
                if (x < at) return before <= 0 ? 1 : min + (1 - min) * (at - x) / before;
                return after <= 0 ? (x == at ? min : 1) : min + (1 - min) * (x - at) / after;
            }
        }

        /**
         * Piecewise-linear pace over the track and its effort integral. Pace is evaluated at every dip
         * break point and taken as linear between them, so effort and its inverse are closed-form.
         */
        static final class Pace {
            final double v;
            final double[] x, g, e;
            final double total;

            Pace(Track t, double v) {
                this.v = v;
                double length = t.length;
                List<Dip> dips = new ArrayList<>();
                if (v > 0 && length > 0.01) {
                    if (!t.closed) {
                        // Ease in from a walking pace and ease out again before the end.
                        double startPace = Math.max(0.05, Math.min(1, 0.5 / v));
                        double ramp = Math.min(length / 3, Math.max(0.5, (v * v - 0.25) / (2 * ACCEL)));
                        if (startPace < 1) {
                            dips.add(new Dip(0, 0, ramp, startPace));
                            dips.add(new Dip(length, ramp, 0, startPace));
                        }
                    }
                    // Interior vertices; on a closed track also the start, which is where laps join.
                    int last = t.lat.length - 1;
                    for (int k = t.closed ? 0 : 1; k < last; k++) {
                        double angle = t.turn(k);
                        if (angle < 20) continue;
                        // Corner speed: never slower than walking, and at most a 70 % cut for a U-turn.
                        double corner = Math.max(Math.min(v, 1.4), v * (1 - 0.7 * angle / 180));
                        if (corner >= v * 0.98) continue;
                        double brake = Math.max(3, (v * v - corner * corner) / (2 * ACCEL));
                        double c = t.cumulative[k];
                        dips.add(new Dip(c, brake, brake, corner / v));
                        if (t.closed && k == 0) dips.add(new Dip(length, brake, brake, corner / v));
                    }
                }
                // Break points: track ends and every dip's edges and centre.
                double[] knots = new double[2 + dips.size() * 3];
                int n = 0;
                knots[n++] = 0;
                knots[n++] = length;
                for (Dip d : dips) {
                    knots[n++] = clamp(d.left(), length);
                    knots[n++] = clamp(d.at, length);
                    knots[n++] = clamp(d.right(), length);
                }
                Arrays.sort(knots, 0, n);
                int m = 0;
                for (int i = 0; i < n; i++) if (m == 0 || knots[i] - knots[m - 1] > 1e-6) knots[m++] = knots[i];
                x = Arrays.copyOf(knots, m);
                g = new double[m];
                dips.sort((a, b) -> Double.compare(a.left(), b.left()));
                double span = 0;
                for (Dip d : dips) span = Math.max(span, d.before + d.after);
                double[] lefts = new double[dips.size()];
                for (int i = 0; i < lefts.length; i++) lefts[i] = dips.get(i).left();
                for (int i = 0; i < m; i++) {
                    double value = 1;
                    int j = lowerBound(lefts, x[i] - span - 1e-9);
                    for (; j < lefts.length && lefts[j] <= x[i] + 1e-9; j++) {
                        Dip d = dips.get(j);
                        if (x[i] <= d.right() + 1e-9) value = Math.min(value, d.value(x[i]));
                    }
                    g[i] = value > 1 - 1e-9 ? 1 : Math.max(0.05, value);
                }
                e = new double[m];
                for (int i = 1; i < m; i++) e[i] = e[i - 1] + pieceEffort(x[i] - x[i - 1], g[i - 1], g[i], x[i] - x[i - 1]);
                total = m == 0 ? 0 : e[m - 1];
            }

            private static double clamp(double value, double length) { return Math.max(0, Math.min(length, value)); }

            private static int lowerBound(double[] a, double key) {
                int lo = 0, hi = a.length;
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (a[mid] < key) lo = mid + 1;
                    else hi = mid;
                }
                return lo;
            }

            /** Effort over the first [d] metres of a piece of length h whose pace goes linearly from g0 to g1. */
            private static double pieceEffort(double d, double g0, double g1, double h) {
                if (h <= 0 || d <= 0) return 0;
                double slope = (g1 - g0) / h;
                if (Math.abs(slope) < 1e-12) return d / g0;
                return Math.log((g0 + slope * d) / g0) / slope;
            }

            /** Inverse of pieceEffort. */
            private static double pieceDistance(double effort, double g0, double g1, double h) {
                if (h <= 0 || effort <= 0) return 0;
                double slope = (g1 - g0) / h;
                if (Math.abs(slope) < 1e-12) return Math.min(h, effort * g0);
                return Math.min(h, g0 * (Math.exp(effort * slope) - 1) / slope);
            }

            private int piece(double[] a, double value) {
                int lo = 0, hi = a.length - 1;
                while (lo < hi) {
                    int mid = (lo + hi + 1) >>> 1;
                    if (a[mid] <= value) lo = mid;
                    else hi = mid - 1;
                }
                return Math.max(0, Math.min(lo, a.length - 2));
            }

            double at(double pos) {
                if (x.length < 2) return 1;
                int i = piece(x, pos);
                double h = x[i + 1] - x[i];
                double f = h <= 0 ? 0 : Math.max(0, Math.min(1, (pos - x[i]) / h));
                return g[i] + (g[i + 1] - g[i]) * f;
            }

            /** Effort from the start to [pos] metres. */
            double effort(double pos) {
                if (x.length < 2) return pos;
                if (pos <= 0) return 0;
                if (pos >= x[x.length - 1]) return total;
                int i = piece(x, pos);
                return e[i] + pieceEffort(pos - x[i], g[i], g[i + 1], x[i + 1] - x[i]);
            }

            /** Metres from the start after [effort]. */
            double distance(double effort) {
                if (x.length < 2) return effort;
                if (effort <= 0) return 0;
                if (effort >= total) return x[x.length - 1];
                int i = piece(e, effort);
                return x[i] + pieceDistance(effort - e[i], g[i], g[i + 1], x[i + 1] - x[i]);
            }
        }
    }
}

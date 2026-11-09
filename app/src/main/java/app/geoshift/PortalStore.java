package app.geoshift;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.Map;

/** Private local state. The exported provider exposes only an explicitly selected subset. */
public final class PortalStore {
    public static final String PREFS = "geoshift";
    public static final String VERSION = BuildConfig.VERSION_NAME;

    /** Runtime keys handed to hooked target processes. The route is deliberately excluded. */
    static final String[] HOOK_RUNTIME_KEYS = {
            "enabled", "paused", "lat", "lon", "speed", "bearing", "mode",
            "routeAnchorDist", "routeAnchorAt", "routeSpeed",
            "wifiEnabled", "wifi", "cellEnabled", "cell", "cells"};
    static final String[] HOOK_SETTING_KEYS = {
            "backend", "privacyAccepted", "accuracy", "altitude", "jitter", "gnssEnabled", "satelliteCount", "interval",
            "protectWifi", "spoofEnvironment", "appScope", "allowedPackages"};

    private static final long RUNTIME_PERSIST_MS = 2000;
    private static final long HOOK_SEEN_PERSIST_MS = 10000;

    private static JSONObject live;
    /** Route coordinates parsed once per route change; playback reads them every tick. */
    private static double[] routeLat = new double[0], routeLon = new double[0];
    private static boolean routeParsed;
    /** Route points parsed for playback, shared with FakeLocation in this process. */
    private static RoutePlan routePlan;
    /** Id of the route the system hook confirmed it holds. */
    private static String routeSentId = "";
    private static JSONObject settingsCache;
    private static long lastRuntimeWrite;
    private static long lastHookSeenWrite;

    private PortalStore() {}

    public static SharedPreferences preferences(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Small, frequently written state (runtime position, hook heartbeat, diagnostics). Kept apart from
     * the records because SharedPreferences rewrites its whole file on every change: during a
     * simulation this is written every few seconds, and must not rewrite all saved routes each time.
     */
    public static SharedPreferences state(Context c) {
        SharedPreferences state = c.getApplicationContext().getSharedPreferences(PREFS + "_state", Context.MODE_PRIVATE);
        if (!stateMigrated) migrateState(c, state);
        return state;
    }

    private static volatile boolean stateMigrated;
    /** Whether Geoshift's own screen is showing; the service clock slows down while it is not. */
    public static volatile boolean uiVisible;

    private static boolean isStateKey(String key) {
        return key.equals("runtime") || key.equals("featureStatus") || key.equals("hookSeenAt")
                || key.equals("hookSystem") || key.startsWith("featureCleared_");
    }

    /** Moves state keys written by 2.5.0 and earlier out of the main file, once. */
    private static synchronized void migrateState(Context c, SharedPreferences state) {
        if (stateMigrated) return;
        SharedPreferences main = preferences(c);
        SharedPreferences.Editor to = state.edit(), from = main.edit();
        boolean moved = false;
        for (Map.Entry<String, ?> e : main.getAll().entrySet()) {
            String key = e.getKey();
            if (!isStateKey(key)) continue;
            Object v = e.getValue();
            if (v instanceof String) to.putString(key, (String) v);
            else if (v instanceof Long) to.putLong(key, (Long) v);
            else if (v instanceof Boolean) to.putBoolean(key, (Boolean) v);
            from.remove(key);
            moved = true;
        }
        if (moved) {
            to.commit();
            from.apply();
        }
        stateMigrated = true;
    }

    // ---- JSON helpers -------------------------------------------------------------------------

    public static JSONObject obj(Object... values) {
        JSONObject out = new JSONObject();
        try {
            for (int i = 0; i < values.length; i += 2) out.put(String.valueOf(values[i]), values[i + 1]);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return out;
    }

    private static JSONObject parse(String value) {
        try {
            return new JSONObject(value);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject copy(JSONObject value) {
        return parse(value.toString());
    }

    public static JSONObject merge(JSONObject target, JSONObject patch) {
        try {
            Iterator<String> keys = patch.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                target.put(k, patch.get(k));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return target;
    }

    /** Copies only the listed keys (shallow values are re-parsed so callers cannot mutate state). */
    private static JSONObject pick(JSONObject source, String... keys) {
        JSONObject out = new JSONObject();
        try {
            for (String k : keys) if (source.has(k)) out.put(k, source.get(k));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return copy(out);
    }

    // ---- Settings -----------------------------------------------------------------------------

    public static synchronized JSONObject settings(Context c) {
        if (settingsCache == null) {
            JSONObject value = obj(
                    "backend", "android_mock", "interval", 1000, "speedMultiplier", 1, "speedMode", "walk",
                    "accuracy", 5, "altitude", 0, "jitter", false, "enhanced", true, "secondReport", false,
                    "debug", false, "privacyAccepted", false, "theme", "system", "allowedPackages", "",
                    "gnssEnabled", false, "satelliteCount", 12, "mapSource", "amap", "protectWifi", false,
                    "spoofEnvironment", true, "appScope", "all");
            merge(value, parse(preferences(c).getString("settings", "{}")));
            value.remove("mapKey");
            // 2.5.0 dropped the AMap map SDK; forget the Android key it needed.
            if (preferences(c).contains("amapAndroidKey")) {
                preferences(c).edit().remove("amapAndroidKey").remove("amapAndroidKeyRevision").remove("amapSdkAccepted").apply();
            }
            try {
                value.put("mapKeyConfigured", !preferences(c).getString("mapKey", "").isEmpty());
            } catch (Exception ignored) {}
            settingsCache = value;
        }
        return copy(settingsCache);
    }

    public static synchronized void updateSettings(Context c, JSONObject patch) {
        JSONObject stored = parse(preferences(c).getString("settings", "{}"));
        JSONObject safe = copy(patch);
        safe.remove("mapKeyConfigured");
        safe.remove("mapKey");
        merge(stored, safe);
        SharedPreferences.Editor editor = preferences(c).edit().putString("settings", stored.toString());
        if (patch.has("mapKey")) editor.putString("mapKey", patch.optString("mapKey", "").trim());
        editor.apply();
        settingsCache = null;
        pushToSystem(c);
    }

    /** Whether the map tip ("map not showing? switch layers") was closed by the user. */
    public static boolean mapTipDismissed(Context c) {
        return preferences(c).getBoolean("mapTipDismissed", false);
    }

    public static void dismissMapTip(Context c) {
        preferences(c).edit().putBoolean("mapTipDismissed", true).apply();
    }

    /** Last way of drawing routes chosen in the route editor (straight / foot / bike / car). */
    public static String lastRoadProfile(Context c) {
        return preferences(c).getString("roadProfile", "straight");
    }

    public static void setLastRoadProfile(Context c, String profile) {
        preferences(c).edit().putString("roadProfile", profile).apply();
    }

    public static String mapKey(Context c) {
        return preferences(c).getString("mapKey", "");
    }

    /** Ground speed in metres per second for the configured movement mode and multiplier. */
    public static double speed(JSONObject settings) {
        String mode = settings.optString("speedMode", "walk");
        double base;
        switch (mode) {
            case "run": base = 3; break;
            case "bike": base = 6; break;
            case "drive": base = 13.9; break;
            case "fly": base = 50; break;
            default: base = 1.4;
        }
        return base * Math.max(0.01, Math.min(1000, settings.optDouble("speedMultiplier", 1)));
    }

    // ---- Runtime ------------------------------------------------------------------------------

    private static void loadRuntime(Context c) {
        if (live != null) return;
        live = obj("enabled", false, "paused", false, "mode", "point", "lat", 39.9042, "lon", 116.4074,
                "bearing", 0, "speed", 0, "route", new JSONArray(), "routeIndex", 0, "progress", 0,
                "overlay", false, "error", "", "wifiEnabled", false, "wifi", obj("ssid", "", "bssid", ""),
                "cellEnabled", false, "cell", new JSONObject());
        SharedPreferences prefs = preferences(c);
        merge(live, parse(state(c).getString("runtime", "{}")));
        String route = prefs.getString("runtimeRoute", null);
        if (route != null) {
            try {
                live.put("route", new JSONArray(route));
            } catch (Exception ignored) {}
        }
        // Nothing keeps running across a process restart.
        merge(live, obj("enabled", false, "paused", false, "overlay", false,
                "wifiEnabled", false, "cellEnabled", false, "error", ""));
    }

    public static synchronized JSONObject runtime(Context c) {
        loadRuntime(c);
        return copy(live);
    }

    /** Runtime without the (potentially large) route array. */
    public static synchronized JSONObject runtimeLight(Context c) {
        loadRuntime(c);
        JSONObject light = new JSONObject();
        try {
            Iterator<String> keys = live.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"route".equals(key)) light.put(key, live.get(key));
            }
        } catch (Exception ignored) {}
        return copy(light);
    }

    /**
     * The current route as {lats, lons}. Shared arrays: callers must not modify them. Avoids copying a
     * road route of up to 10,000 points through JSON on every playback tick.
     */
    public static synchronized double[][] routeCoordinates(Context c) {
        loadRuntime(c);
        if (!routeParsed) {
            JSONArray points = live.optJSONArray("route");
            int n = points == null ? 0 : points.length();
            double[] lat = new double[n], lon = new double[n];
            for (int i = 0; i < n; i++) {
                JSONObject p = points.optJSONObject(i);
                lat[i] = p == null ? 0 : p.optDouble("lat");
                lon[i] = p == null ? 0 : p.optDouble("lon");
            }
            routeLat = lat;
            routeLon = lon;
            routeParsed = true;
            routePlan = new RoutePlan(RoutePlan.idOf(lat, lon), lat, lon);
            RoutePlan.install(routePlan);
        }
        return new double[][]{routeLat, routeLon};
    }

    /** The current route as a playback plan (cached until the route changes). */
    public static synchronized RoutePlan routePlan(Context c) {
        routeCoordinates(c);
        return routePlan;
    }

    public static synchronized JSONObject runtimeFields(Context c, String... keys) {
        loadRuntime(c);
        return pick(live, keys);
    }

    public static synchronized void updateRuntime(Context c, JSONObject patch) {
        loadRuntime(c);
        merge(live, patch);
        if (patch.has("route")) routeParsed = false;
        long now = SystemClock.elapsedRealtime();
        boolean important = patch.has("enabled") || patch.has("paused") || patch.has("overlay")
                || patch.has("wifiEnabled") || patch.has("cellEnabled") || patch.has("error")
                || patch.has("route");
        if (now - lastRuntimeWrite > RUNTIME_PERSIST_MS || important) {
            JSONObject persisted = new JSONObject();
            try {
                Iterator<String> keys = live.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!"route".equals(key)) persisted.put(key, live.get(key));
                }
            } catch (Exception ignored) {}
            state(c).edit().putString("runtime", persisted.toString()).apply();
            // The route is large and changes rarely, so it stays with the records.
            if (patch.has("route")) preferences(c).edit().putString("runtimeRoute", String.valueOf(live.opt("route"))).apply();
            lastRuntimeWrite = now;
        }
        // Any change the system hook cares about goes out at once; route steps it computes itself.
        heartbeatToSystem(c);
    }

    /** Lives as long as this process; the system hook drops the config when it dies. */
    private static final android.os.Binder PROCESS_TOKEN = new android.os.Binder();

    /**
     * Sends the hook-visible config to the LSPosed system-framework hook. The hook only accepts it from
     * this app and keeps it until this process dies (it watches PROCESS_TOKEN), so screen-off sleep or
     * background freezing never lets real fixes through. Harmless when the module is not active.
     */
    public static synchronized void pushToSystem(Context c) { push(c, false); }

    /**
     * Clock-tick variant: pushes only when the config changed, or every HEARTBEAT_MS. During route
     * playback the moving position is left out of the comparison: the system hook plays the route
     * itself, so the app need not wake it every second.
     */
    public static synchronized void heartbeatToSystem(Context c) { push(c, true); }

    private static final long HEARTBEAT_MS = 10_000;
    private static String lastPushSignature = "";
    private static long lastPushAt;

    private static void push(Context c, boolean onlyIfChanged) {
        try {
            JSONObject settings = settings(c), safe = new JSONObject();
            for (String k : HOOK_SETTING_KEYS) safe.put(k, settings.opt(k));
            JSONObject runtime = runtimeFields(c, HOOK_RUNTIME_KEYS);
            RoutePlan plan = "route".equals(runtime.optString("mode")) ? routePlan(c) : null;
            if (plan != null) runtime.put("planId", plan.id);
            JSONObject config = obj("settings", safe, "runtime", runtime);
            JSONObject stable = new JSONObject(runtime.toString());
            if (plan != null) for (String k : new String[]{"lat", "lon", "bearing", "speed"}) stable.remove(k);
            String signature = safe.toString() + stable;
            long now = android.os.SystemClock.elapsedRealtime();
            if (onlyIfChanged && signature.equals(lastPushSignature) && now - lastPushAt < HEARTBEAT_MS) return;
            android.os.Bundle b = new android.os.Bundle();
            b.putString("config", config.toString());
            b.putBinder("token", PROCESS_TOKEN);
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            lm.sendExtraCommand("geoshift", "SET", b);
            lastPushSignature = signature;
            lastPushAt = now;
            // The system hook plays the route itself; send the points once per route (or again after
            // system_server restarted and lost them).
            if (plan != null && (b.getBoolean("needRoute") || !plan.id.equals(routeSentId))) {
                android.os.Bundle route = new android.os.Bundle();
                route.putString("routeId", plan.id);
                route.putDoubleArray("lat", plan.lat);
                route.putDoubleArray("lon", plan.lon);
                lm.sendExtraCommand("geoshift", "ROUTE", route);
                if (route.getBoolean("ok")) routeSentId = plan.id;
            }
        } catch (Exception ignored) {}
    }

    // ---- Records and logs ---------------------------------------------------------------------

    public static synchronized JSONArray items(Context c, String key) {
        try {
            return new JSONArray(preferences(c).getString(key, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static synchronized void saveItems(Context c, String key, JSONArray values) {
        preferences(c).edit().putString(key, values.toString()).apply();
    }

    public static synchronized void log(Context c, String message) {
        JSONArray old = items(c, "logs"), out = new JSONArray();
        out.put(obj("time", System.currentTimeMillis(), "message", message));
        for (int i = 0; i < Math.min(99, old.length()); i++) out.put(old.opt(i));
        saveItems(c, "logs", out);
    }

    // ---- Hook / feature status ----------------------------------------------------------------

    public static boolean hookActive(Context c) {
        long age = System.currentTimeMillis() - state(c).getLong("hookSeenAt", 0);
        return age >= 0 && age < 60000;
    }

    /** Records that the hook answered. Writes are throttled because targets poll every 500 ms. */
    public static synchronized void markHookSeen(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (lastHookSeenWrite != 0 && now - lastHookSeenWrite < HOOK_SEEN_PERSIST_MS) return;
        lastHookSeenWrite = now;
        state(c).edit().putLong("hookSeenAt", System.currentTimeMillis()).apply();
    }

    private static JSONObject featureStatus(Context c) {
        return parse(state(c).getString("featureStatus", "{}"));
    }

    public static synchronized void reportFeature(Context c, String feature, JSONObject status) {
        try {
            JSONObject statuses = featureStatus(c);
            statuses.put(feature, status);
            state(c).edit().putString("featureStatus", statuses.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized void clearFeature(Context c, String feature) {
        JSONObject statuses = featureStatus(c);
        statuses.remove(feature);
        // Remember when it was cleared so older reports still held by the system hook are ignored.
        state(c).edit().putString("featureStatus", statuses.toString())
                .putLong("featureCleared_" + feature, System.currentTimeMillis()).apply();
    }

    /** Stores a report from the system hook if it is newer than both the stored one and the last clear. */
    public static synchronized void mergeReport(Context c, String feature, JSONObject report) {
        long time = report.optLong("time");
        if (time <= state(c).getLong("featureCleared_" + feature, 0)) return;
        JSONObject old = featureStatus(c).optJSONObject(feature);
        if (old != null && old.optLong("time") >= time) return;
        reportFeature(c, feature, report);
    }

    // ---- Device capabilities ------------------------------------------------------------------

    public static boolean mockSelected(Context c) {
        try {
            AppOpsManager a = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return a != null && a.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(),
                    c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean locationEnabled(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled("gps") || lm.isProviderEnabled("network");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean preciseLocationGranted(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Most recent last-known fix across enabled providers, or null. Requires fine location. */
    public static Location lastKnownLocation(Context c) {
        if (!preciseLocationGranted(c)) return null;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        for (String provider : lm.getProviders(true)) {
            try {
                Location candidate = lm.getLastKnownLocation(provider);
                if (candidate != null && (best == null
                        || candidate.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos()))
                    best = candidate;
            } catch (SecurityException denied) {
                return null;
            } catch (Exception ignored) {}
        }
        return best;
    }

    public static boolean isMock(Location location) {
        return Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider();
    }

    public static JSONObject capabilities(Context c) {
        return obj(
                "preciseLocation", preciseLocationGranted(c),
                "locationEnabled", locationEnabled(c),
                "notifications", Build.VERSION.SDK_INT < 33
                        || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                "overlay", Settings.canDrawOverlays(c),
                "phoneAvailable", c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
                "mockSelected", mockSelected(c),
                "hookActive", hookActive(c),
                "systemHook", hookActive(c) && state(c).getBoolean("hookSystem", false),
                "nearbyWifi", Build.VERSION.SDK_INT < 33
                        || c.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED,
                "cellSimulation", true,
                "cellSimulationTypes", new JSONArray().put("GSM").put("LTE"),
                "gnssSimulation", Build.VERSION.SDK_INT >= 30);
    }

    // ---- Snapshots for the UI -----------------------------------------------------------------

    public static synchronized JSONObject snapshot(Context c) {
        return obj(
                "app", obj("name", "Geoshift", "version", VERSION, "independent", true),
                "settings", settings(c),
                "runtime", runtime(c),
                "capabilities", capabilities(c),
                "locations", items(c, "locations"),
                "routes", items(c, "routes"),
                "wifiPresets", items(c, "wifiPresets"),
                "cellPresets", items(c, "cellPresets"),
                "environments", items(c, "environments"),
                "logs", items(c, "logs"),
                "featureStatus", featureStatus(c));
    }

    public static synchronized JSONObject status(Context c) {
        return obj("runtime", runtimeLight(c), "capabilities", capabilities(c), "featureStatus", featureStatus(c));
    }

    public static JSONObject exportData(Context c) {
        return obj("format", "geoshift", "version", 1, "createdAt", System.currentTimeMillis(),
                "locations", items(c, "locations"),
                "routes", items(c, "routes"),
                "wifiPresets", items(c, "wifiPresets"),
                "cellPresets", items(c, "cellPresets"),
                "environments", items(c, "environments"));
    }
}

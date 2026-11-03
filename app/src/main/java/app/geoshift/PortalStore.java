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

/** Private local state. The exported provider exposes only an explicitly selected subset. */
public final class PortalStore {
    public static final String PREFS = "geoshift";
    public static final String VERSION = BuildConfig.VERSION_NAME;

    /** Runtime keys handed to hooked target processes. The route is deliberately excluded. */
    static final String[] HOOK_RUNTIME_KEYS = {
            "enabled", "paused", "lat", "lon", "speed", "bearing",
            "wifiEnabled", "wifi", "cellEnabled", "cell", "cells"};
    static final String[] HOOK_SETTING_KEYS = {
            "backend", "privacyAccepted", "accuracy", "altitude", "jitter", "gnssEnabled", "satelliteCount", "interval", "protectWifi", "spoofEnvironment"};

    private static final long RUNTIME_PERSIST_MS = 2000;
    private static final long HOOK_SEEN_PERSIST_MS = 10000;

    private static JSONObject live;
    private static JSONObject settingsCache;
    private static long lastRuntimeWrite;
    private static long lastHookSeenWrite;

    private PortalStore() {}

    public static SharedPreferences preferences(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
                    "gnssEnabled", false, "satelliteCount", 12, "mapSource", "osm", "protectWifi", false,
                    "spoofEnvironment", true);
            merge(value, parse(preferences(c).getString("settings", "{}")));
            value.remove("mapKey");
            value.remove("amapAndroidKey");
            try {
                value.put("mapKeyConfigured", !preferences(c).getString("mapKey", "").isEmpty());
                value.put("amapAndroidKeyConfigured", !amapAndroidKey(c).isEmpty());
                value.put("amapAndroidKeyRevision", preferences(c).getLong("amapAndroidKeyRevision", 0));
                value.put("amapSdkAccepted", preferences(c).getBoolean("amapSdkAccepted", false));
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
        safe.remove("amapAndroidKey");
        safe.remove("amapAndroidKeyConfigured");
        safe.remove("amapAndroidKeyRevision");
        safe.remove("amapSdkAccepted");
        merge(stored, safe);
        SharedPreferences.Editor editor = preferences(c).edit().putString("settings", stored.toString());
        if (patch.has("mapKey")) editor.putString("mapKey", patch.optString("mapKey", "").trim());
        if (patch.has("amapAndroidKey")) {
            String key = patch.optString("amapAndroidKey", "").trim();
            editor.putString("amapAndroidKey", key);
            editor.putLong("amapAndroidKeyRevision", preferences(c).getLong("amapAndroidKeyRevision", 0) + 1);
            editor.putBoolean("amapSdkAccepted", !key.isEmpty() && patch.optBoolean("amapSdkAccepted"));
        }
        editor.apply();
        settingsCache = null;
        pushToSystem(c);
    }

    public static String mapKey(Context c) {
        return preferences(c).getString("mapKey", "");
    }

    public static String amapAndroidKey(Context c) {
        return preferences(c).getString("amapAndroidKey", "");
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
        merge(live, parse(prefs.getString("runtime", "{}")));
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

    public static synchronized JSONObject runtimeFields(Context c, String... keys) {
        loadRuntime(c);
        return pick(live, keys);
    }

    public static synchronized void updateRuntime(Context c, JSONObject patch) {
        loadRuntime(c);
        merge(live, patch);
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
            SharedPreferences.Editor editor = preferences(c).edit().putString("runtime", persisted.toString());
            if (patch.has("route")) editor.putString("runtimeRoute", String.valueOf(live.opt("route")));
            editor.apply();
            lastRuntimeWrite = now;
        }
        pushToSystem(c);
    }

    /**
     * Sends the hook-visible config to the LSPosed system-framework hook. The hook only accepts it from
     * this app, and stops injecting by itself if no push arrives for 20 s, so callers refresh it often.
     * Harmless when the module is not active (the command is simply ignored).
     */
    public static synchronized void pushToSystem(Context c) {
        try {
            JSONObject settings = settings(c), safe = new JSONObject();
            for (String k : HOOK_SETTING_KEYS) safe.put(k, settings.opt(k));
            JSONObject config = obj("settings", safe, "runtime", runtimeFields(c, HOOK_RUNTIME_KEYS));
            android.os.Bundle b = new android.os.Bundle();
            b.putString("config", config.toString());
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) lm.sendExtraCommand("geoshift", "SET", b);
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
        long age = System.currentTimeMillis() - preferences(c).getLong("hookSeenAt", 0);
        return age >= 0 && age < 60000;
    }

    /** Records that the hook answered. Writes are throttled because targets poll every 500 ms. */
    public static synchronized void markHookSeen(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (lastHookSeenWrite != 0 && now - lastHookSeenWrite < HOOK_SEEN_PERSIST_MS) return;
        lastHookSeenWrite = now;
        preferences(c).edit().putLong("hookSeenAt", System.currentTimeMillis()).apply();
    }

    private static JSONObject featureStatus(Context c) {
        return parse(preferences(c).getString("featureStatus", "{}"));
    }

    public static synchronized void reportFeature(Context c, String feature, JSONObject status) {
        try {
            JSONObject statuses = featureStatus(c);
            statuses.put(feature, status);
            preferences(c).edit().putString("featureStatus", statuses.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized void clearFeature(Context c, String feature) {
        JSONObject statuses = featureStatus(c);
        statuses.remove(feature);
        // Remember when it was cleared so older reports still held by the system hook are ignored.
        preferences(c).edit().putString("featureStatus", statuses.toString())
                .putLong("featureCleared_" + feature, System.currentTimeMillis()).apply();
    }

    /** Stores a report from the system hook if it is newer than both the stored one and the last clear. */
    public static synchronized void mergeReport(Context c, String feature, JSONObject report) {
        long time = report.optLong("time");
        if (time <= preferences(c).getLong("featureCleared_" + feature, 0)) return;
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
                "systemHook", hookActive(c) && preferences(c).getBoolean("hookSystem", false),
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

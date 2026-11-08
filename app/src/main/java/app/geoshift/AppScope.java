package app.geoshift;

import android.content.Context;
import android.content.pm.PackageManager;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/**
 * Which apps the simulation applies to. Settings: appScope = "all" (default) or "selected", and
 * allowedPackages = the chosen package names. Works in any hooked process that has a Context for the
 * package manager. The list only decides coverage; it never grants access to the command channel.
 */
final class AppScope {
    private AppScope() {}

    private static volatile Context context;
    private static volatile String cachedList = null;
    private static volatile Set<String> cachedPackages = new HashSet<>();
    private static final Map<Integer, Boolean> uidCache = new ConcurrentHashMap<>();

    static void setContext(Context c) { if (c != null) context = c; }

    static boolean selectedOnly(JSONObject config) {
        JSONObject s = config == null ? null : config.optJSONObject("settings");
        return s != null && "selected".equals(s.optString("appScope"));
    }

    static Set<String> packages(JSONObject config) {
        JSONObject s = config == null ? null : config.optJSONObject("settings");
        String list = s == null ? "" : s.optString("allowedPackages", "");
        if (!list.equals(cachedList)) {
            Set<String> out = new HashSet<>();
            for (String p : list.split("[,\\s]+")) if (!p.isEmpty()) out.add(p);
            cachedPackages = out;
            uidCache.clear();
            cachedList = list;
        }
        return cachedPackages;
    }

    /** Whether an app uid is in the chosen list. System uids (below 10000) never are. */
    static boolean listed(JSONObject config, int uid) {
        if (uid < 10000) return false;
        Set<String> chosen = packages(config);
        if (chosen.isEmpty()) return false;
        int appId = uid % 100000;
        Boolean known = uidCache.get(uid);
        if (known != null) return known;
        boolean result = false;
        Context c = context;
        if (c == null) {
            // system_server loads the module before its system context exists; fetch it lazily.
            try {
                Object thread = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
                if (thread != null) c = context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
            } catch (Throwable ignored) {}
            if (c == null) return false; // Unknown yet: do not cache.
        }
        {
            try {
                PackageManager pm = c.getPackageManager();
                String[] names = pm.getPackagesForUid(uid);
                if (names == null && appId != uid) names = pm.getPackagesForUid(appId);
                if (names != null) for (String n : names) if (chosen.contains(n)) { result = true; break; }
            } catch (Throwable ignored) {}
        }
        uidCache.put(uid, result);
        return result;
    }

    static boolean listed(JSONObject config, String packageName) {
        return packageName != null && packages(config).contains(packageName);
    }

    /**
     * Whether the simulation applies to an app uid: always in "all" mode (system uids included, as
     * before); only chosen apps in "selected" mode. An unknown owner (uid < 0) counts as not chosen.
     */
    static boolean covers(JSONObject config, int uid) {
        if (!selectedOnly(config)) return true;
        return uid >= 0 && listed(config, uid);
    }
}

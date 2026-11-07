package app.geoshift;

import android.content.Context;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs inside system_server (LSPosed scope "系统框架"). Holds the simulation config pushed by the app
 * and replaces Android LocationProviderManager reports, plus every location, satellite, Wi-Fi and
 * cell reply sent to apps (see SystemSpoofHooks / WifiPrivacyHooks), so ordinary apps need no scope.
 * Android 12+ (API 31) only; older versions use app hooks.
 *
 * Commands arrive through LocationManager.sendExtraCommand(provider = "geoshift", command, extras):
 *   PING   (app only)  -> extras: active, system, reports
 *   SET    (app only)  <- extras: config (JSON)
 *   GET    (any app)   -> extras: config
 *   REPORT (any app)   <- extras: feature, ok, error
 */
final class SystemHook {
    static final String COMMAND_PROVIDER = "geoshift";
    private static final String SELF = "app.geoshift";
    /** The app must refresh the config this often or location injection stops by itself. */
    private static final long LEASE_MS = 20_000;
    private static final List<String> INJECT_PROVIDERS = java.util.Arrays.asList("gps", "network", "fused");

    private static volatile JSONObject config;
    private static volatile long setAt;
    private static volatile boolean systemLocation;
    private static int ownUid = -1;
    private static final Set<Object> managers = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));
    private static final Map<String, JSONObject> reports = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> providerHits = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> pumpSuccess = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> pumpFailure = new ConcurrentHashMap<>();
    private static volatile WeakReference<Object> locationService = new WeakReference<>(null);
    private static volatile String pumpLastError = "";
    private static long lastPumpReportAt;
    private static long lastSystemReportAt;
    private static long lastWifiReportAt;
    private static String lastWifiStage = "";
    private static int deliveryHooks;
    private static final AtomicLong deliveryHits = new AtomicLong();
    private static volatile String lastDeliveryPackage = "尚无交付目标";
    private static Handler injector;
    private static final Runnable inject = SystemHook::injectAll;
    /** True while the pump itself reports a simulated fix on this thread. */
    private static final ThreadLocal<Boolean> INJECTING = ThreadLocal.withInitial(() -> false);

    private SystemHook() {}

    static void install(ClassLoader loader) {
        hookCommands(loader);
        try { new WifiPrivacyHooks(new WifiPrivacyHooks.Controller() {
            @Override public JSONObject activeFor(int uid) {
                if (uid < 10000 || uid == ownUid()) return null;
                JSONObject c = lsposedConfig();
                if (c == null) return null;
                if (FakeEnvironment.wifiPreset(c) != null) return c;
                return environmentActive() != null ? c : null;
            }
            @Override public void report(Throwable error, String stage, String detail) { wifiReport(error, stage, detail); }
        }).install(loader); }
        catch (Throwable e) { wifiReport(e, "error", "Wi-Fi 伪装入口安装失败，定位入口继续独立安装"); }
        try { new SystemSpoofHooks(new SystemSpoofHooks.Controller() {
            @Override public JSONObject location() { return locationActive(); }
            @Override public JSONObject cells() {
                JSONObject c = lsposedConfig();
                if (c == null) return null;
                return FakeEnvironment.cellsConfigured(c) ? c : environmentActive();
            }
            @Override public JSONObject gnss() {
                JSONObject c = lsposedConfig();
                if (c == null) return null;
                return c.optJSONObject("settings").optBoolean("gnssEnabled") ? c : environmentActive();
            }
            @Override public void report(String stage, String detail) { environmentReport(stage, detail); }
        }).install(loader); }
        catch (Throwable e) { environmentReport("error", "卫星/基站伪装入口安装失败：" + e.getClass().getSimpleName()); }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                hookProviderManagers(loader);
                systemLocation = true;
                reports.put("location_system", new JSONObject().put("ok", true).put("time", System.currentTimeMillis())
                        .put("stage", "installed").put("detail", "系统定位入口已安装，等待 gps / network / fused 注入")
                        .put("package", "android").put("process", "system_server"));
            } catch (Throwable e) {
                XposedBridge.log("Geoshift system location: " + e);
                try {
                    reports.put("location_system", new JSONObject().put("ok", false).put("time", System.currentTimeMillis())
                            .put("stage", "error").put("error", e.getClass().getSimpleName())
                            .put("detail", "系统定位入口安装失败，请查看 LSPosed 模块日志")
                            .put("package", "android").put("process", "system_server"));
                } catch (Exception ignored) {}
            }
        }
    }

    // ---- Commands -----------------------------------------------------------------------------

    private static void hookCommands(ClassLoader loader) {
        Class<?> service = XposedHelpers.findClassIfExists("com.android.server.location.LocationManagerService", loader);
        if (service == null) service = XposedHelpers.findClassIfExists("com.android.server.LocationManagerService", loader);
        if (service == null) {
            XposedBridge.log("Geoshift: LocationManagerService not found");
            return;
        }
        XposedBridge.hookAllMethods(service, "sendExtraCommand", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                if (h.args.length < 3 || !COMMAND_PROVIDER.equals(h.args[0])) return;
                locationService = new WeakReference<>(h.thisObject);
                // Android 12+ declares this method void; older versions return boolean.
                boolean returnsBoolean = h.method instanceof java.lang.reflect.Method
                        && ((java.lang.reflect.Method) h.method).getReturnType() == boolean.class;
                h.setResult(returnsBoolean ? Boolean.TRUE : null);
                if (!(h.args[2] instanceof Bundle)) return;
                Bundle extras = (Bundle) h.args[2];
                int uid = Binder.getCallingUid();
                try {
                    handle(String.valueOf(h.args[1]), extras, uid);
                } catch (Throwable e) {
                    extras.putString("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        });
    }

    private static void handle(String command, Bundle extras, int uid) throws Exception {
        boolean own = uid == ownUid();
        switch (command) {
            case "PING":
                if (!own) return;
                extras.putBoolean("active", true);
                extras.putBoolean("system", systemLocation);
                extras.putInt("managers", managers.size());
                extras.putString("reports", new JSONObject(reports).toString());
                break;
            case "SET":
                if (!own) return;
                config = new JSONObject(extras.getString("config", "{}"));
                setAt = SystemClock.elapsedRealtime();
                extras.putBoolean("ok", true);
                schedule(0);
                break;
            case "GET": {
                JSONObject c = current();
                if (c != null) extras.putString("config", c.toString());
                extras.putBoolean("system", systemLocation);
                break;
            }
            case "REPORT": {
                String feature = extras.getString("feature", "");
                if (!java.util.Arrays.asList("location", "wifi", "cell", "gnss", "bluetooth").contains(feature)) return;
                String error = extras.getString("error", "");
                if (error.length() > 300) error = error.substring(0, 300);
                reports.put(feature, new JSONObject().put("ok", extras.getBoolean("ok")).put("time", System.currentTimeMillis())
                        .put("error", error).put("package", packageOf(uid))
                        .put("stage", limited(extras.getString("stage", "read"), 30))
                        .put("detail", limited(extras.getString("detail", ""), 200))
                        .put("process", limited(extras.getString("process", ""), 150)));
                break;
            }
            default:
        }
    }

    private static String limited(String value, int max) { return value.length() > max ? value.substring(0, max) : value; }

    private static Context systemContext() throws Exception {
        Object thread = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentActivityThread");
        return (Context) XposedHelpers.callMethod(thread, "getSystemContext");
    }

    private static int ownUid() {
        if (ownUid < 0) {
            try {
                ownUid = systemContext().getPackageManager().getApplicationInfo(SELF, 0).uid;
            } catch (Throwable e) {
                return -2;
            }
        }
        return ownUid;
    }

    private static String packageOf(int uid) {
        try {
            String name = systemContext().getPackageManager().getNameForUid(uid);
            return name == null ? String.valueOf(uid) : name;
        } catch (Throwable e) {
            return String.valueOf(uid);
        }
    }

    /** Config if the lease is fresh, else null (the app stopped refreshing it). */
    static JSONObject current() {
        JSONObject c = config;
        if (c == null || SystemClock.elapsedRealtime() - setAt > LEASE_MS) return null;
        return c;
    }

    /** Fresh config when LSPosed mode is selected, else null. */
    private static JSONObject lsposedConfig() {
        JSONObject c = current();
        if (c == null) return null;
        JSONObject s = c.optJSONObject("settings"), r = c.optJSONObject("runtime");
        if (s == null || r == null || !s.optBoolean("privacyAccepted") || !"lsposed".equals(s.optString("backend"))) return null;
        return c;
    }

    /** Config when location simulation is on in LSPosed mode, else null. */
    private static JSONObject locationActive() {
        JSONObject c = lsposedConfig();
        return c != null && c.optJSONObject("runtime").optBoolean("enabled") ? c : null;
    }

    /** Config while simulating with the real Wi-Fi / cells / satellites hidden, else null. */
    private static JSONObject environmentActive() {
        JSONObject c = locationActive();
        return c != null && FakeEnvironment.spoofEnvironment(c.optJSONObject("settings")) ? c : null;
    }

    private static long lastEnvironmentReportAt;
    private static String lastEnvironmentStage = "";

    private static synchronized void environmentReport(String stage, String detail) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastEnvironmentReportAt < 3000 && stage.equals(lastEnvironmentStage)) return;
        lastEnvironmentReportAt = now;
        lastEnvironmentStage = stage;
        try {
            reports.put("environment_system", new JSONObject().put("ok", !"error".equals(stage)).put("time", System.currentTimeMillis())
                    .put("stage", stage).put("detail", limited(detail, 200)).put("package", "android").put("process", "system_server"));
        } catch (Exception ignored) {}
    }

    // ---- Location injection (Android 12+) -----------------------------------------------------

    private static void hookProviderManagers(ClassLoader loader) {
        Class<?> lpm = XposedHelpers.findClass("com.android.server.location.provider.LocationProviderManager", loader);
        XposedBridge.hookAllConstructors(lpm, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                managers.add(h.thisObject);
            }
        });
        // Replace whatever the real provider reports (GPS fix, network fix) before it reaches apps.
        XC_MethodHook reportHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                managers.add(h.thisObject);
                if (INJECTING.get()) return; // Our own pump: already the simulated fix.
                JSONObject c = locationActive();
                if (c == null || h.args.length == 0 || h.args[0] == null) return;
                String name = nameOf(h.thisObject);
                if (!INJECT_PROVIDERS.contains(name) && !"passive".equals(name)) return;
                if (INJECT_PROVIDERS.contains(name)) {
                    // Drop real reports while simulating; the pump delivers the simulated fix at the set
                    // interval. Replacing them instead loops: a fused provider listening passively sees
                    // the replaced fix as new input and reports again, hundreds of times a second.
                    h.setResult(null);
                    noteProvider(name);
                    return;
                }
                try {
                    h.args[0] = wrap(FakeLocation.build(c, providerFor(name)));
                    noteProvider(name);
                } catch (Throwable e) {
                    XposedBridge.log("Geoshift onReportLocation: " + e);
                }
            }
        };
        int reportHooks = 0;
        for (Method method : DeliveryTargets.reports(lpm)) { XposedBridge.hookMethod(method, reportHook); reportHooks++; }
        XC_MethodHook last = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                JSONObject c = locationActive();
                if (c == null || h.hasThrowable()) return;
                if (!(h.method instanceof java.lang.reflect.Method)
                        || ((java.lang.reflect.Method) h.method).getReturnType() != Location.class) return;
                String name = nameOf(h.thisObject);
                if (!INJECT_PROVIDERS.contains(name) && !"passive".equals(name)) return;
                try {
                    h.setResult(FakeLocation.build(c, providerFor(name)));
                    noteProvider(name);
                } catch (Throwable ignored) {}
            }
        };
        int lastHooks = 0;
        for (Method method : DeliveryTargets.cached(lpm)) { XposedBridge.hookMethod(method, last); lastHooks++; }
        try { deliveryHooks = hookDeliveries(lpm); }
        catch (Throwable e) { XposedBridge.log("Geoshift optional delivery hook: " + e.getClass().getSimpleName()); }
        if (reportHooks == 0 || lastHooks == 0) throw new IllegalStateException("定位入口缺失：report=" + reportHooks + ", last=" + lastHooks);
    }

    private static int hookDeliveries(Class<?> manager) {
        int count = 0;
        for (Method method : DeliveryTargets.find(manager)) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam h) {
                        JSONObject c = locationActive();
                        if (c == null || h.args[0] == null) return;
                        try {
                            Object last = XposedHelpers.callMethod(h.args[0], "getLastLocation");
                            if (!(last instanceof Location)) return;
                            String provider = ((Location) last).getProvider();
                            if (!INJECT_PROVIDERS.contains(provider) && !"passive".equals(provider)) return;
                            h.args[0] = wrap(FakeLocation.build(c, providerFor(provider)));
                            try {
                                Object identity = XposedHelpers.callMethod(h.thisObject, "getIdentity");
                                lastDeliveryPackage = limited(String.valueOf(XposedHelpers.callMethod(identity, "getPackageName")), 100);
                            } catch (Throwable unavailable) { lastDeliveryPackage = "目标包名不可用"; }
                            deliveryHits.incrementAndGet();
                            noteProvider(provider);
                        } catch (Throwable e) { XposedBridge.log("Geoshift delivery: " + e.getClass().getSimpleName()); }
                    }
                });
                count++;
        }
        return count;
    }

    private static synchronized void wifiReport(Throwable error, String stage, String detail) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastWifiReportAt < 3000 && stage.equals(lastWifiStage)) return;
        lastWifiReportAt = now;
        lastWifiStage = stage;
        try {
            reports.put("wifi_privacy", new JSONObject().put("ok", error == null).put("time", System.currentTimeMillis())
                    .put("stage", stage).put("detail", detail).put("error", error == null ? "" : error.getClass().getSimpleName())
                    .put("package", "android").put("process", "system_server"));
        } catch (Exception ignored) {}
    }

    private static synchronized void noteProvider(String provider) {
        providerHits.computeIfAbsent(provider, key -> new AtomicLong()).incrementAndGet();
        long now = SystemClock.elapsedRealtime();
        if (now - lastSystemReportAt < 3000) return;
        lastSystemReportAt = now;
        StringBuilder detail = new StringBuilder("已拦截的真实上报次数（不是应用接收确认）：");
        for (String name : INJECT_PROVIDERS) {
            AtomicLong hits = providerHits.get(name);
            detail.append(name).append('=').append(hits == null ? 0 : hits.get()).append(' ');
        }
        detail.append("末端入口=").append(deliveryHooks).append(" 交付改写=").append(deliveryHits.get());
        detail.append(" 最近目标=").append(lastDeliveryPackage);
        try {
            reports.put("location_system", new JSONObject().put("ok", true).put("time", System.currentTimeMillis())
                    .put("stage", "injected").put("detail", detail.toString()).put("package", "android").put("process", "system_server"));
        } catch (Exception ignored) {}
    }

    private static String nameOf(Object manager) {
        try {
            Object name = XposedHelpers.getObjectField(manager, "mName");
            return name == null ? "" : name.toString();
        } catch (Throwable unavailable) { return ""; }
    }

    private static String providerFor(String managerName) {
        return "passive".equals(managerName) ? "gps" : managerName;
    }

    private static Object wrap(Location location) {
        return LocationPacket.wrap(location);
    }

    private static synchronized void schedule(long delay) {
        if (!systemLocation) return;
        if (injector == null) {
            HandlerThread thread = new HandlerThread("geoshift-inject");
            thread.start();
            injector = new Handler(thread.getLooper());
        }
        injector.removeCallbacks(inject);
        injector.postDelayed(inject, delay);
    }

    /**
     * Reports the simulated location through each provider's own lock, exactly like a real fix,
     * so apps keep receiving updates even when there is no real GPS / network signal.
     */
    private static void injectAll() {
        JSONObject c = locationActive();
        if (c == null) return; // Lease expired or simulation off: stop until the next SET.
        // Providers can exist before our constructor hook is installed. Discover them from the
        // live service rather than waiting for an unrelated real fix to happen first.
        Object service = locationService.get();
        if (service != null) ProviderDiscovery.discover(INJECT_PROVIDERS, managers,
                name -> XposedHelpers.callMethod(service, "getLocationProviderManager", name));
        List<Object> snapshot;
        synchronized (managers) {
            snapshot = new ArrayList<>(managers);
        }
        int cycleSuccess = 0, cycleFailure = 0;
        String cycleError = "";
        for (Object manager : snapshot) {
            String name = "";
            try {
                if (locationActive() == null) break;
                name = nameOf(manager);
                if (!INJECT_PROVIDERS.contains(name)) continue;
                Object provider = XposedHelpers.getObjectField(manager, "mProvider");
                Object lock = ownerLock(provider);
                Object result = wrap(FakeLocation.build(c, name));
                synchronized (lock) {
                    INJECTING.set(true);
                    try {
                        XposedHelpers.callMethod(manager, "onReportLocation", result);
                    } finally {
                        INJECTING.set(false);
                    }
                }
                pumpSuccess.computeIfAbsent(name, key -> new AtomicLong()).incrementAndGet();
                cycleSuccess++;
            } catch (Throwable e) {
                if (INJECT_PROVIDERS.contains(name)) pumpFailure.computeIfAbsent(name, key -> new AtomicLong()).incrementAndGet();
                cycleFailure++;
                cycleError = name + ": " + e.getClass().getSimpleName();
                XposedBridge.log("Geoshift inject: " + e);
            }
        }
        pumpLastError = cycleError;
        pumpReport(snapshot.size(), cycleSuccess, cycleFailure);
        int interval = c.optJSONObject("settings") == null ? 1000 : c.optJSONObject("settings").optInt("interval", 1000);
        schedule(Math.max(200, Math.min(5000, interval)));
    }

    private static synchronized void pumpReport(int count, int cycleSuccess, int cycleFailure) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPumpReportAt < 3000) return;
        lastPumpReportAt = now;
        StringBuilder detail = new StringBuilder("已发现提供器=").append(count);
        for (String name : INJECT_PROVIDERS) {
            AtomicLong ok = pumpSuccess.get(name), fail = pumpFailure.get(name);
            long a = ok == null ? 0 : ok.get(), b = fail == null ? 0 : fail.get();
            detail.append(' ').append(name).append('=').append(a).append('/').append(b);
        }
        detail.append("（成功/失败）；非应用接收确认");
        try {
            reports.put("location_pump", new JSONObject().put("ok", cycleFailure == 0).put("time", System.currentTimeMillis())
                    .put("stage", cycleFailure > 0 ? "error" : cycleSuccess > 0 ? "injected" : "waiting")
                    .put("detail", detail.toString()).put("error", pumpLastError)
                    .put("package", "android").put("process", "system_server"));
        } catch (Exception ignored) {}
    }

    private static Object ownerLock(Object provider) throws Exception {
        for (String name : new String[]{"mOwnerLock", "mLock"}) {
            Field f = XposedHelpers.findFieldIfExists(provider.getClass(), name);
            if (f != null) {
                f.setAccessible(true);
                Object lock = f.get(provider);
                if (lock != null) return lock;
            }
        }
        throw new IllegalStateException("provider lock not found");
    }
}

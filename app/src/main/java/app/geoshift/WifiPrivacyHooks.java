package app.geoshift;

import android.os.Binder;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Rewrites Wi-Fi replies to ordinary callers while simulating: scans show only the Wi-Fi preset (or
 * nothing), the connection reports the preset or a hidden BSSID. Covers both WifiManager and the
 * Wi-Fi details apps read from ConnectivityManager network capabilities. Hardware is untouched.
 */
final class WifiPrivacyHooks {
    interface Controller {
        /** Config when this app uid should see simulated Wi-Fi, else null. */
        org.json.JSONObject activeFor(int uid);
        void report(Throwable error, String stage, String detail);
    }
    private static final String IMPLEMENTATION = "com.android.server.wifi.WifiServiceImpl";
    // Android 14+ repackages ConnectivityService inside the Tethering module.
    private static final String[] CONNECTIVITY = {
            "android.net.connectivity.com.android.server.ConnectivityService", "com.android.server.ConnectivityService"};
    private static final String CONNECTIVITY_INITIALIZER = "com.android.server.ConnectivityServiceInitializer";
    private static final String HIDDEN_BSSID = "02:00:00:00:00:00";
    private final Controller controller;
    private final Set<Class<?>> installed = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean wifiInstalled;
    private Set<XC_MethodHook.Unhook> classLoading = Collections.emptySet();

    WifiPrivacyHooks(Controller controller) { this.controller = controller; }

    void install(ClassLoader loader) {
        // Watching every class load costs system_server time, so this stays only until Wi-Fi is found.
        classLoading = XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                if (!h.hasThrowable() && h.args.length > 0 && IMPLEMENTATION.equals(h.args[0]) && h.getResult() instanceof Class<?>)
                    installClass((Class<?>) h.getResult());
            }
        });
        Class<?> manager = XposedHelpers.findClassIfExists("com.android.server.SystemServiceManager", loader);
        if (manager != null) XposedBridge.hookAllMethods(manager, "startService", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                if (h.args.length == 0 || !(h.args[0] instanceof Class<?>)) return;
                Class<?> service = (Class<?>) h.args[0];
                String name = service.getName();
                if (name.startsWith("com.android.server.wifi.") || CONNECTIVITY_INITIALIZER.equals(name)) discover(service.getClassLoader());
            }
        });
        discover(loader);
        if (!wifiInstalled) controller.report(null, "waiting", "等待系统 Wi-Fi 服务加载；尚未确认保护入口");
    }

    private void discover(ClassLoader loader) {
        Class<?> type = XposedHelpers.findClassIfExists(IMPLEMENTATION, loader);
        if (type != null) installClass(type);
        for (String name : CONNECTIVITY) {
            Class<?> connectivity = XposedHelpers.findClassIfExists(name, loader);
            if (connectivity != null) installConnectivity(connectivity);
        }
    }

    private synchronized void stopWatchingClassLoads() {
        for (XC_MethodHook.Unhook u : classLoading) u.unhook();
        classLoading = Collections.emptySet();
    }

    /** Applies the simulated identity to a WifiInfo copy (preset, or the real SSID with a hidden BSSID). */
    private static void disguise(Object info, org.json.JSONObject c) {
        org.json.JSONObject preset = FakeEnvironment.wifiPreset(c);
        if (preset == null) {
            XposedHelpers.callMethod(info, "setBSSID", HIDDEN_BSSID);
            return;
        }
        XposedHelpers.callMethod(info, "setBSSID", preset.optString("bssid").toLowerCase(java.util.Locale.ROOT));
        Object ssid = FakeEnvironment.wifiSsid(preset.optString("ssid"));
        if (ssid != null) XposedHelpers.callMethod(info, "setSSID", ssid);
    }

    private void installClass(Class<?> type) {
        if (!installed.add(type)) return;
        int scans = XposedBridge.hookAllMethods(type, "getScanResults", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                org.json.JSONObject c = controller.activeFor(Binder.getCallingUid());
                if (c == null || h.hasThrowable() || h.getResult() == null) return;
                try {
                    Class<?> expected = ((Method) h.method).getReturnType();
                    h.setResult(ReplyFactory.list(expected, FakeEnvironment.scanResults(c)));
                    controller.report(null, "read", FakeEnvironment.wifiPreset(c) == null
                            ? "扫描结果已隐藏真实 Wi-Fi" : "扫描结果已替换为预设 Wi-Fi");
                } catch (Throwable e) { controller.report(e, "error", "扫描回复类型不兼容，未能替换"); }
            }
        }).size();
        XposedBridge.hookAllMethods(type, "getConnectionInfo", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                org.json.JSONObject c = controller.activeFor(Binder.getCallingUid());
                if (c == null || h.hasThrowable() || h.getResult() == null) return;
                try {
                    Object original = h.getResult();
                    Object copy = XposedHelpers.newInstance(original.getClass(), original);
                    disguise(copy, c);
                    h.setResult(copy);
                    controller.report(null, "read", FakeEnvironment.wifiPreset(c) == null ? "连接信息已隐藏 BSSID" : "连接信息已替换为预设 Wi-Fi");
                } catch (Throwable e) { controller.report(e, "error", "连接信息替换失败"); }
            }
        });
        if (scans > 0) {
            wifiInstalled = true;
            stopWatchingClassLoads();
        }
        controller.report(scans == 0 ? new IllegalStateException() : null, scans == 0 ? "error" : "installed",
                scans == 0 ? "未找到系统 Wi-Fi 扫描入口" : "系统 Wi-Fi 伪装入口已安装，等待应用读取");
    }

    /**
     * ConnectivityService decides per app whether network capabilities keep Wi-Fi details. The caller
     * uid is an argument there because callbacks are sent from a service thread, not a Binder call.
     */
    private void installConnectivity(Class<?> type) {
        if (!installed.add(type)) return;
        XposedBridge.hookAllMethods(type, "createWithLocationInfoSanitizedIfNecessaryWhenParceled", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                Object nc = h.getResult();
                if (nc == null || h.hasThrowable()) return;
                int uid = -1;
                for (int i = 0, ints = 0; i < h.args.length; i++) {
                    if (h.args[i] instanceof Integer && ++ints == 2) { uid = (Integer) h.args[i]; break; } // (pid, uid)
                }
                org.json.JSONObject c = controller.activeFor(uid);
                if (c == null) return;
                try {
                    Object info = XposedHelpers.callMethod(nc, "getTransportInfo");
                    if (info == null || !"android.net.wifi.WifiInfo".equals(info.getClass().getName())) return;
                    Object copy = XposedHelpers.newInstance(info.getClass(), info);
                    disguise(copy, c);
                    Object result = XposedHelpers.newInstance(nc.getClass(), nc);
                    XposedHelpers.callMethod(result, "setTransportInfo", copy);
                    h.setResult(result);
                    controller.report(null, "read", "网络连接信息中的 Wi-Fi 已替换");
                } catch (Throwable e) { controller.report(e, "error", "网络连接信息中的 Wi-Fi 替换失败"); }
            }
        });
    }
}

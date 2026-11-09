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
        // The object actually published as "wifi" may be an OEM subclass that overrides the AOSP
        // methods, so its own class chain is hooked as well.
        Class<?> services = XposedHelpers.findClassIfExists("android.os.ServiceManager", loader);
        if (services != null) XposedBridge.hookAllMethods(services, "addService", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                if (h.args.length > 1 && "wifi".equals(h.args[0]) && h.args[1] != null) installPublished(h.args[1].getClass());
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

    private void installPublished(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class && !c.getName().startsWith("android.os."); c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("getScanResults")) { installClass(c); break; }
            }
        }
    }

    private synchronized void stopWatchingClassLoads() {
        for (XC_MethodHook.Unhook u : classLoading) u.unhook();
        classLoading = Collections.emptySet();
    }

    /** Link fields copied to the rebuilt WifiInfo: they keep the connection usable and name no access point. */
    private static final String[][] LINK_FIELDS = {
            {"getNetworkId", "setNetworkId", "int"}, {"getRssi", "setRssi", "int"},
            {"getFrequency", "setFrequency", "int"}, {"getLinkSpeed", "setLinkSpeed", "int"},
            {"getTxLinkSpeedMbps", "setTxLinkSpeedMbps", "int"}, {"getRxLinkSpeedMbps", "setRxLinkSpeedMbps", "int"},
            {"getWifiStandard", "setWifiStandard", "int"}};

    /** An empty WifiInfo with the real connection state only; returned when a rewrite fails. */
    private static Object blank(Object original) {
        Object info = XposedHelpers.newInstance(original.getClass());
        XposedHelpers.callMethod(info, "setSupplicantState", XposedHelpers.callMethod(original, "getSupplicantState"));
        return info;
    }

    /** Build from an empty WifiInfo: copying the real one retains SSID, MLO and Passpoint identities. */
    private static Object disguise(Object original, org.json.JSONObject c) {
        Object info = blank(original);
        // Apps check network id, IP and signal to decide whether Wi-Fi is usable; none of them identify the AP.
        for (String[] f : LINK_FIELDS) {
            try { XposedHelpers.callMethod(info, f[1], XposedHelpers.callMethod(original, f[0])); }
            catch (Throwable unavailable) {}
        }
        try { XposedHelpers.setObjectField(info, "mIpAddress", XposedHelpers.getObjectField(original, "mIpAddress")); }
        catch (Throwable unavailable) {}
        org.json.JSONObject preset = FakeEnvironment.wifiPreset(c);
        if (preset == null) {
            XposedHelpers.callMethod(info, "setBSSID", HIDDEN_BSSID);
            XposedHelpers.callMethod(info, "setSSID", (Object) null);
            return info;
        }
        XposedHelpers.callMethod(info, "setBSSID", preset.optString("bssid").toLowerCase(java.util.Locale.ROOT));
        Object ssid = FakeEnvironment.wifiSsid(preset.optString("ssid"));
        if (ssid == null) throw new IllegalStateException("WifiSsid unavailable");
        XposedHelpers.callMethod(info, "setSSID", ssid);
        XposedHelpers.callMethod(info, "setFrequency", FakeEnvironment.wifiFrequency(preset));
        XposedHelpers.callMethod(info, "setRssi", FakeEnvironment.wifiRssi(preset));
        return info;
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
                } catch (Throwable e) {
                    h.setResult(null); // Never return the original access points after a rewrite failure.
                    try { h.setResult(ReplyFactory.emptyList(((Method) h.method).getReturnType())); } catch (Throwable ignored) {}
                    controller.report(e, "error", "扫描替换失败，已阻止返回真实结果");
                }
            }
        }).size();
        XposedBridge.hookAllMethods(type, "getConnectionInfo", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                org.json.JSONObject c = controller.activeFor(Binder.getCallingUid());
                if (c == null || h.hasThrowable() || h.getResult() == null) return;
                try {
                    h.setResult(disguise(h.getResult(), c));
                    controller.report(null, "read", FakeEnvironment.wifiPreset(c) == null ? "连接身份已隐藏" : "连接信息已替换为预设 Wi-Fi");
                } catch (Throwable e) {
                    // Apps rarely null-check getConnectionInfo(); an empty WifiInfo hides the AP without crashing them.
                    Object fallback = null;
                    try { fallback = blank(h.getResult()); } catch (Throwable ignored) {}
                    h.setResult(fallback);
                    controller.report(e, "error", "连接信息替换失败，已阻止返回真实结果");
                }
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
                    Object result = XposedHelpers.newInstance(nc.getClass(), nc);
                    XposedHelpers.callMethod(result, "setTransportInfo", (Object) null);
                    h.setResult(result);
                    XposedHelpers.callMethod(result, "setTransportInfo", disguise(info, c));
                    controller.report(null, "read", "网络连接信息中的 Wi-Fi 已替换");
                } catch (Throwable e) {
                    if (h.getResult() == nc) h.setResult(null);
                    controller.report(e, "error", "网络连接信息替换失败，已阻止返回真实 Wi-Fi");
                }
            }
        });
    }
}

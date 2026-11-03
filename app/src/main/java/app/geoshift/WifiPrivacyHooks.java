package app.geoshift;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Rewrites Wi-Fi replies to ordinary callers while simulating: scans show only the Wi-Fi preset (or
 * nothing), the connection reports the preset or a hidden BSSID. Hardware and settings are untouched.
 */
final class WifiPrivacyHooks {
    interface Controller {
        /** Config when the current Binder caller should see simulated Wi-Fi, else null. */
        org.json.JSONObject activeForCaller();
        void report(Throwable error, String stage, String detail);
    }
    private static final String IMPLEMENTATION = "com.android.server.wifi.WifiServiceImpl";
    private final Controller controller;
    private final Set<Class<?>> installed = Collections.newSetFromMap(new ConcurrentHashMap<>());

    WifiPrivacyHooks(Controller controller) { this.controller = controller; }

    void install(ClassLoader loader) {
        XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
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
                if (service.getName().startsWith("com.android.server.wifi.")) discover(service.getClassLoader());
            }
        });
        discover(loader);
        if (installed.isEmpty()) controller.report(null, "waiting", "等待系统 Wi-Fi 服务加载；尚未确认保护入口");
    }

    private void discover(ClassLoader loader) {
        Class<?> type = XposedHelpers.findClassIfExists(IMPLEMENTATION, loader);
        if (type != null) installClass(type);
    }

    private void installClass(Class<?> type) {
        if (!installed.add(type)) return;
        int scans = XposedBridge.hookAllMethods(type, "getScanResults", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                org.json.JSONObject c = controller.activeForCaller();
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
                org.json.JSONObject c = controller.activeForCaller();
                if (c == null || h.hasThrowable() || h.getResult() == null) return;
                try {
                    Object original = h.getResult();
                    Object copy = XposedHelpers.newInstance(original.getClass(), original);
                    org.json.JSONObject preset = FakeEnvironment.wifiPreset(c);
                    if (preset == null) {
                        XposedHelpers.callMethod(copy, "setBSSID", "02:00:00:00:00:00");
                    } else {
                        XposedHelpers.callMethod(copy, "setBSSID", preset.optString("bssid").toLowerCase(java.util.Locale.ROOT));
                        Object ssid = FakeEnvironment.wifiSsid(preset.optString("ssid"));
                        if (ssid != null) XposedHelpers.callMethod(copy, "setSSID", ssid);
                    }
                    h.setResult(copy);
                    controller.report(null, "read", preset == null ? "连接信息已隐藏 BSSID" : "连接信息已替换为预设 Wi-Fi");
                } catch (Throwable e) { controller.report(e, "error", "连接信息替换失败"); }
            }
        });
        controller.report(scans == 0 ? new IllegalStateException() : null, scans == 0 ? "error" : "installed",
                scans == 0 ? "未找到系统 Wi-Fi 扫描入口" : "系统 Wi-Fi 伪装入口已安装，等待应用读取");
    }
}

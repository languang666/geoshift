package app.geoshift;

import android.app.Application;
import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed entry. In system_server it installs {@link SystemHook} (location for every app, Android 12+).
 * Existing app-scoped Wi-Fi, cell and GNSS features remain separate from system location injection.
 * Does not alter the physical radio. Hook-generated locations carry no mock flag.
 */
public final class PortalHook implements IXposedHookLoadPackage {
    private static final String SELF = "app.geoshift";
    private static final long CONFIG_TTL_MS = 500;
    private static final long REPORT_INTERVAL_MS = 3000;

    private static volatile Context context;
    private static boolean attachHooked;
    private static JSONObject cached;
    private static volatile boolean systemLocation;
    private static String processName = "";
    private static long readAt;
    private static final Set<String> installed = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Map<String, Long> reports = new ConcurrentHashMap<>();
    private static final Map<String, String> reportStages = new ConcurrentHashMap<>();
    private static final Set<Method> callbackMethods = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if ("android".equals(p.packageName) || "system".equals(p.packageName)) {
            SystemHook.install(p.classLoader);
            return;
        }
        if (SELF.equals(p.packageName)) return;
        processName = p.processName == null ? p.packageName : p.processName;
        // One hook per process: shared processes (com.android.phone) load several packages.
        if (!attachHooked) {
            attachHooked = true;
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam h) {
                    // getApplicationContext() is still null at this point; the Application itself is usable.
                    attached((Context) h.thisObject, p);
                }
            });
        }
        // Persistent processes such as com.android.phone may already have their Application.
        try {
            Object app = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentApplication");
            if (app instanceof Context) attached(((Context) app).getApplicationContext(), p);
        } catch (Throwable ignored) {}
    }

    private static void attached(Context app, XC_LoadPackage.LoadPackageParam p) {
        if (app == null) return;
        context = app;
        if ("com.android.phone".equals(p.packageName) || "com.android.phone".equals(p.processName))
            installPhone(p.classLoader, app.getClassLoader());
        else if (BLUETOOTH_PACKAGES.contains(p.packageName) || BLUETOOTH_PACKAGES.contains(p.processName))
            installBluetooth(p.classLoader, app.getClassLoader());
        else install();
    }

    // ---- Configuration ------------------------------------------------------------------------

    /** Returns a short-lived cached copy of the config held by system_server, or null. */
    private static synchronized JSONObject config() {
        Context c = context;
        if (c == null) return null;
        long now = SystemClock.elapsedRealtime();
        if (readAt != 0 && now - readAt < CONFIG_TTL_MS) return cached;
        readAt = now;
        try {
            Bundle b = command(c, "GET", new Bundle());
            String json = b == null ? null : b.getString("config");
            cached = json == null ? null : new JSONObject(json);
            systemLocation = b != null && b.getBoolean("system");
        } catch (Throwable unavailable) {
            cached = null;
        }
        return cached;
    }

    /** Sends a command to the system-framework hook; the bundle comes back filled in. */
    private static Bundle command(Context c, String name, Bundle extras) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        lm.sendExtraCommand(SystemHook.COMMAND_PROVIDER, name, extras);
        return extras;
    }

    /** Returns the config when the feature is active for LSPosed, otherwise null. */
    private static JSONObject active(String feature) {
        JSONObject c = config();
        if (c == null) return null;
        JSONObject s = c.optJSONObject("settings"), r = c.optJSONObject("runtime");
        if (s == null || r == null || !s.optBoolean("privacyAccepted") || !"lsposed".equals(s.optString("backend")))
            return null;
        boolean on;
        switch (feature) {
            case "location": on = r.optBoolean("enabled"); break;
            case "environment": on = r.optBoolean("enabled") && FakeEnvironment.spoofEnvironment(s); break;
            case "gnss": on = s.optBoolean("gnssEnabled"); break;
            default: on = r.optBoolean(feature + "Enabled");
        }
        return on ? c : null;
    }

    private static void report(String feature, Throwable error) {
        report(feature, error, "read", "");
    }

    private static void report(String feature, Throwable error, String stage, String detail) {
        Context c = context;
        if (c == null) return;
        long now = SystemClock.elapsedRealtime();
        Long last = reports.get(feature);
        if (last != null && now - last < REPORT_INTERVAL_MS && stage.equals(reportStages.get(feature))) return;
        reports.put(feature, now);
        reportStages.put(feature, stage);
        try {
            Bundle b = new Bundle();
            b.putString("feature", feature);
            b.putBoolean("ok", error == null);
            b.putString("error", error == null ? "" : error.getClass().getSimpleName());
            b.putString("stage", stage);
            b.putString("detail", detail);
            b.putString("process", processName);
            command(c, "REPORT", b);
        } catch (Throwable ignored) {}
    }

    // ---- Simulated values ---------------------------------------------------------------------

    private static Location location(JSONObject config, Object original) throws Exception {
        String provider = original instanceof Location ? ((Location) original).getProvider() : "fused";
        return FakeLocation.build(config, provider == null ? "fused" : provider);
    }

    private static List<Object> cells(JSONObject config) throws Throwable { return FakeEnvironment.cells(config); }

    @androidx.annotation.RequiresApi(30)
    private static GnssStatus gnss(JSONObject config) { return FakeEnvironment.gnss(config); }

    // ---- Hook installation --------------------------------------------------------------------

    /** The Bluetooth stack: AOSP / most ROMs, and the Google build on Pixel and the emulator. */
    private static final java.util.Set<String> BLUETOOTH_PACKAGES =
            new java.util.HashSet<>(java.util.Arrays.asList("com.android.bluetooth", "com.google.android.bluetooth"));
    private static final java.util.concurrent.atomic.AtomicLong bleDrops = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Bluetooth process (LSPosed scope "蓝牙"): while the environment is simulated, apps receive no
     * Bluetooth LE scan results, because location SDKs look nearby beacons up to find the real
     * position. Connected devices keep working; classic discovery (pairing in Settings) is untouched.
     */
    private static void installBluetooth(ClassLoader... loaders) {
        if (!installed.add("__bluetooth_hooks__")) return;
        XC_MethodHook drop = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                if (active("environment") == null) return;
                h.setResult(null);
                long n = bleDrops.incrementAndGet();
                report("bluetooth", null, "read", "已拦截蓝牙扫描结果 " + n + " 次");
            }
        };
        int hooks = 0;
        Set<Class<?>> seen = new java.util.HashSet<>();
        // Callback-based scans: the AIDL proxy each result is sent through to the scanning app.
        for (ClassLoader loader : loaders) {
            Class<?> proxy = XposedHelpers.findClassIfExists("android.bluetooth.le.IScannerCallback$Stub$Proxy", loader);
            if (proxy == null || !seen.add(proxy)) continue;
            for (String name : new String[]{"onScanResult", "onBatchScanResults", "onFoundOrLost"})
                hooks += XposedBridge.hookAllMethods(proxy, name, drop).size();
        }
        // PendingIntent-based scans. The class holding this method moved between Android versions.
        for (String className : new String[]{"com.android.bluetooth.le_scan.TransitionalScanHelper",
                "com.android.bluetooth.le_scan.ScanController", "com.android.bluetooth.gatt.GattService"}) {
            for (ClassLoader loader : loaders) {
                Class<?> type = XposedHelpers.findClassIfExists(className, loader);
                if (type == null || !seen.add(type)) continue;
                hooks += XposedBridge.hookAllMethods(type, "sendResultsByPendingIntent", drop).size();
            }
        }
        XposedBridge.log("Geoshift bluetooth scan hooks: " + hooks);
        if (hooks == 0) installed.remove("__bluetooth_hooks__");
        report("bluetooth", hooks == 0 ? new IllegalStateException("IScannerCallback") : null,
                hooks == 0 ? "error" : "installed", hooks == 0 ? "蓝牙进程未找到扫描结果入口" : "蓝牙扫描伪装入口已安装（" + hooks + " 处）");
    }

    /** Config while the phone process should answer apps with simulated cells (or none), else null. */
    private static JSONObject phoneCells() {
        JSONObject c = active("cell");
        return c != null ? c : active("environment");
    }

    /**
     * Phone process (LSPosed scope "电话服务"): answers direct cell queries from ordinary apps with the
     * simulated cells, or an empty result, so no target-app scope is needed.
     */
    private static void installPhone(ClassLoader... loaders) {
        if (!installed.add("__phone_hooks__")) return;
        int hooks = 0;
        for (String className : new String[]{"com.android.phone.PhoneInterfaceManager", "com.android.phone.HwPhoneInterfaceManager"}) {
            for (ClassLoader loader : loaders) hooks += hookPhoneClass(XposedHelpers.findClassIfExists(className, loader));
        }
        // OEM ROMs may publish a subclass of PhoneInterfaceManager that overrides these queries, so the
        // class of the object actually registered as "phone" is hooked too, now or when it registers.
        try {
            Class<?> services = Class.forName("android.os.ServiceManager");
            Object published = XposedHelpers.callStaticMethod(services, "checkService", "phone");
            if (published != null) hooks += hookPhoneChain(published.getClass());
            XposedBridge.hookAllMethods(services, "addService", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam h) {
                    if (h.args.length > 1 && "phone".equals(h.args[0]) && h.args[1] != null) {
                        int n = hookPhoneChain(h.args[1].getClass());
                        if (n > 0) report("cell", null, "installed", "电话服务基站伪装入口已安装（注册对象 " + n + " 处）");
                    }
                }
            });
        } catch (Throwable e) { XposedBridge.log("Geoshift phone service lookup: " + e); }
        XposedBridge.log("Geoshift phone cell hooks: " + hooks);
        if (hooks == 0) installed.remove("__phone_hooks__"); // Let a later Application.attach retry.
        report("cell", hooks == 0 ? new IllegalStateException("PhoneInterfaceManager") : null,
                hooks == 0 ? "error" : "installed",
                hooks == 0 ? "电话服务未找到基站查询入口" : "电话服务基站伪装入口已安装");
    }

    private static final Set<Class<?>> phoneClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Hooks every class from the published phone service up to (not including) the framework stub. */
    private static int hookPhoneChain(Class<?> type) {
        int hooks = 0;
        for (Class<?> c = type; c != null && c != Object.class && !c.getName().startsWith("android.")
                && !c.getName().startsWith("com.android.internal."); c = c.getSuperclass())
            hooks += hookPhoneClass(c);
        return hooks;
    }

    private static int hookPhoneClass(Class<?> type) {
        if (type == null || !phoneClasses.add(type)) return 0;
        int hooks = 0;
        XC_MethodHook query = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                if (Binder.getCallingUid() < 10000 || h.hasThrowable()) return;
                JSONObject c = phoneCells();
                if (c == null) return;
                try {
                    Class<?> result = ((Method) h.method).getReturnType();
                    String name = h.method.getName();
                    if (List.class.isAssignableFrom(result))
                        h.setResult("getAllCellInfo".equals(name) ? FakeEnvironment.cellsOrEmpty(c) : new ArrayList<>());
                    else if (result == Bundle.class) h.setResult(new Bundle());
                    else if ("android.telephony.ServiceState".equals(result.getName())) {
                        if (h.getResult() == null) return;
                        h.setResult(FakeEnvironment.serviceState(h.getResult(), c));
                    }
                    else if ("getCellLocation".equals(name)) h.setResult(FakeEnvironment.cellIdentityOrEmpty(c));
                    else return;
                    report("cell", null, "read", "电话服务已替换基站查询：" + name);
                } catch (Throwable e) { report("cell", e, "error", "电话服务基站查询替换失败"); }
            }
        };
        for (String name : new String[]{"getAllCellInfo", "getCellLocation", "getNeighboringCellInfo",
                "getServiceStateForSlot", "getServiceStateForSubscriber"})
            hooks += XposedBridge.hookAllMethods(type, name, query).size();
        XC_MethodHook update = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                if (Binder.getCallingUid() < 10000) return;
                JSONObject c = phoneCells();
                if (c == null) return;
                for (Object a : h.args) {
                    if (a == null || !implementsInterface(a.getClass(), "android.telephony.ICellInfoCallback")) continue;
                    try {
                        XposedHelpers.callMethod(a, "onCellInfo", FakeEnvironment.cellsOrEmpty(c));
                        h.setResult(null); // Answered; skip the real modem request.
                        report("cell", null, "read", "电话服务已替换基站刷新回调");
                    } catch (Throwable e) { report("cell", e, "error", "基站刷新回调替换失败"); }
                    return;
                }
            }
        };
        for (String name : new String[]{"requestCellInfoUpdate", "requestCellInfoUpdateWithWorkSource"})
            hooks += XposedBridge.hookAllMethods(type, name, update).size();
        return hooks;
    }

    private static boolean implementsInterface(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            for (Class<?> i : c.getInterfaces()) if (i.getName().equals(name)) return true;
        return false;
    }

    /** Hooks a callback method once per concrete listener class and replaces its first argument. */
    private static void callbacks(Object object, String method, String feature) {
        if (object == null) return;
        Class<?> type = object.getClass();
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                if (h.args.length == 0) return;
                JSONObject c = active(feature);
                if (c == null && "cell".equals(feature) && h.args[0] instanceof List && active("environment") != null) {
                    h.args[0] = Collections.emptyList();
                    return;
                }
                if (c == null) return;
                try {
                    Object first = h.args[0];
                    if ("location".equals(feature)) {
                        if (first instanceof Location) h.args[0] = location(c, first);
                        else if (first instanceof List) h.args[0] = Collections.singletonList(location(c,
                                ((List<?>) first).isEmpty() ? null : ((List<?>) first).get(0)));
                        else return;
                    } else if ("cell".equals(feature) && first instanceof List) {
                        h.args[0] = cells(c);
                    } else if ("gnss".equals(feature) && Build.VERSION.SDK_INT >= 30 && first instanceof GnssStatus) {
                        h.args[0] = gnss(c);
                    } else {
                        return;
                    }
                    report(feature, null);
                } catch (Throwable e) {
                    report(feature, e);
                }
            }
        };
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method candidate : c.getDeclaredMethods()) {
                if (candidate.getName().equals(method) && !Modifier.isAbstract(candidate.getModifiers()) && callbackMethods.add(candidate))
                    XposedBridge.hookMethod(candidate, hook);
            }
        }
    }

    private static void install() {
        if (!installed.add("__portal_hooks__")) return;

        XC_MethodHook lastLocation = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                JSONObject c = active("location");
                if (c == null || h.hasThrowable()) return;
                if (!(h.method instanceof Method) || ((Method) h.method).getReturnType() != Location.class) return;
                try {
                    h.setResult(location(c, h.getResult()));
                    report("location", null);
                } catch (Throwable e) {
                    report("location", e);
                }
            }
        };
        for (String name : new String[]{"getLastKnownLocation", "getLastLocation"})
            XposedBridge.hookAllMethods(LocationManager.class, name, lastLocation);

        if (Build.VERSION.SDK_INT >= 30) {
            XposedBridge.hookAllMethods(LocationManager.class, "getCurrentLocation", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam h) {
                    for (int i = 0; i < h.args.length; i++) {
                        if (!(h.args[i] instanceof java.util.function.Consumer)) continue;
                        @SuppressWarnings("unchecked")
                        java.util.function.Consumer<Object> callback = (java.util.function.Consumer<Object>) h.args[i];
                        h.args[i] = (java.util.function.Consumer<Object>) value -> {
                            Object delivered = value;
                            try {
                                JSONObject c = active("location");
                                if (c != null && value instanceof Location) {
                                    delivered = location(c, value);
                                    report("location", null);
                                }
                            } catch (Throwable e) { report("location", e); }
                            callback.accept(delivered);
                        };
                        break;
                    }
                }
            });
        }

        XC_MethodHook locationRegistration = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                for (Object a : h.args) {
                    if (a instanceof LocationListener) callbacks(a, "onLocationChanged", "location");
                    if (a instanceof GnssStatus.Callback) callbacks(a, "onSatelliteStatusChanged", "gnss");
                }
            }
        };
        for (String name : new String[]{"requestLocationUpdates", "requestSingleUpdate", "registerGnssStatusCallback"})
            XposedBridge.hookAllMethods(LocationManager.class, name, locationRegistration);

        XC_MethodHook wifi = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                JSONObject c = active("wifi");
                if (c == null) return;
                try {
                    JSONObject w = c.getJSONObject("runtime").getJSONObject("wifi");
                    h.setResult("getSSID".equals(h.method.getName())
                            ? "\"" + w.getString("ssid") + "\"" : w.getString("bssid"));
                    report("wifi", null);
                } catch (Throwable e) {
                    report("wifi", e);
                }
            }
        };
        for (String name : new String[]{"getSSID", "getBSSID"})
            XposedBridge.hookAllMethods(WifiInfo.class, name, wifi);

        XposedBridge.hookAllMethods(WifiInfo.class, "getBSSID", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                if (!h.hasThrowable() && active("environment") != null) h.setResult("02:00:00:00:00:00");
            }
        });
        XposedBridge.hookAllMethods(android.net.wifi.WifiManager.class, "getScanResults", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                if (!h.hasThrowable() && active("environment") != null) h.setResult(Collections.emptyList());
            }
        });

        if (Build.VERSION.SDK_INT >= 31) {
            XposedBridge.hookAllMethods(TelephonyManager.class, "registerTelephonyCallback", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam h) {
                    for (Object a : h.args) if (a instanceof TelephonyCallback) callbacks(a, "onCellInfoChanged", "cell");
                }
            });
        }

        XposedBridge.hookAllMethods(TelephonyManager.class, "getAllCellInfo", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                JSONObject c = active("cell");
                if (c == null) {
                    if (!h.hasThrowable() && active("environment") != null) h.setResult(Collections.emptyList());
                    return;
                }
                try {
                    h.setResult(cells(c));
                    report("cell", null);
                } catch (Throwable e) {
                    report("cell", e);
                }
            }
        });

        XC_MethodHook cellRegistration = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                for (Object a : h.args) {
                    if (a instanceof PhoneStateListener) callbacks(a, "onCellInfoChanged", "cell");
                    if (Build.VERSION.SDK_INT >= 29 && a instanceof TelephonyManager.CellInfoCallback)
                        callbacks(a, "onCellInfo", "cell");
                }
            }
        };
        for (String name : new String[]{"requestCellInfoUpdate", "listen"})
            XposedBridge.hookAllMethods(TelephonyManager.class, name, cellRegistration);
    }
}

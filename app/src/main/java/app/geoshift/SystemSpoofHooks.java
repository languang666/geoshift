package app.geoshift;

import android.location.Location;
import android.os.Build;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs in system_server. Rewrites what leaves the system for other processes, at the Binder proxy
 * of each client callback, so ordinary apps need no LSPosed scope of their own:
 *   location listeners / getCurrentLocation callbacks -> simulated location
 *   GNSS status -> simulated satellites; NMEA sentences -> simulated sentences of the same type
 *   raw GNSS measurements / navigation messages (enough to solve the real fix) -> dropped
 *   Xiaomi HyperOS blurred-location service -> simulated location / simulated cells, or none
 *   cell info / cell location / service state listeners -> simulated cells, or none
 * Wi-Fi replies are handled by {@link WifiPrivacyHooks}; direct cell queries by the phone process.
 * In "selected" mode only callbacks owned by a chosen app (see CallbackOwners) are rewritten; other
 * apps keep real data, and a simulated fix the provider cache may still hold is swapped for the real one.
 */
final class SystemSpoofHooks {
    interface Controller {
        /** Config while location simulation runs in LSPosed mode, else null. */
        JSONObject location();
        /** Config while cell data should be replaced, else null. */
        JSONObject cells();
        /** Config while satellites should be replaced, else null. */
        JSONObject gnss();
        /** Whether the simulation applies to an app uid (always true unless "selected" mode). */
        boolean covers(JSONObject config, int uid);
        /** Last real fix of a provider, or null. */
        Location realFix(String provider);
        void report(String stage, String detail);
    }

    private final Controller controller;
    private final AtomicLong locationHits = new AtomicLong();
    private final AtomicLong gnssHits = new AtomicLong();
    private final AtomicLong nmeaDrops = new AtomicLong();
    private final AtomicLong rawDrops = new AtomicLong();
    private final AtomicLong miuiHits = new AtomicLong();
    private final AtomicLong cellHits = new AtomicLong();
    private final AtomicLong unknownOwners = new AtomicLong();
    private final List<String> installed = new ArrayList<>();
    private final List<String> missing = new ArrayList<>();

    SystemSpoofHooks(Controller controller) { this.controller = controller; }

    /** Config if the simulation applies to the app owning this callback proxy, else null. */
    private JSONObject forOwner(JSONObject c, Object proxy) {
        if (c == null) return null;
        if (!AppScope.selectedOnly(c)) return c;
        int uid = CallbackOwners.ownerOf(proxy);
        if (uid < 0) {
            long count = unknownOwners.incrementAndGet();
            if (count == 1 || (count & (count - 1)) == 0)
                controller.report("error", "回调归属未知 " + count + " 次：" + proxy.getClass().getName() + "；无法确认选中应用的保护范围");
        }
        return controller.covers(c, uid) ? c : null;
    }

    /** For an app the simulation does not cover: a simulated fix becomes the real one (or null). */
    private Location realFor(JSONObject c, Object item) {
        if (!(item instanceof Location)) return null;
        Location l = (Location) item;
        return SystemHook.nearSimulated(c, l) ? controller.realFix(l.getProvider()) : l;
    }

    void install(ClassLoader loader) {
        hook(loader, "android.location.ILocationListener$Stub$Proxy", "onLocationChanged", "定位回调", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                JSONObject all = controller.location();
                if (all == null || h.args.length == 0 || !(h.args[0] instanceof List)) return;
                List<?> original = (List<?>) h.args[0];
                JSONObject c = forOwner(all, h.thisObject);
                if (c == null) {
                    List<Location> real = new ArrayList<>();
                    for (Object item : original) { Location l = realFor(all, item); if (l != null) real.add(l); }
                    if (real.isEmpty()) h.setResult(null);
                    else h.args[0] = real;
                    return;
                }
                List<Location> replaced = new ArrayList<>(Math.max(1, original.size()));
                try {
                    for (Object item : original) replaced.add(fake(c, item));
                    if (replaced.isEmpty()) replaced.add(fake(c, null));
                } catch (Throwable e) { dropFailed(h, "定位回调", e); return; }
                h.args[0] = replaced;
                hit(locationHits);
            }
        });
        hook(loader, "android.location.ILocationCallback$Stub$Proxy", "onLocation", "单次定位回调", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                JSONObject all = controller.location();
                if (all == null || h.args.length == 0) return;
                JSONObject c = forOwner(all, h.thisObject);
                if (c == null) {
                    h.args[0] = realFor(all, h.args[0]);
                    return;
                }
                try { h.args[0] = fake(c, h.args[0]); }
                catch (Throwable e) { dropFailed(h, "单次定位回调", e); return; }
                hit(locationHits);
            }
        });
        if (Build.VERSION.SDK_INT >= 30) {
            hook(loader, "android.location.IGnssStatusListener$Stub$Proxy", "onSvStatusChanged", "卫星状态", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam h) {
                    JSONObject c = forOwner(controller.gnss(), h.thisObject);
                    if (c == null || h.args.length == 0) return;
                    try { h.args[0] = FakeEnvironment.gnss(c); }
                    catch (Throwable e) { dropFailed(h, "卫星状态", e); return; }
                    hit(gnssHits);
                }
            });
        }
        hook(loader, "android.location.IGnssNmeaListener$Stub$Proxy", "onNmeaReceived", "NMEA", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                JSONObject c = forOwner(controller.location(), h.thisObject);
                if (c == null) return;
                // NMEA carries the real position: swap each sentence for the simulated one.
                int text = -1;
                for (int i = 0; i < h.args.length; i++) if (h.args[i] instanceof String) text = i;
                java.util.List<String> fake = java.util.Collections.emptyList();
                try { if (text >= 0) fake = FakeNmea.replaceAll(c, (String) h.args[text]); } catch (Throwable ignored) {}
                if (fake.isEmpty()) h.setResult(null);
                else {
                    h.args[text] = fake.get(0);
                    if (fake.size() > 1) h.setObjectExtra("more", fake.subList(1, fake.size()));
                }
                hit(nmeaDrops);
            }
            @Override protected void afterHookedMethod(MethodHookParam h) {
                // Simulated GSV parts the real chip never sent, one callback each like real sentences.
                Object more = h.getObjectExtra("more");
                if (!(more instanceof java.util.List)) return;
                int text = -1;
                for (int i = 0; i < h.args.length; i++) if (h.args[i] instanceof String) text = i;
                for (Object sentence : (java.util.List<?>) more) {
                    Object[] args = h.args.clone();
                    args[text] = sentence;
                    try { XposedBridge.invokeOriginalMethod(h.method, h.thisObject, args); } catch (Throwable e) { return; }
                }
            }
        });
        // Raw pseudoranges and navigation data let an app solve the real position itself and would
        // contradict the simulated satellites, so apps receive none while simulating.
        XC_MethodHook dropRaw = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                if (forOwner(controller.location(), h.thisObject) == null) return;
                h.setResult(null);
                hit(rawDrops);
            }
        };
        hook(loader, "android.location.IGnssMeasurementsListener$Stub$Proxy", "onGnssMeasurementsReceived", "GNSS 原始测量", dropRaw);
        hook(loader, "android.location.IGnssNavigationMessageListener$Stub$Proxy", "onGnssNavigationMessageReceived", "GNSS 导航电文", dropRaw);
        hookMiuiBlur(loader);
        hook(loader, "com.android.internal.telephony.IPhoneStateListener$Stub$Proxy", "onCellInfoChanged", "基站列表回调", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                JSONObject c = forOwner(controller.cells(), h.thisObject);
                if (c == null || h.args.length == 0) return;
                try {
                    h.args[0] = FakeEnvironment.cellsOrEmpty(c);
                    hit(cellHits);
                } catch (Throwable e) { dropFailed(h, "基站列表回调", e); }
            }
        });
        hook(loader, "com.android.internal.telephony.IPhoneStateListener$Stub$Proxy", "onCellLocationChanged", "基站位置回调", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                JSONObject c = forOwner(controller.cells(), h.thisObject);
                if (c == null || h.args.length == 0) return;
                try {
                    h.args[0] = FakeEnvironment.cellIdentityOrEmpty(c);
                    hit(cellHits);
                } catch (Throwable e) { dropFailed(h, "基站位置回调", e); }
            }
        });
        hook(loader, "com.android.internal.telephony.IPhoneStateListener$Stub$Proxy", "onServiceStateChanged", "网络状态回调", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                JSONObject c = forOwner(controller.cells(), h.thisObject);
                if (c == null || h.args.length == 0 || h.args[0] == null) return;
                try {
                    h.args[0] = FakeEnvironment.serviceState(h.args[0], c);
                    hit(cellHits);
                } catch (Throwable e) { dropFailed(h, "网络状态回调", e); }
            }
        });
        hook(loader, "com.android.internal.telephony.IPhoneStateListener$Stub$Proxy", "onBarringInfoChanged", "接入限制回调", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                if (forOwner(controller.cells(), h.thisObject) == null || h.args.length == 0 || h.args[0] == null) return;
                try {
                    h.args[0] = XposedHelpers.callMethod(h.args[0], "createLocationInfoSanitizedCopy");
                    hit(cellHits);
                } catch (Throwable e) { dropFailed(h, "接入限制回调", e); }
            }
        });
        hook(loader, "com.android.internal.telephony.IPhoneStateListener$Stub$Proxy", "onRegistrationFailed", "注册失败回调", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                JSONObject c = forOwner(controller.cells(), h.thisObject);
                if (c == null || h.args.length == 0) return;
                try {
                    h.args[0] = FakeEnvironment.cellIdentityOrEmpty(c);
                    hit(cellHits);
                } catch (Throwable e) { dropFailed(h, "注册失败回调", e); }
            }
        });
        controller.report(missing.isEmpty() ? "installed" : "error",
                "已安装：" + String.join("、", installed) + (missing.isEmpty() ? "" : "；缺失：" + String.join("、", missing)));
    }

    /**
     * Xiaomi HyperOS / MIUI keeps its own location service in system_server for the "approximate
     * location" feature; it answers some apps from the real fix and real towers on its own path.
     * Only present on Xiaomi ROMs, so its absence is not reported as missing.
     */
    /** In "selected" mode, the config only when a uid / package argument names a chosen app. */
    private JSONObject miuiTarget(JSONObject c, Object[] args) {
        if (c == null || !AppScope.selectedOnly(c)) return c;
        for (Object a : args) {
            if (a instanceof Integer && (Integer) a >= 10000 && controller.covers(c, (Integer) a)) return c;
            if (a instanceof String && AppScope.listed(c, (String) a)) return c;
        }
        return null;
    }

    private void hookMiuiBlur(ClassLoader loader) {
        Class<?> type = null;
        for (String name : new String[]{"com.android.server.location.MiuiBlurLocationManagerImpl",
                "com.android.server.location.MiuiBlurLocationManager"}) {
            type = XposedHelpers.findClassIfExists(name, loader);
            if (type != null) break;
        }
        if (type == null) return;
        hookMethods(type, "getBlurryLocation", "小米模糊定位", false, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                JSONObject c = miuiTarget(controller.location(), h.args);
                if (c == null) return;
                try { h.setResult(fakeLocationLike(c, h.getResult(), ((Method) h.method).getReturnType())); }
                catch (Throwable e) { dropFailed(h, "小米模糊定位", e); return; }
                hit(miuiHits);
            }
        });
        hookMethods(type, "getBlurryCellInfos", "小米模糊基站列表", false, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                JSONObject c = miuiTarget(controller.cells(), h.args);
                if (c == null) return;
                Object value;
                try { value = FakeEnvironment.cellsOrEmpty(c); } catch (Throwable e) { value = new ArrayList<>(); }
                h.setResult(fit(value, ((Method) h.method).getReturnType()));
                hit(miuiHits);
            }
        });
        hookMethods(type, "getBlurryCellLocation", "小米模糊基站位置", false, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam h) {
                JSONObject c = miuiTarget(controller.cells(), h.args);
                if (c == null) return;
                Object value;
                try { value = FakeEnvironment.cellIdentityOrEmpty(c); } catch (Throwable e) { value = null; }
                h.setResult(fit(value, ((Method) h.method).getReturnType()));
                hit(miuiHits);
            }
        });
        hookMethods(type, "handleGpsLocationChangedLocked", "小米 GPS 刷新", false, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam h) {
                JSONObject c = controller.location();
                if (c == null || AppScope.selectedOnly(c)) return; // Global refresh: only when every app is covered.
                h.setResult(defaultValue(((Method) h.method).getReturnType()));
                hit(miuiHits);
            }
        });
    }

    /** Replaces a Location, LocationResult or List of locations with simulated ones of the same shape. */
    private static Object fakeLocationLike(JSONObject c, Object result, Class<?> returnType) {
        if (result instanceof Location) return fake(c, result);
        if (result instanceof List) {
            List<Location> replaced = new ArrayList<>();
            for (Object item : (List<?>) result) replaced.add(fake(c, item));
            if (replaced.isEmpty()) replaced.add(fake(c, null));
            return replaced;
        }
        if (result != null) {
            try {
                List<?> original = (List<?>) XposedHelpers.getObjectField(result, "mLocations");
                List<Location> replaced = new ArrayList<>();
                for (Object item : original) replaced.add(fake(c, item));
                if (replaced.isEmpty()) replaced.add(fake(c, null));
                // Provider caches may share this object with callers outside the selected scope.
                return XposedHelpers.callStaticMethod(result.getClass(), "create", replaced);
            } catch (Throwable error) { throw new IllegalStateException("Unable to build independent location reply", error); }
        }
        return Location.class.isAssignableFrom(returnType) ? fake(c, null) : null;
    }

    /** value if the method can return it, else an empty list or null of the declared type. */
    private static Object fit(Object value, Class<?> returnType) {
        if (value != null && returnType.isInstance(value)) return value;
        if (returnType.isAssignableFrom(ArrayList.class)) return new ArrayList<>();
        return defaultValue(returnType);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    private void dropFailed(XC_MethodHook.MethodHookParam h, String label, Throwable error) {
        h.setResult(defaultValue(((Method) h.method).getReturnType()));
        controller.report("error", label + "替换失败，已阻止返回真实数据：" + error.getClass().getSimpleName());
    }

    private void hook(ClassLoader loader, String className, String method, String label, XC_MethodHook hook) {
        hookMethods(XposedHelpers.findClassIfExists(className, loader), method, label, true, hook);
    }

    private void hookMethods(Class<?> type, String method, String label, boolean required, XC_MethodHook hook) {
        int count = 0;
        if (type != null) {
            for (Method m : type.getDeclaredMethods()) {
                if (m.getName().equals(method)) {
                    try { XposedBridge.hookMethod(m, hook); count++; } catch (Throwable ignored) {}
                }
            }
        }
        if (count > 0) installed.add(label);
        else if (required) missing.add(label);
    }

    private static Location fake(JSONObject config, Object original) {
        try {
            return FakeLocation.replace(config, original);
        } catch (Exception e) {
            // LSPosed logs hook exceptions and then runs the original call.
            throw new IllegalStateException("Geoshift fake location", e);
        }
    }

    private long lastReport;

    private void hit(AtomicLong counter) {
        counter.incrementAndGet();
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (this) {
            if (now - lastReport < 3000) return;
            lastReport = now;
        }
        controller.report("read", "应用回调替换：定位=" + locationHits.get() + " 卫星=" + gnssHits.get()
                + " NMEA 替换=" + nmeaDrops.get() + " 原始测量拦截=" + rawDrops.get() + " 基站=" + cellHits.get()
                + (miuiHits.get() > 0 ? " 小米模糊定位=" + miuiHits.get() : ""));
    }
}

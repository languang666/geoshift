package app.geoshift;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Remembers which app uid registered each callback Binder, so a hook on a callback proxy (where the
 * receiving app is otherwise unknown) can tell whose callback it is. Filled by hooking the service
 * methods apps call to register callbacks, where Binder.getCallingUid() is the app.
 */
final class CallbackOwners {
    private CallbackOwners() {}

    private static final Map<IBinder, Integer> owners = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Class<?>, Field> remoteFields = new ConcurrentHashMap<>();

    interface Listener { void registered(Method method, int uid); }

    /** Hooks every method of a service class that takes a callback interface. Returns the count. */
    static int watch(Class<?> service, Listener listener) {
        if (service == null) return 0;
        int count = 0;
        for (Method m : service.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) continue;
            boolean takesCallback = false;
            for (Class<?> p : m.getParameterTypes()) if (IInterface.class.isAssignableFrom(p)) { takesCallback = true; break; }
            if (!takesCallback) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam h) {
                        int uid = callerUid(h.args);
                        for (Object a : h.args) if (a instanceof IInterface) record((IInterface) a, uid);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam h) {
                        if (listener != null && !h.hasThrowable()) listener.registered((Method) h.method, callerUid(h.args));
                    }
                });
                count++;
            } catch (Throwable ignored) {}
        }
        return count;
    }

    /** The app behind a call: an AttributionSource argument if present, else the Binder caller. */
    private static int callerUid(Object[] args) {
        for (Object a : args) {
            if (a != null && "android.content.AttributionSource".equals(a.getClass().getName())) {
                try { return (int) a.getClass().getMethod("getUid").invoke(a); } catch (Throwable ignored) {}
            }
        }
        return Binder.getCallingUid();
    }

    static void record(IInterface callback, int uid) {
        try {
            IBinder b = callback.asBinder();
            if (b != null) owners.put(b, uid);
        } catch (Throwable ignored) {}
    }

    /** Owner uid of a callback proxy (an IXxx$Stub$Proxy, or any IInterface), or -1 when unknown. */
    static int ownerOf(Object proxy) {
        if (proxy == null) return -1;
        IBinder binder = null;
        if (proxy instanceof IInterface) {
            try { binder = ((IInterface) proxy).asBinder(); } catch (Throwable ignored) {}
        }
        if (binder == null) {
            try {
                Field f = remoteFields.computeIfAbsent(proxy.getClass(), type -> {
                    try {
                        Field field = type.getDeclaredField("mRemote");
                        field.setAccessible(true);
                        return field;
                    } catch (Throwable e) { return null; }
                });
                if (f != null) binder = (IBinder) f.get(proxy);
            } catch (Throwable ignored) {}
        }
        if (binder == null) return -1;
        Integer uid = owners.get(binder);
        return uid == null ? -1 : uid;
    }
}

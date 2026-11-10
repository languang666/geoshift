package app.geoshift;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * ART inlines short framework getters (Location / WifiInfo / TelephonyManager). When such a method
 * is called from inside the same system process we hook in (system_server, com.android.phone), an
 * inlined caller can run the original body and bypass the hook. {@code deoptimizeMethod} forces those
 * callers onto the interpreter path so the hook always fires.
 *
 * <p>The method exists on LSPosed and Vector but not on classic Xposed, so it is resolved reflectively
 * once; when absent the hooks still work, only without the inline guard. Deoptimizing is one-way for the
 * process lifetime and only affects the few getters we hook, so the cost is negligible.
 */
final class HookUtil {
    private HookUtil() {}

    private static final Method DEOPT = resolve();

    private static Method resolve() {
        try {
            Method m = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable absent) {
            return null; // classic Xposed has no deoptimizeMethod; skip the guard
        }
    }

    /** True when the runtime supports deoptimization (LSPosed / Vector). */
    static boolean available() { return DEOPT != null; }

    /** Deoptimize a single hooked member. Safe to call repeatedly; failures are ignored. */
    static void deoptimize(Member method) {
        if (DEOPT == null || method == null) return;
        try { DEOPT.invoke(null, method); } catch (Throwable ignored) {}
    }

    /** Deoptimize every member hooked by a {@code hookAllMethods(...)} call. Pass its return value. */
    static void deoptimize(Set<XC_MethodHook.Unhook> unhooks) {
        if (DEOPT == null || unhooks == null) return;
        for (XC_MethodHook.Unhook u : unhooks) {
            if (u != null) deoptimize(u.getHookedMethod());
        }
    }

    /** Deoptimize the hooked members and return how many were hooked, for callers that need the count. */
    static int deoptimizeN(Set<XC_MethodHook.Unhook> unhooks) {
        deoptimize(unhooks);
        return unhooks == null ? 0 : unhooks.size();
    }
}

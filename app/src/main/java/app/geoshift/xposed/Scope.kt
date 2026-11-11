package app.geoshift.xposed

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.SystemClock
import app.geoshift.core.SimConfig
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Which apps the simulation applies to: every app, or only the chosen packages. System uids (below
 * 10000) are never "chosen". Works in any hooked process with a Context for the package manager.
 */
object AppScope {
    private val uidCache = ConcurrentHashMap<Int, Boolean>()
    @Volatile private var cachedFor: List<String> = emptyList()
    @Volatile private var cacheAt = 0L
    /** Uids are reused after an uninstall, so answers are refreshed now and then. */
    private const val CACHE_MS = 60_000L

    /** Whether the simulation applies to [uid]. An unknown owner (uid < 0) is not chosen. */
    fun covers(c: SimConfig, uid: Int): Boolean = !c.selectedOnly || (uid >= 0 && listed(c, uid))

    fun listed(c: SimConfig, packageName: String?) = packageName != null && packageName in c.packages

    fun listed(c: SimConfig, uid: Int): Boolean {
        if (uid < 10000 || c.packages.isEmpty()) return false
        val now = SystemClock.elapsedRealtime()
        if (cachedFor !== c.packages || now - cacheAt > CACHE_MS) {
            uidCache.clear()
            cachedFor = c.packages
            cacheAt = now
        }
        uidCache[uid]?.let { return it }
        val ctx = Contexts.get() ?: return false // Unknown yet: do not cache.
        val result = try {
            val pm = ctx.packageManager
            val names = pm.getPackagesForUid(uid) ?: pm.getPackagesForUid(uid % 100000)
            names?.any { it in c.packages } == true
        } catch (_: Throwable) {
            false
        }
        uidCache[uid] = result
        return result
    }
}

/**
 * Remembers which app registered each callback Binder, so a hook on a callback proxy (where the
 * receiving app is otherwise unknown) can tell whose callback it is. Filled by hooking the service
 * methods apps call to register callbacks, where the Binder caller is the app.
 */
object CallbackOwners {
    private val owners = Collections.synchronizedMap(WeakHashMap<IBinder, Int>())
    private val remoteFields = ConcurrentHashMap<Class<*>, Field>()

    /** Hooks every method of [service] that takes a callback interface; [registered] runs after each. Returns the count. */
    fun watch(hooks: Hooker, service: Class<*>?, registered: ((Method, Int) -> Unit)? = null): Int {
        if (service == null) return 0
        var count = 0
        for (m in service.declaredMethods) {
            if (Modifier.isAbstract(m.modifiers) || m.parameterTypes.none { IInterface::class.java.isAssignableFrom(it) }) continue
            try {
                hooks.around(m, before = { call ->
                    val uid = callerUid(call.args)
                    for (a in call.args) if (a is IInterface) record(a, uid)
                }, after = { call ->
                    registered?.invoke(m, callerUid(call.args))
                })
                count++
            } catch (e: Throwable) {
                Log.w("owner hook ${m.name}", e)
            }
        }
        return count
    }

    /** The app behind a call: an AttributionSource argument if present, else the Binder caller. */
    private fun callerUid(args: Array<Any?>): Int {
        for (a in args) {
            if (a != null && a.javaClass.name == "android.content.AttributionSource")
                (Reflect.callOrNull(a, "getUid") as? Int)?.let { return it }
        }
        return Binder.getCallingUid()
    }

    fun record(callback: IInterface, uid: Int) {
        try { callback.asBinder()?.let { owners[it] = uid } } catch (_: Throwable) {}
    }

    /** Owner uid of a callback proxy (IXxx$Stub$Proxy or any IInterface), or -1 when unknown. */
    fun ownerOf(proxy: Any?): Int {
        if (proxy == null) return -1
        var binder: IBinder? = null
        if (proxy is IInterface) binder = runCatching { proxy.asBinder() }.getOrNull()
        if (binder == null) {
            val f = remoteFields.getOrPut(proxy.javaClass) {
                Reflect.field(proxy.javaClass, "mRemote") ?: return -1
            }
            binder = runCatching { f.get(proxy) as? IBinder }.getOrNull()
        }
        return binder?.let { owners[it] } ?: -1
    }
}

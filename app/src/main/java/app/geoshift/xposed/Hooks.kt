package app.geoshift.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * One intercepted call. [args] may be replaced in a before-hook; setting [result] in a before-hook
 * skips the original. In an after-hook [result] holds what the original returned and may be replaced.
 */
class Call internal constructor(private val chain: XposedInterface.Chain) {
    val method: Executable get() = chain.executable
    val thisObject: Any? get() = chain.thisObject
    val args: Array<Any?> = chain.args.toTypedArray()
    internal var skipped = false
    var result: Any? = null
        set(value) { field = value; skipped = true }

    val returnType: Class<*> get() = (method as? Method)?.returnType ?: Void.TYPE

    /** Calls the rest of the chain again with other arguments (e.g. to send an extra NMEA sentence). */
    fun proceedWith(other: Array<Any?>): Any? = chain.proceed(other)

    /** The default value of the return type: null, false or zero. Used to drop a call. */
    fun empty(): Any? = defaultValue(returnType)

    /** Skips the original and returns nothing (the return type's default). */
    fun drop() { result = empty() }

    internal fun proceed(): Any? = chain.proceed(args)
}

fun defaultValue(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Byte.TYPE -> 0.toByte()
    Character.TYPE -> 0.toChar()
    else -> null
}

/**
 * Installs hooks through the libxposed interception chain. Hook bodies must not let exceptions out:
 * the framework would then run the original as if unhooked and real data would reach the app. Each
 * entry point catches its own failures and drops the call instead (see [failClosed]).
 */
class Hooker(private val xposed: XposedInterface) {
    /** Runs [block] before the original. */
    fun before(target: Executable, block: (Call) -> Unit) = install(target) { chain ->
        val call = Call(chain)
        block(call)
        if (call.skipped) call.result else call.proceed()
    }

    /** Runs [block] after the original returned normally; it may replace [Call.result]. */
    fun after(target: Executable, block: (Call) -> Unit) = install(target) { chain ->
        val call = Call(chain)
        val value = call.proceed()
        call.result = value
        block(call)
        call.result
    }

    /** Runs [before] and [after] around the original. */
    fun around(target: Executable, before: (Call) -> Unit, after: (Call) -> Unit) = install(target) { chain ->
        val call = Call(chain)
        before(call)
        if (call.skipped) return@install call.result
        call.result = call.proceed()
        after(call)
        call.result
    }

    private fun install(target: Executable, body: (XposedInterface.Chain) -> Any?): XposedInterface.HookHandle =
        xposed.hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain -> body(chain) }

    /** Hooks every concrete method called [name] declared by [type] (not inherited). Returns how many. */
    fun declared(type: Class<*>?, name: String, hook: (Method) -> Unit): Int {
        if (type == null) return 0
        var count = 0
        for (m in type.declaredMethods) {
            if (m.name != name || Modifier.isAbstract(m.modifiers)) continue
            try { hook(m); count++ } catch (e: Throwable) { Log.w("hook ${type.name}.$name", e) }
        }
        return count
    }

    /** Hooks every constructor of [type]. */
    fun constructors(type: Class<*>, hook: (Constructor<*>) -> Unit): Int {
        var count = 0
        for (c in type.declaredConstructors) try { hook(c); count++ } catch (e: Throwable) { Log.w("hook ${type.name}.<init>", e) }
        return count
    }

    /** Asks ART not to inline a hooked framework getter into its callers (which would bypass the hook). */
    fun deoptimize(target: Executable) {
        try { xposed.deoptimize(target) } catch (_: Throwable) {}
    }
}

/**
 * Runs a rewrite; if it throws, drops the call (default return value) and reports [label], so a
 * failed rewrite never lets the original, real data through.
 */
inline fun Call.failClosed(label: String, report: (String) -> Unit, block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        drop()
        report("${label}替换失败，已阻止返回真实数据：${e.javaClass.simpleName}")
        Log.w(label, e)
    }
}

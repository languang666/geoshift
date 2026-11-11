package app.geoshift.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Module log, through the framework (shown in its module log). */
object Log {
    @Volatile var xposed: XposedInterface? = null
    private const val TAG = "Geoshift"

    fun i(message: String) {
        xposed?.log(android.util.Log.INFO, TAG, message) ?: android.util.Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        xposed?.log(android.util.Log.WARN, TAG, message, error) ?: android.util.Log.w(TAG, message, error)
    }
}

/**
 * Reflection on framework internals by name. Every lookup that fails is recorded with the name it
 * looked for, and the diagnostics page lists them, so a renamed or missing method on a given ROM
 * shows up as "缺失入口：LocationResult.asList" instead of a silent exception at run time.
 */
object Reflect {
    /** Missing class / member name -> where it was needed. */
    val missing = ConcurrentHashMap<String, String>()

    private val methods = ConcurrentHashMap<String, Method>()
    private val fields = ConcurrentHashMap<String, Field>()
    private val absent = ConcurrentHashMap.newKeySet<String>()

    fun classOrNull(name: String, loader: ClassLoader?): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: Throwable) {
        null
    }

    /** A class that must exist for [feature]; recorded as missing when it does not. */
    fun requireClass(name: String, loader: ClassLoader?, feature: String): Class<*>? =
        classOrNull(name, loader) ?: null.also { missing[name] = feature }

    fun firstClass(names: List<String>, loader: ClassLoader?, feature: String?): Class<*>? {
        for (n in names) classOrNull(n, loader)?.let { return it }
        if (feature != null) missing[names.joinToString(" / ")] = feature
        return null
    }

    fun field(type: Class<*>, name: String): Field? {
        val key = type.name + "#" + name
        fields[key]?.let { return it }
        if (key in absent) return null
        var c: Class<*>? = type
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                fields[key] = f
                return f
            } catch (_: NoSuchFieldException) {
            }
            c = c.superclass
        }
        absent.add(key)
        return null
    }

    fun get(target: Any, name: String, feature: String = "字段"): Any? {
        val f = field(target.javaClass, name) ?: throw missingMember(target.javaClass, name, feature)
        return f.get(target)
    }

    fun getOrNull(target: Any, name: String): Any? = try {
        field(target.javaClass, name)?.get(target)
    } catch (_: Throwable) {
        null
    }

    fun set(target: Any, name: String, value: Any?, feature: String = "字段") {
        val f = field(target.javaClass, name) ?: throw missingMember(target.javaClass, name, feature)
        f.set(target, value)
    }

    /** Whether [type] or a superclass declares a field [name]. */
    fun hasField(type: Class<*>, name: String) = field(type, name) != null

    /** Calls an instance method by name, choosing the overload that accepts [args]. */
    fun call(target: Any, name: String, vararg args: Any?): Any? =
        invoke(target.javaClass, target, name, args, "方法")

    fun callStatic(type: Class<*>, name: String, vararg args: Any?): Any? = invoke(type, null, name, args, "静态方法")

    /** Like [call], but null instead of an exception when the method is missing or fails. */
    fun callOrNull(target: Any, name: String, vararg args: Any?): Any? = try {
        invoke(target.javaClass, target, name, args, null)
    } catch (_: Throwable) {
        null
    }

    fun newInstance(type: Class<*>, vararg args: Any?): Any {
        for (c in type.declaredConstructors) {
            if (!accepts(c.parameterTypes, args)) continue
            c.isAccessible = true
            return c.newInstance(*args)
        }
        throw missingMember(type, "<init>(${args.size})", "构造")
    }

    private fun invoke(type: Class<*>, target: Any?, name: String, args: Array<out Any?>, feature: String?): Any? {
        val m = find(type, name, args) ?: throw (if (feature != null) missingMember(type, name, feature) else NoSuchMethodException(name))
        return try {
            m.invoke(target, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    fun find(type: Class<*>, name: String, args: Array<out Any?>): Method? {
        val key = buildString {
            append(type.name).append('.').append(name).append('(')
            for (a in args) append(a?.javaClass?.name ?: "null").append(',')
        }
        methods[key]?.let { return it }
        var c: Class<*>? = type
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name || !accepts(m.parameterTypes, args)) continue
                m.isAccessible = true
                methods[key] = m
                return m
            }
            c = c.superclass
        }
        // Interface default methods.
        for (i in type.interfaces) for (m in i.methods) if (m.name == name && accepts(m.parameterTypes, args)) return m.also { methods[key] = it }
        return null
    }

    /** Methods named [name] declared anywhere in [type]'s class chain. */
    fun methodsNamed(type: Class<*>, name: String): List<Method> {
        val out = ArrayList<Method>()
        var c: Class<*>? = type
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) if (m.name == name && !Modifier.isAbstract(m.modifiers)) out.add(m)
            c = c.superclass
        }
        return out
    }

    private fun accepts(params: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (params.size != args.size) return false
        for (i in params.indices) {
            val a = args[i]
            val p = params[i]
            if (a == null) { if (p.isPrimitive) return false; continue }
            if (!box(p).isInstance(a)) return false
        }
        return true
    }

    private fun box(p: Class<*>): Class<*> = if (!p.isPrimitive) p else when (p) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        Integer.TYPE -> Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        Character.TYPE -> Character::class.java
        else -> p
    }

    private fun missingMember(type: Class<*>, name: String, feature: String): NoSuchMethodException {
        val label = type.name.substringAfterLast('.') + "." + name
        missing[label] = feature
        return NoSuchMethodException(label)
    }
}

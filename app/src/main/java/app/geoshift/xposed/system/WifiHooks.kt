package app.geoshift.xposed.system

import android.os.Binder
import app.geoshift.core.SimConfig
import app.geoshift.xposed.AppScope
import app.geoshift.xposed.Diag
import app.geoshift.xposed.Fakes
import app.geoshift.xposed.Hooker
import app.geoshift.xposed.Log
import app.geoshift.xposed.Reflect
import app.geoshift.xposed.Sim
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Rewrites Wi-Fi replies to apps: scans show only the simulated access point (or nothing), the
 * connection names the simulated one or a hidden BSSID. Covers WifiManager and the Wi-Fi details apps
 * read from ConnectivityManager network capabilities. The radio is untouched.
 */
class WifiHooks(private val hooks: Hooker, private val loader: ClassLoader, private val ownUid: () -> Int) {
    private val implementation = "com.android.server.wifi.WifiServiceImpl"
    // Android 14+ repackages ConnectivityService inside the Tethering module.
    private val connectivity = listOf("android.net.connectivity.com.android.server.ConnectivityService", "com.android.server.ConnectivityService")
    private val installed = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())
    @Volatile private var wifiFound = false
    @Volatile private var classLoading: XposedInterface.HookHandle? = null

    private fun report(ok: Boolean, stage: String, detail: String) = Diag.report("wifi", ok, stage, detail)

    /** The config when this app should see simulated Wi-Fi. */
    private fun activeFor(uid: Int): SimConfig? {
        if (uid < 10000 || uid == ownUid()) return null
        val c = Sim.config
        if (!c.wifiOn || !AppScope.covers(c, uid)) return null
        return c
    }

    fun install() {
        // Wi-Fi lives in an APEX loaded after system_server starts; watching class loads stays only until it is found.
        val loadClass = ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java, java.lang.Boolean.TYPE)
        classLoading = hooks.after(loadClass) { call ->
            if (call.args[0] == implementation) (call.result as? Class<*>)?.let { installService(it) }
        }
        Reflect.classOrNull("com.android.server.SystemServiceManager", loader)?.let { manager ->
            hooks.declared(manager, "startService") { m ->
                if (m.parameterTypes.firstOrNull() == Class::class.java) hooks.before(m) { call ->
                    val service = call.args[0] as? Class<*> ?: return@before
                    if (service.name.startsWith("com.android.server.wifi.") || service.name == "com.android.server.ConnectivityServiceInitializer")
                        discover(service.classLoader)
                }
            }
        }
        // The object actually published as "wifi" may be an OEM subclass overriding the AOSP methods.
        Reflect.classOrNull("android.os.ServiceManager", loader)?.let { sm ->
            hooks.declared(sm, "addService") { m ->
                hooks.after(m) { call ->
                    if (call.args.size > 1 && call.args[0] == "wifi" && call.args[1] != null) installPublished(call.args[1]!!.javaClass)
                }
            }
        }
        discover(loader)
        if (!wifiFound) report(true, "waiting", "等待系统 Wi-Fi 服务加载")
    }

    private fun discover(cl: ClassLoader?) {
        Reflect.classOrNull(implementation, cl)?.let { installService(it) }
        for (name in connectivity) Reflect.classOrNull(name, cl)?.let { installConnectivity(it) }
    }

    private fun installPublished(type: Class<*>) {
        var c: Class<*>? = type
        while (c != null && c != Any::class.java && !c.name.startsWith("android.os.")) {
            if (c.declaredMethods.any { it.name == "getScanResults" }) installService(c)
            c = c.superclass
        }
    }

    private fun installService(type: Class<*>) {
        if (!installed.add(type)) return
        val scans = hooks.declared(type, "getScanResults") { m ->
            hooks.after(m) { call ->
                val c = activeFor(Binder.getCallingUid()) ?: return@after
                if (call.result == null) return@after
                try {
                    call.result = listReply(m, Fakes.scanResults(c))
                    report(true, "read", if (c.wifi == null) "扫描结果已隐藏真实 Wi-Fi" else "扫描结果已替换为模拟 Wi-Fi")
                } catch (e: Throwable) {
                    call.result = runCatching { listReply(m, emptyList<Any>()) }.getOrNull()
                    report(false, "error", "扫描替换失败，已阻止返回真实结果：${e.javaClass.simpleName}")
                }
            }
            hooks.deoptimize(m)
        }
        hooks.declared(type, "getConnectionInfo") { m ->
            hooks.after(m) { call ->
                val c = activeFor(Binder.getCallingUid()) ?: return@after
                val original = call.result ?: return@after
                try {
                    call.result = Fakes.disguiseWifiInfo(original, c.wifi)
                    report(true, "read", if (c.wifi == null) "连接身份已隐藏" else "连接信息已替换为模拟 Wi-Fi")
                } catch (e: Throwable) {
                    // Apps rarely null-check getConnectionInfo(); an empty WifiInfo hides the AP without crashing them.
                    call.result = runCatching { Fakes.blankWifiInfo(original) }.getOrNull()
                    report(false, "error", "连接信息替换失败，已阻止返回真实结果：${e.javaClass.simpleName}")
                }
            }
            hooks.deoptimize(m)
        }
        if (scans > 0) {
            wifiFound = true
            classLoading?.let { runCatching { it.unhook() } }
            classLoading = null
            report(true, "installed", "系统 Wi-Fi 入口已安装，等待应用读取")
        } else {
            Reflect.missing["WifiServiceImpl.getScanResults"] = "Wi-Fi"
        }
    }

    /**
     * ConnectivityService decides per app whether network capabilities keep Wi-Fi details. The uid is
     * an argument there because callbacks go out from a service thread, not a Binder call.
     */
    private fun installConnectivity(type: Class<*>) {
        if (!installed.add(type)) return
        val n = hooks.declared(type, "createWithLocationInfoSanitizedIfNecessaryWhenParceled") { m ->
            hooks.after(m) { call ->
                val nc = call.result ?: return@after
                // (…, pid, uid, …): the second int argument is the uid.
                val uid = call.args.filterIsInstance<Int>().getOrNull(1) ?: -1
                val c = activeFor(uid) ?: return@after
                try {
                    val info = Reflect.call(nc, "getTransportInfo") ?: return@after
                    if (info.javaClass.name != "android.net.wifi.WifiInfo") return@after
                    val copy = Reflect.newInstance(nc.javaClass, nc)
                    Reflect.call(copy, "setTransportInfo", null)
                    call.result = copy
                    Reflect.call(copy, "setTransportInfo", Fakes.disguiseWifiInfo(info, c.wifi))
                    report(true, "read", "网络连接信息中的 Wi-Fi 已替换")
                } catch (e: Throwable) {
                    if (call.result === nc) call.result = null
                    report(false, "error", "网络连接信息替换失败，已阻止返回真实 Wi-Fi：${e.javaClass.simpleName}")
                }
            }
        }
        if (n == 0) Log.w("ConnectivityService sanitize entry not found in ${type.name}")
    }

    /** Wraps [items] in the declared reply type: a List, or an Android 15+ slice class with a List constructor. */
    @Suppress("UNCHECKED_CAST")
    private fun listReply(m: Method, items: List<Any>): Any {
        val type = m.returnType
        if (List::class.java.isAssignableFrom(type)) {
            if (type.isInstance(items)) return items
            return (type.getDeclaredConstructor().newInstance() as MutableList<Any>).apply { addAll(items) }
        }
        val ctor = type.getDeclaredConstructor(List::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(items)
    }
}

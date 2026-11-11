package app.geoshift.xposed

import android.app.PendingIntent
import android.os.Binder
import android.os.Bundle
import app.geoshift.core.SimConfig
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Phone process (scope "电话服务"): answers direct cell queries from apps with the simulated cells, or
 * none, so no app scope is needed.
 */
class PhoneSide(private val hooks: Hooker, private val loader: ClassLoader) {
    private val classes = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    private fun cells(): SimConfig? = Sim.config.takeIf { it.cellsOn }
    private fun report(ok: Boolean, stage: String, detail: String) = Diag.report("phone", ok, stage, detail)

    fun install() {
        var n = 0
        for (name in listOf("com.android.phone.PhoneInterfaceManager", "com.android.phone.HwPhoneInterfaceManager"))
            n += hookClass(Reflect.classOrNull(name, loader))
        // OEM ROMs may publish a subclass that overrides these queries: hook the class of the object
        // actually registered as "phone", now or when it registers.
        try {
            val sm = Class.forName("android.os.ServiceManager")
            Reflect.callStatic(sm, "checkService", "phone")?.let { n += hookChain(it.javaClass) }
            hooks.declared(sm, "addService") { m ->
                hooks.after(m) { call ->
                    if (call.args.size > 1 && call.args[0] == "phone" && call.args[1] != null) {
                        val added = hookChain(call.args[1]!!.javaClass)
                        if (added > 0) report(true, "installed", "电话服务基站入口已安装（注册对象 $added 处）")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w("phone service lookup", e)
        }
        Log.i("phone cell hooks: $n")
        report(n > 0, if (n > 0) "installed" else "error", if (n > 0) "电话服务基站入口已安装（$n 处）" else "电话服务未找到基站查询入口")
        reportMissing()
    }

    private fun reportMissing() {
        if (Reflect.missing.isNotEmpty())
            Diag.report("missing_phone", false, "error", Reflect.missing.entries.joinToString("；") { "${it.key}（${it.value}）" })
    }

    /** Hooks every class from the published service up to (not including) the framework stub. */
    private fun hookChain(type: Class<*>): Int {
        var n = 0
        var c: Class<*>? = type
        while (c != null && c != Any::class.java && !c.name.startsWith("android.") && !c.name.startsWith("com.android.internal.")) {
            n += hookClass(c)
            c = c.superclass
        }
        return n
    }

    private fun hookClass(type: Class<*>?): Int {
        if (type == null || !classes.add(type)) return 0
        var n = 0
        for (name in listOf("getAllCellInfo", "getCellLocation", "getNeighboringCellInfo", "getServiceStateForSlot", "getServiceStateForSubscriber"))
            n += hooks.declared(type, name) { m -> hooks.after(m) { call -> answer(m, call) } }
        for (name in listOf("requestCellInfoUpdate", "requestCellInfoUpdateWithWorkSource"))
            n += hooks.declared(type, name) { m -> hooks.before(m, ::refresh) }
        return n
    }

    private fun answer(m: Method, call: Call) {
        val uid = Binder.getCallingUid()
        if (uid < 10000) return
        val c = cells() ?: return
        if (!AppScope.covers(c, uid)) return
        try {
            val type = m.returnType
            when {
                List::class.java.isAssignableFrom(type) -> call.result = if (m.name == "getAllCellInfo") Fakes.cellsOrEmpty(c) else ArrayList<Any>()
                type == Bundle::class.java -> call.result = Bundle()
                type.name == "android.telephony.ServiceState" -> call.result = call.result?.let { Fakes.serviceState(it, c) } ?: return
                m.name == "getCellLocation" -> call.result = Fakes.cellIdentityOrEmpty(c)
                else -> return
            }
            report(true, "read", "电话服务已替换基站查询：${m.name}")
        } catch (e: Throwable) {
            call.result = null
            report(false, "error", "电话服务基站查询替换失败，已阻止返回真实数据：${e.javaClass.simpleName}")
        }
    }

    /** An app asks the modem for fresh cells: answer its callback with the simulated ones, skip the modem. */
    private fun refresh(call: Call) {
        val uid = Binder.getCallingUid()
        if (uid < 10000) return
        val c = cells() ?: return
        if (!AppScope.covers(c, uid)) return
        val callback = call.args.firstOrNull { it != null && implements(it.javaClass, "android.telephony.ICellInfoCallback") } ?: return
        try {
            Reflect.call(callback, "onCellInfo", Fakes.cellsOrEmpty(c))
            call.drop()
            report(true, "read", "电话服务已替换基站刷新回调")
        } catch (e: Throwable) {
            call.drop()
            report(false, "error", "基站刷新回调替换失败，已阻止真实请求：${e.javaClass.simpleName}")
        }
    }

    private fun implements(type: Class<*>, name: String): Boolean {
        var c: Class<*>? = type
        while (c != null) {
            if (c.interfaces.any { it.name == name }) return true
            c = c.superclass
        }
        return false
    }
}

/**
 * Bluetooth process (scope "蓝牙"): while the environment is hidden, apps receive no Bluetooth LE scan
 * results, because location SDKs look nearby beacons up to find the real position. Connected devices
 * keep working; classic discovery (pairing in Settings) is untouched.
 */
class BluetoothSide(private val hooks: Hooker, private val loader: ClassLoader) {
    private val drops = AtomicLong()
    private val unknown = AtomicLong()

    private fun report(ok: Boolean, stage: String, detail: String) = Diag.report("bluetooth", ok, stage, detail)

    fun install() {
        var n = 0
        Reflect.classOrNull("android.bluetooth.le.IScannerCallback\$Stub\$Proxy", loader)?.let { proxy ->
            for (name in listOf("onScanResult", "onBatchScanResults", "onFoundOrLost"))
                n += hooks.declared(proxy, name) { m -> hooks.before(m) { drop(it, CallbackOwners.ownerOf(it.thisObject)) } }
        }
        // PendingIntent scans; the class holding them moved between versions. The same classes
        // register scan callbacks, which tells whose callback each proxy is.
        for (name in listOf("com.android.bluetooth.le_scan.TransitionalScanHelper", "com.android.bluetooth.le_scan.ScanController",
            "com.android.bluetooth.gatt.GattService")) {
            val type = Reflect.classOrNull(name, loader) ?: continue
            n += hooks.declared(type, "sendResultsByPendingIntent") { m -> hooks.before(m) { drop(it, pendingOwner(it.args)) } }
            CallbackOwners.watch(hooks, type)
            runCatching { type.declaredClasses.forEach { CallbackOwners.watch(hooks, it) } }
        }
        Log.i("bluetooth scan hooks: $n")
        report(n > 0, if (n > 0) "installed" else "error", if (n > 0) "蓝牙扫描入口已安装（$n 处）" else "蓝牙进程未找到扫描结果入口")
    }

    private fun drop(call: Call, uid: Int) {
        val c = Sim.config.takeIf { it.environmentHidden } ?: return
        if (uid < 0 && c.selectedOnly) {
            val n = unknown.incrementAndGet()
            if (n == 1L || (n and (n - 1)) == 0L) report(false, "error", "蓝牙扫描归属未知 $n 次；无法确认是否为选中应用")
        }
        if (!AppScope.covers(c, uid)) return
        call.drop()
        report(true, "read", "已拦截蓝牙扫描结果 ${drops.incrementAndGet()} 次")
    }

    private fun pendingOwner(args: Array<Any?>): Int {
        for (a in args) {
            if (a == null) continue
            val pi = a as? PendingIntent ?: Reflect.getOrNull(a, "intent") as? PendingIntent ?: continue
            return pi.creatorUid
        }
        return -1
    }
}

package app.geoshift.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Wi-Fi and cell scans of the phone's current radio environment. Blocking; call off the main thread. */
class DeviceScanner(private val context: Context, private val caps: () -> Capabilities) {
    private fun requireLocation() {
        val c = caps()
        if (!c.preciseLocation) fail("请先授予精确位置权限。")
        if (!c.locationEnabled) fail("请打开系统定位开关后重试。")
    }

    fun wifi(): List<ScannedWifi> {
        requireLocation()
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null || !wm.isWifiEnabled) fail("请先开启 Wi-Fi。Android 会限制扫描刷新频率。")
        val results = try {
            @Suppress("DEPRECATION") wm.startScan()
            wm.scanResults
        } catch (e: SecurityException) {
            fail("Wi-Fi 扫描权限不可用，请检查精确位置和附近 Wi-Fi 权限。")
        }
        @Suppress("DEPRECATION")
        return results.orEmpty().distinctBy { it.BSSID }.map { ScannedWifi(it.SSID.orEmpty(), it.BSSID.orEmpty(), it.level, it.frequency) }
    }

    fun cells(): List<ScannedCell> {
        requireLocation()
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) fail("此设备没有蜂窝通信硬件。")
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: fail("系统电话服务不可用。")
        val managers = ArrayList<TelephonyManager>()
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                sm?.activeSubscriptionInfoList?.forEach { managers.add(tm.createForSubscriptionId(it.subscriptionId)) }
            } catch (_: SecurityException) {
            }
        }
        if (managers.isEmpty()) managers.add(tm)
        val out = LinkedHashSet<ScannedCell>()
        try {
            for (m in managers) (refresh(m) ?: m.allCellInfo).orEmpty().mapNotNullTo(out, ::parse)
        } catch (_: SecurityException) {
            fail("读取基站需要精确位置权限。")
        }
        return out.sortedByDescending { it.registered }
    }

    /** Asks the modem for fresh cell info; null on timeout or error. */
    private fun refresh(m: TelephonyManager): List<CellInfo>? {
        if (Build.VERSION.SDK_INT < 29) return null
        val answer = AtomicReference<List<CellInfo>?>()
        val latch = CountDownLatch(1)
        try {
            m.requestCellInfoUpdate(Runnable::run, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cells: MutableList<CellInfo>) { answer.set(cells); latch.countDown() }
                override fun onError(code: Int, detail: Throwable?) { latch.countDown() }
            })
            latch.await(2500, TimeUnit.MILLISECONDS)
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
        }
        return answer.get()
    }

    private fun Int.orNull() = takeIf { it != Int.MAX_VALUE }

    /** MCC / MNC as text (API 28+), or the old integer form on Android 8. */
    @Suppress("DEPRECATION")
    private inline fun code(text: () -> String?, old: () -> Int): String =
        if (Build.VERSION.SDK_INT >= 28) text().orEmpty() else old().orNull()?.toString().orEmpty()

    private fun parse(v: CellInfo): ScannedCell? = try {
        when (v) {
            is CellInfoLte -> v.cellIdentity.let { i ->
                ScannedCell("LTE", code({ i.mccString }) { i.mcc }, code({ i.mncString }) { i.mnc }, i.ci.orNull()?.toLong(), i.tac.orNull(),
                    i.pci.orNull(), i.earfcn.orNull(), v.cellSignalStrength.dbm, v.isRegistered)
            }
            is CellInfoGsm -> v.cellIdentity.let { i ->
                ScannedCell("GSM", code({ i.mccString }) { i.mcc }, code({ i.mncString }) { i.mnc }, i.cid.orNull()?.toLong(), i.lac.orNull(),
                    null, i.arfcn.orNull(), v.cellSignalStrength.dbm, v.isRegistered)
            }
            is CellInfoWcdma -> v.cellIdentity.let { i ->
                ScannedCell("WCDMA", code({ i.mccString }) { i.mcc }, code({ i.mncString }) { i.mnc }, i.cid.orNull()?.toLong(), i.lac.orNull(),
                    i.psc.orNull(), i.uarfcn.orNull(), v.cellSignalStrength.dbm, v.isRegistered)
            }
            is CellInfoCdma -> ScannedCell("CDMA", cid = v.cellIdentity.basestationId.orNull()?.toLong(),
                area = v.cellIdentity.networkId.orNull(), dbm = v.cellSignalStrength.dbm, registered = v.isRegistered)
            else -> if (Build.VERSION.SDK_INT >= 29 && v is CellInfoNr) (v.cellIdentity as CellIdentityNr).let { i ->
                ScannedCell("NR", i.mccString.orEmpty(), i.mncString.orEmpty(), i.nci.takeIf { it != Long.MAX_VALUE }, i.tac.orNull(),
                    i.pci.orNull(), i.nrarfcn.orNull(), v.cellSignalStrength.dbm, v.isRegistered)
            } else null
        }
    } catch (_: Exception) {
        null
    }
}

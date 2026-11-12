package app.geoshift.xposed

import android.location.GnssStatus
import android.location.Location
import android.net.wifi.ScanResult
import android.os.Build
import android.os.SystemClock
import android.telephony.CellIdentityLte
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import app.geoshift.core.CellSpec
import app.geoshift.core.CellType
import app.geoshift.core.Fix
import app.geoshift.core.FixBuilder
import app.geoshift.core.SimConfig
import app.geoshift.core.Sky
import app.geoshift.core.WifiSpec
import app.geoshift.core.toLocation
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Simulated framework objects (locations, satellites, cells, Wi-Fi) built from the config. */
object Fakes {
    const val HIDDEN_BSSID = "02:00:00:00:00:00"

    // ---- Location -----------------------------------------------------------------------------

    fun fix(c: SimConfig, provider: String, atMs: Long = 0): Fix =
        FixBuilder.build(c, provider, atMs, Sim.clock, Sim.plan)

    fun location(c: SimConfig, provider: String, atMs: Long = 0): Location = fix(c, provider, atMs).toLocation()

    /** Simulated replacement for a delivered fix (keeps its provider and, for batches, its moment). */
    fun replace(c: SimConfig, original: Any?): Location {
        val o = original as? Location
        return FixBuilder.replacing(c, o?.provider, o?.elapsedRealtimeNanos ?: 0, Sim.clock, Sim.plan).toLocation()
    }

    /** android.location.LocationResult (Android 12+), the framework's delivery packet. */
    private val resultClass by lazy { Class.forName("android.location.LocationResult") }

    fun wrap(location: Location): Any = Reflect.callStatic(resultClass, "create", listOf(location))!!

    /** Fixes inside a LocationResult: asList() on AOSP 12–16; size()/get(i) and mLocations as fallbacks. */
    fun fixesOf(packet: Any): List<*> {
        Reflect.callOrNull(packet, "asList")?.let { return it as List<*> }
        val size = Reflect.callOrNull(packet, "size") as? Int
        if (size != null) return (0 until size).map { Reflect.call(packet, "get", it) }
        return Reflect.get(packet, "mLocations", "定位交付") as List<*>
    }

    /** A new LocationResult of the same fixes replaced by simulated ones (never mutates the original). */
    fun replacePacket(c: SimConfig, packet: Any): Any {
        val fixes = fixesOf(packet)
        val replaced = if (fixes.isEmpty()) listOf(replace(c, null)) else fixes.map { replace(c, it) }
        return Reflect.callStatic(packet.javaClass, "create", replaced)!!
    }

    fun lastOf(packet: Any): Location? = Reflect.callOrNull(packet, "getLastLocation") as? Location

    // ---- Satellites ---------------------------------------------------------------------------

    fun gnss(c: SimConfig): GnssStatus {
        val b = GnssStatus.Builder()
        for (s in Sky.at(c, SystemClock.elapsedRealtime())) {
            b.addSatellite(s.constellation, s.svid, s.cn0.toFloat(), s.elevation.toFloat(), s.azimuth.toFloat(),
                true, true, s.used, true, s.carrierHz, false, 0f)
        }
        return b.build()
    }

    // ---- Cells --------------------------------------------------------------------------------

    /** Simulated cells, or an empty list (hides the real towers). */
    fun cellsOrEmpty(c: SimConfig): MutableList<Any> = c.cells.take(32).mapTo(ArrayList()) { cell(it) }

    /** Identity of the serving simulated cell, or one with every field unavailable. */
    fun cellIdentityOrEmpty(c: SimConfig): Any =
        c.cells.firstOrNull()?.let { Reflect.call(cell(it), "getCellIdentity")!! } ?: Reflect.newInstance(CellIdentityLte::class.java)

    fun cell(v: CellSpec): Any {
        val lte = v.type == CellType.LTE
        val nr = v.type == CellType.NR
        val info = Reflect.newInstance(if (nr) android.telephony.CellInfoNr::class.java else if (lte) CellInfoLte::class.java else CellInfoGsm::class.java)
        val identity = Reflect.call(info, "getCellIdentity")!!
        val signal = Reflect.call(info, "getCellSignalStrength")!!
        if (Reflect.hasField(identity.javaClass, "mMccStr")) {
            Reflect.set(identity, "mMccStr", v.mcc, "基站")
            Reflect.set(identity, "mMncStr", v.mnc, "基站")
        } else {
            Reflect.set(identity, "mMcc", v.mcc.toInt(), "基站")
            Reflect.set(identity, "mMnc", v.mnc.toInt(), "基站")
        }
        Reflect.set(identity, if (nr) "mNci" else if (lte) "mCi" else "mCid", if (nr) v.cid as Any else v.cid.toInt() as Any, "基站")
        Reflect.set(identity, if (lte || nr) "mTac" else "mLac", v.area, "基站")
        if (nr) {
            Reflect.set(identity, "mPci", v.pci, "基站")
            Reflect.set(identity, "mNrArfcn", v.channel, "基站")
            Reflect.set(signal, "mSsRsrp", v.dbm, "基站信号")
        } else if (lte) {
            Reflect.set(identity, "mPci", v.pci, "基站")
            Reflect.set(identity, "mEarfcn", v.channel, "基站")
            Reflect.set(signal, "mRsrp", v.dbm, "基站信号")
        } else {
            Reflect.set(identity, "mArfcn", v.channel, "基站")
            Reflect.set(signal, "mRssi", v.dbm, "基站信号")
        }
        // Android 10+ caches the bar count in mLevel; without it getLevel() reports no signal.
        if (Reflect.hasField(signal.javaClass, "mLevel")) Reflect.set(signal, "mLevel", signalLevel(v), "基站信号")
        // CellIdentity derives its global id in the constructor; refresh it after the fields changed.
        Reflect.callOrNull(identity, "updateGlobalCellId")
        Reflect.call(info, "setRegistered", v.registered)
        val now = SystemClock.elapsedRealtimeNanos()
        if (Reflect.find(info.javaClass, "setTimeStamp", arrayOf(now)) != null) Reflect.call(info, "setTimeStamp", now)
        else Reflect.set(info, "mTimeStamp", now, "基站时间")
        val read = (Reflect.call(identity, if (nr) "getNci" else if (lte) "getCi" else "getCid") as Number).toLong()
        check(read == v.cid) { "Cell identity readback mismatch" }
        return info
    }

    /** Signal bars 0–4 from the AOSP default carrier thresholds (NR SS-RSRP, LTE RSRP, GSM RSSI). */
    fun signalLevel(v: CellSpec): Int {
        val thresholds = when (v.type) {
            CellType.NR -> intArrayOf(-110, -90, -80, -65)
            CellType.LTE -> intArrayOf(-115, -105, -95, -85)
            CellType.GSM -> intArrayOf(-107, -103, -97, -89)
        }
        return thresholds.count { v.dbm >= it }
    }

    /**
     * Copy of a ServiceState with the real serving-cell identities removed, the same way Android does
     * it for apps without location permission. With simulated cells, registrations that carried an
     * identity get the simulated one, so it matches getAllCellInfo.
     */
    fun serviceState(state: Any, c: SimConfig): Any {
        val copy = Reflect.call(state, "createLocationInfoSanitizedCopy", false)!!
        if (c.cells.isEmpty()) return copy
        val identity = cellIdentityOrEmpty(c)
        val real = Reflect.get(state, "mNetworkRegistrationInfos", "网络状态") as List<*>
        val safe = Reflect.get(copy, "mNetworkRegistrationInfos", "网络状态") as List<*>
        for (i in 0 until minOf(real.size, safe.size)) {
            if (Reflect.get(real[i]!!, "mCellIdentity", "网络注册") != null) Reflect.set(safe[i]!!, "mCellIdentity", identity, "网络注册")
        }
        return copy
    }

    // ---- Wi-Fi --------------------------------------------------------------------------------

    /** android.net.wifi.WifiSsid for a name, or null if the platform has no usable factory. */
    fun wifiSsid(name: String): Any? {
        val type = Reflect.classOrNull("android.net.wifi.WifiSsid", null) ?: return null
        return runCatching { Reflect.callStatic(type, "fromBytes", name.toByteArray(StandardCharsets.UTF_8)) }.getOrNull()
            ?: runCatching { Reflect.callStatic(type, "createFromAsciiEncoded", name) }.getOrNull()
    }

    /** Scan results an app should see: the simulated access point, or nothing. */
    fun scanResults(c: SimConfig): MutableList<ScanResult> {
        val w = c.wifi ?: return ArrayList()
        val r = if (Build.VERSION.SDK_INT >= 30) ScanResult() else Reflect.newInstance(ScanResult::class.java) as ScanResult
        @Suppress("DEPRECATION")
        run {
            r.SSID = w.ssid
            r.BSSID = w.bssid.lowercase(Locale.ROOT)
            r.capabilities = "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"
            r.level = w.safeRssi
            r.frequency = w.safeFrequency
            r.timestamp = SystemClock.elapsedRealtime() * 1000
        }
        wifiSsid(w.ssid)?.let { Reflect.callOrNull(r, "setWifiSsid", it) }
        return arrayListOf(r)
    }

    /** Link fields kept in a rebuilt WifiInfo: they keep the connection usable and name no access point. */
    private val LINK_FIELDS = listOf(
        "getNetworkId" to "setNetworkId", "getRssi" to "setRssi", "getFrequency" to "setFrequency",
        "getLinkSpeed" to "setLinkSpeed", "getTxLinkSpeedMbps" to "setTxLinkSpeedMbps",
        "getRxLinkSpeedMbps" to "setRxLinkSpeedMbps", "getWifiStandard" to "setWifiStandard",
    )

    /** An empty WifiInfo with only the real connection state. */
    fun blankWifiInfo(original: Any): Any {
        val info = Reflect.newInstance(original.javaClass)
        Reflect.call(info, "setSupplicantState", Reflect.call(original, "getSupplicantState"))
        return info
    }

    /** WifiInfo naming the simulated access point (or none), built from scratch so no real identity leaks. */
    fun disguiseWifiInfo(original: Any, wifi: WifiSpec?): Any {
        val info = blankWifiInfo(original)
        for ((get, set) in LINK_FIELDS) {
            val value = Reflect.callOrNull(original, get) ?: continue
            Reflect.callOrNull(info, set, value)
        }
        Reflect.getOrNull(original, "mIpAddress")?.let { runCatching { Reflect.set(info, "mIpAddress", it) } }
        if (wifi == null) {
            Reflect.call(info, "setBSSID", HIDDEN_BSSID)
            Reflect.call(info, "setSSID", null)
            return info
        }
        Reflect.call(info, "setBSSID", wifi.bssid.lowercase(Locale.ROOT))
        Reflect.call(info, "setSSID", wifiSsid(wifi.ssid) ?: error("WifiSsid unavailable"))
        Reflect.call(info, "setFrequency", wifi.safeFrequency)
        Reflect.call(info, "setRssi", wifi.safeRssi)
        return info
    }
}

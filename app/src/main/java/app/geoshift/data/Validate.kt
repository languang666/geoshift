package app.geoshift.data

import app.geoshift.core.CellSpec
import app.geoshift.core.CellType
import app.geoshift.core.LatLon

/** Input checks shared by the editors, import and the simulation. Failures are [UserError]s. */
object Validate {
    const val MAX_RECORDS = 1000
    const val MAX_ROUTE_POINTS = 10_000
    const val MAX_CELLS = 32
    private val PACKAGE = Regex("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+")
    private val BSSID = Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
    private val MCC = Regex("[0-9]{3}")
    private val MNC = Regex("[0-9]{2,3}")
    private val MAP_KEY = Regex("[A-Za-z0-9]{16,128}")

    fun coordinate(p: LatLon) { if (!p.valid) fail("经纬度无效。") }

    fun name(name: String): String {
        val n = name.trim()
        if (n.isEmpty() || n.length > 120) fail("名称需要 1–120 字。")
        return n
    }

    fun route(points: List<LatLon>) {
        if (points.size < 2 || points.size > MAX_ROUTE_POINTS) fail("路线需要 2–10,000 个点。")
        points.forEach(::coordinate)
    }

    fun wifi(ssid: String, bssid: String) {
        if (ssid.isBlank() || ssid.trim().toByteArray().size > 32) fail("SSID 不能为空，且不能超过 32 字节。")
        if (!bssid.trim().matches(BSSID)) fail("BSSID 格式不正确，应为 xx:xx:xx:xx:xx:xx。")
    }

    fun cell(c: CellSpec) {
        if (!c.mcc.matches(MCC) || !c.mnc.matches(MNC))
            fail("MCC 应为 3 位数字（中国为 460），MNC 应为 2–3 位数字。")
        val maxCid = when (c.type) { CellType.LTE -> 268435455L; CellType.GSM -> 65535L; CellType.NR -> 68719476735L }
        val maxArea = if (c.type == CellType.NR) 16777215 else 65535
        if (c.cid !in 0..maxCid) fail("小区识别码超出 ${c.type} 范围（0–$maxCid）。")
        if (c.area !in 0..maxArea) fail("TAC/LAC 应为 0–$maxArea。")
        if (c.type != CellType.GSM && c.pci !in 0..(if (c.type == CellType.NR) 1007 else 503)) fail("PCI 超出 ${c.type} 范围。")
        if (c.type == CellType.NR && c.channel !in 0..3279165) fail("NR-ARFCN 应为 0–3279165。")
        if (c.dbm !in (if (c.type == CellType.NR) -156..-31 else -160..-20)) fail("信号强度超出 ${c.type} 范围。")
    }

    fun cellOk(c: CellSpec) = runCatching { cell(c) }.isSuccess

    fun packages(list: List<String>) {
        if (list.joinToString(",").length > 4000) fail("包名列表过长。")
        for (p in list) if (!p.matches(PACKAGE)) fail("包名无效：$p")
    }

    fun mapKey(key: String) {
        if (key.isNotEmpty() && !key.matches(MAP_KEY)) fail("地图 Key 格式无效。")
    }

    fun inRange(value: Double, min: Double, max: Double, message: String): Double {
        if (!value.isFinite() || value < min || value > max) fail(message)
        return value
    }
}

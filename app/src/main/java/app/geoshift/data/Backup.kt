package app.geoshift.data

import app.geoshift.core.CellSpec
import app.geoshift.core.CellType
import app.geoshift.core.LatLon
import app.geoshift.core.SimConfig
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Export / import of records. 3.x writes version 3; 2.x backups (version 1) can still be imported. */
object Backup {
    @Serializable
    private data class V3(
        val format: String = "geoshift",
        val version: Int = 3,
        val createdAt: Long = System.currentTimeMillis(),
        val places: List<SavedPlace> = emptyList(),
        val routes: List<SavedRoute> = emptyList(),
        val wifiPresets: List<WifiPreset> = emptyList(),
        val cellPresets: List<CellPreset> = emptyList(),
        val environments: List<EnvironmentRecord> = emptyList(),
    )

    private val pretty = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }

    fun export(store: Store): String = pretty.encodeToString(V3.serializer(), V3(
        places = store.places.items.value, routes = store.routes.items.value, wifiPresets = store.wifiPresets.items.value,
        cellPresets = store.cellPresets.items.value, environments = store.environments.items.value))

    /** Validates everything first, so a bad file imports nothing. Returns the number of records. */
    fun import(store: Store, text: String): Int {
        val head = runCatching { JSONObject(text) }.getOrElse { fail("文件不是有效的 JSON。") }
        val data = when {
            head.optString("format") == "geoshift" && head.optInt("version") == 3 ->
                runCatching { SimConfig.JSON.decodeFromString(V3.serializer(), text) }.getOrElse { fail("备份内容无法识别：${it.message}") }
            head.optString("format") in listOf("geoshift", "portal-rebuild") && head.optInt("version") == 1 -> fromV1(head)
            else -> fail("文件不是受支持的 Geoshift 备份。")
        }
        val total = data.places.size + data.routes.size + data.wifiPresets.size + data.cellPresets.size + data.environments.size
        if (listOf(data.places, data.routes, data.wifiPresets, data.cellPresets, data.environments).any { it.size > Validate.MAX_RECORDS }) fail("记录数量过多。")
        data.places.forEach { Validate.name(it.name); Validate.coordinate(it.position) }
        data.routes.forEach { Validate.name(it.name); Validate.route(it.points) }
        data.wifiPresets.forEach { Validate.name(it.name); Validate.wifi(it.ssid, it.bssid) }
        data.cellPresets.forEach { Validate.name(it.name); Validate.cell(it.cell) }
        data.environments.forEach { Validate.name(it.name); Validate.coordinate(it.position) }
        // Room is checked for every list before anything is written, and each list is written once.
        if (!store.places.fits(data.places) || !store.routes.fits(data.routes) || !store.wifiPresets.fits(data.wifiPresets) ||
            !store.cellPresets.fits(data.cellPresets) || !store.environments.fits(data.environments))
            fail("导入后记录会超过 ${Validate.MAX_RECORDS} 条，请先导出并清理。")
        store.places.putAll(data.places)
        store.routes.putAll(data.routes)
        store.wifiPresets.putAll(data.wifiPresets)
        store.cellPresets.putAll(data.cellPresets)
        store.environments.putAll(data.environments)
        return total
    }

    // ---- 2.x (version 1) ------------------------------------------------------------------------

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.id() = optString("id").ifEmpty { UUID.randomUUID().toString() }
    private fun JSONObject.time(vararg keys: String) = keys.firstNotNullOfOrNull { k -> optLong(k).takeIf { it > 0 } } ?: System.currentTimeMillis()
    private fun JSONObject.point() = LatLon(optDouble("lat"), optDouble("lon"))
    private fun JSONArray?.points() = objects().map { it.point() }

    private fun cellOf(o: JSONObject): CellSpec? {
        val type = when (o.optString("networkType", "LTE").uppercase()) { "LTE" -> CellType.LTE; "GSM" -> CellType.GSM; else -> return null }
        val lte = type == CellType.LTE
        return CellSpec(type, o.optString("mcc"), o.optString("mnc"), o.optLong("cid", -1), o.optInt(if (lte) "tac" else "lac", -1),
            o.optInt("pci", 0), o.optInt(if (lte) "earfcn" else "arfcn", 0), o.optInt("rssi", -85), o.optBoolean("registered", true))
    }

    private fun scannedCell(o: JSONObject) = ScannedCell(
        type = o.optString("networkType"), mcc = o.optString("mcc"), mnc = o.optString("mnc"),
        cid = if (o.has("cid") && !o.isNull("cid")) o.optLong("cid") else o.optString("nci").toLongOrNull(),
        area = (if (o.has("tac")) o.opt("tac") else o.opt("lac")) as? Int,
        pci = o.opt("pci") as? Int, channel = (o.opt("earfcn") ?: o.opt("arfcn")) as? Int,
        dbm = o.optInt("rssi", -85), registered = o.optBoolean("registered"))

    private fun fromV1(o: JSONObject) = V3(
        places = o.optJSONArray("locations").objects().map { SavedPlace(it.id(), it.optString("name"), it.point(), it.optString("address"), it.time("createdAt")) },
        routes = o.optJSONArray("routes").objects().map {
            SavedRoute(it.id(), it.optString("name"), it.optJSONArray("points").points(), it.optJSONArray("waypoints").points(),
                RoadProfile.entries.firstOrNull { p -> p.name.equals(it.optString("profile"), true) } ?: RoadProfile.STRAIGHT, it.time("createdAt"))
        },
        wifiPresets = o.optJSONArray("wifiPresets").objects().map { WifiPreset(it.id(), it.optString("name"), it.optString("ssid"), it.optString("bssid").lowercase(), it.time("createdAt")) },
        cellPresets = o.optJSONArray("cellPresets").objects().mapNotNull { row -> cellOf(row)?.let { CellPreset(row.id(), row.optString("name"), it, row.time("createdAt")) } },
        environments = o.optJSONArray("environments").objects().map {
            EnvironmentRecord(it.id(), it.optString("name"), it.point(), it.time("capturedAt", "createdAt"),
                cells = it.optJSONObject("cells")?.optJSONArray("results").objects().map(::scannedCell),
                wifi = it.optJSONObject("wifi")?.optJSONArray("results").objects().map { w ->
                    ScannedWifi(w.optString("ssid"), w.optString("bssid"), w.optInt("rssi"), w.optInt("frequency"))
                },
                deviceDistance = it.optDouble("selectedDistanceFromDeviceMeters").takeIf { d -> d.isFinite() },
                note = it.optString("locationWarning"), simulatedAtCapture = it.optBoolean("simulationActiveAtCapture"))
        },
    )
}

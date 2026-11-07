package app.geoshift.data

import app.geoshift.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Keyless place search (Photon by Komoot) and address lookup (Nominatim), both on OpenStreetMap
 * data. Used when no AMap Web service key is set. Coordinates are WGS-84.
 */
object OpenGeocoder {
    private var lastNominatim = 0L

    fun search(query: String, near: LatLon?): List<PortalActions.Place> {
        val q = query.trim()
        if (q.isEmpty() || q.length > 200) throw PortalException("请输入 1–200 字的地点关键词。")
        val bias = near?.let { String.format(Locale.ROOT, "&lat=%.5f&lon=%.5f&zoom=12&location_bias_scale=0", it.lat, it.lon) }.orEmpty()
        val body = httpText("https://photon.komoot.io/api/?limit=15&lang=default&q=" + URLEncoder.encode(q, "UTF-8") + bias)
        val features = runCatching { JSONObject(body).optJSONArray("features") }.getOrNull() ?: JSONArray()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.optJSONObject(i) ?: return@mapNotNull null
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val p = LatLon(c.optDouble(1), c.optDouble(0))
            if (!Geo.valid(p.lat, p.lon)) return@mapNotNull null
            val props = f.optJSONObject("properties") ?: JSONObject()
            val name = props.optString("name").ifEmpty { props.optString("street") }.ifEmpty { return@mapNotNull null }
            PortalActions.Place(name, address(props, name), p)
        }.distinctBy { it.name to it.address }
    }

    fun reverse(p: LatLon): String {
        // Nominatim allows one request per second.
        synchronized(this) {
            val wait = lastNominatim + 1100 - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastNominatim = System.currentTimeMillis()
        }
        val body = httpText(String.format(Locale.ROOT,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=zh-CN,zh,en&lat=%.6f&lon=%.6f", p.lat, p.lon))
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return ""
        val a = json.optJSONObject("address") ?: return json.optString("display_name")
        // Chinese order, largest area first; falls back to Nominatim's own text.
        val parts = listOf("state", "city", "county", "district", "suburb", "town", "village", "road", "house_number")
            .map { a.optString(it).trim() }.filter { it.isNotEmpty() }.distinct()
        val place = json.optString("name").trim()
        val text = (parts + listOfNotNull(place.takeIf { it.isNotEmpty() && it !in parts })).joinToString("")
        return text.ifEmpty { json.optString("display_name") }
    }

    private fun address(props: JSONObject, name: String): String {
        val parts = listOf("state", "city", "district", "county", "street", "housenumber")
            .map { props.optString(it).trim() }.filter { it.isNotEmpty() && it != name }.distinct()
        val country = props.optString("country")
        return if (props.optString("countrycode").equals("CN", true)) parts.joinToString("")
        else (parts.reversed() + listOfNotNull(country.takeIf { it.isNotEmpty() })).joinToString(", ")
    }
}

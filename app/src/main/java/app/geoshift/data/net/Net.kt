package app.geoshift.data.net

import app.geoshift.BuildConfig
import app.geoshift.core.Geo
import app.geoshift.core.LatLon
import app.geoshift.data.Place
import app.geoshift.data.RoadProfile
import app.geoshift.data.UserError
import app.geoshift.data.fail
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** HTTPS GET; returns the body of a 2xx reply (or of [allowStatus]). Errors are [UserError]s in Chinese. */
fun httpText(url: String, allowStatus: Int = -1, maxBytes: Int = 8 * 1024 * 1024): String {
    val connection = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Geoshift/${BuildConfig.VERSION_NAME} (Android; app.geoshift; +https://github.com/languang666/geoshift)")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
        }
    } catch (e: Exception) {
        fail("网络请求失败（${e.javaClass.simpleName}）。")
    }
    try {
        val code = try { connection.responseCode } catch (e: Exception) { fail("连接失败（${e.javaClass.simpleName}），请检查网络后重试。") }
        if (code !in 200..299 && code != allowStatus) fail(if (code == 429) "请求太频繁，请稍后再试。" else "服务暂时不可用（HTTP $code），请稍后重试。")
        val stream = (if (code in 200..299) connection.inputStream else connection.errorStream) ?: fail("服务没有返回内容（HTTP $code）。")
        stream.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = try { it.read(buffer) } catch (e: Exception) { fail("读取响应失败（${e.javaClass.simpleName}），请重试。") }
                if (n < 0) break
                total += n
                if (total > maxBytes) fail("响应过大。")
                out.write(buffer, 0, n)
            }
            return out.toString("UTF-8")
        }
    } finally {
        connection.disconnect()
    }
}

private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

/** Keyless search (Photon) and address lookup (Nominatim) on OpenStreetMap data. */
object OpenGeocoder {
    private var lastNominatim = 0L

    fun search(query: String, near: LatLon?): List<Place> {
        val bias = near?.let { String.format(Locale.ROOT, "&lat=%.5f&lon=%.5f&zoom=12&location_bias_scale=0", it.lat, it.lon) }.orEmpty()
        // lang=default: names as the map shows them (lang=zh falls back to English for most places).
        val body = httpText("https://photon.komoot.io/api/?limit=15&lang=default&q=${enc(query)}$bias")
        val features = runCatching { JSONObject(body).optJSONArray("features") }.getOrNull() ?: JSONArray()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.optJSONObject(i) ?: return@mapNotNull null
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val p = LatLon(c.optDouble(1), c.optDouble(0)).takeIf { it.valid } ?: return@mapNotNull null
            val props = f.optJSONObject("properties") ?: JSONObject()
            val name = props.optString("name").ifEmpty { props.optString("street") }.ifEmpty { return@mapNotNull null }
            Place(name, address(props, name), p)
        }.distinctBy { it.name to it.address }
    }

    fun reverse(p: LatLon): String {
        // Nominatim allows one request per second.
        synchronized(this) {
            val wait = lastNominatim + 1100 - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastNominatim = System.currentTimeMillis()
        }
        val json = runCatching {
            JSONObject(httpText(String.format(Locale.ROOT,
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=zh-CN,zh,en&lat=%.6f&lon=%.6f", p.lat, p.lon)))
        }.getOrNull() ?: return ""
        val a = json.optJSONObject("address") ?: return json.optString("display_name")
        // Chinese order, largest area first.
        val parts = listOf("state", "city", "county", "district", "suburb", "town", "village", "road", "house_number")
            .map { a.optString(it).trim() }.filter { it.isNotEmpty() }.distinct()
        val place = json.optString("name").trim()
        return (parts + listOfNotNull(place.takeIf { it.isNotEmpty() && it !in parts })).joinToString("").ifEmpty { json.optString("display_name") }
    }

    private fun address(props: JSONObject, name: String): String {
        val parts = listOf("state", "city", "district", "county", "street", "housenumber")
            .map { props.optString(it).trim() }.filter { it.isNotEmpty() && it != name }.distinct()
        return if (props.optString("countrycode").equals("CN", true)) parts.joinToString("")
        else (parts.reversed() + listOfNotNull(props.optString("country").takeIf { it.isNotEmpty() })).joinToString(", ")
    }
}

/** Public exit-IP location; [position] is WGS-84 when known. */
data class IpResult(val ip: String, val place: String, val position: LatLon?, val source: String)

object IpLocator {
    /** ipwho.is: worldwide, keyless. */
    fun lookup(): IpResult {
        val r = runCatching { JSONObject(httpText("https://ipwho.is/", maxBytes = 256 * 1024)) }.getOrElse { if (it is UserError) throw it else fail("IP 定位服务返回了无法识别的内容。") }
        if (!r.optBoolean("success", true) && r.has("message")) fail("IP 定位服务返回错误：${r.optString("message")}")
        val clean = { s: String -> s.takeUnless { it == "[]" }.orEmpty().trim() }
        val country = clean(r.optString("country"))
        val region = clean(r.optString("region"))
        val city = clean(r.optString("city"))
        val place = listOf(country, if (region == city) "" else region, city).filter { it.isNotEmpty() }.joinToString(" · ")
        val p = LatLon(r.optDouble("latitude", Double.NaN), r.optDouble("longitude", Double.NaN)).takeIf { it.valid }
        return IpResult(r.optString("ip"), place, p, "ipwho.is")
    }
}

/**
 * Snaps route sections to real roads: AMap route planning with the user's key (better road data in China),
 * otherwise the public OSRM servers of FOSSGIS (OpenStreetMap). Coordinates in and out are WGS-84.
 */
class RoadRouter(private val amap: AmapSdk) {
    val source get() = if (amap.ready) "高德" else "OpenStreetMap"
    private val cache = object : LinkedHashMap<String, List<LatLon>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LatLon>>) = size > 200
    }
    private var lastAmap = 0L

    /** Road path from a to b, starting at a and ending at b. Blocking. */
    fun section(profile: RoadProfile, a: LatLon, b: LatLon): List<LatLon> {
        if (!profile.road) return listOf(a, b)
        val useAmap = amap.ready
        val key = String.format(Locale.ROOT, "%s|%s|%.6f,%.6f|%.6f,%.6f", if (useAmap) "amap" else "osrm", profile.name, a.lat, a.lon, b.lat, b.lon)
        synchronized(cache) { cache[key]?.let { return it } }
        val road = if (useAmap) {
            try { viaAmap(profile, a, b) } catch (e: AmapSdk.AmapError) {
                // Overseas sections: AMap refuses them; OpenStreetMap covers them.
                if (e.outOfCoverage) osrm(profile, a, b) else throw UserError(e.message ?: "高德路径规划失败。")
            }
        } else osrm(profile, a, b)
        // Join the tapped points to where the road begins and ends, so sections connect exactly.
        val path = ArrayList<LatLon>(road.size + 2)
        path.add(a)
        for (p in road) if (Geo.distance(path.last(), p) > 0.5) path.add(p)
        if (Geo.distance(path.last(), b) > 0.5) path.add(b) else path[path.lastIndex] = b
        synchronized(cache) { cache[key] = path }
        return path
    }

    private fun viaAmap(profile: RoadProfile, a: LatLon, b: LatLon): List<LatLon> {
        // Personal AMap keys allow only a few requests per second.
        synchronized(this) {
            val wait = lastAmap + 350 - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastAmap = System.currentTimeMillis()
        }
        return amap.direction(profile, a, b)
    }

    private fun osrm(profile: RoadProfile, a: LatLon, b: LatLon): List<LatLon> {
        val url = String.format(Locale.ROOT,
            "https://routing.openstreetmap.de/routed-%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
            profile.osrm, a.lon, a.lat, b.lon, b.lat)
        val json = runCatching { JSONObject(httpText(url, allowStatus = 400)) }.getOrElse { if (it is UserError) throw it else fail("道路服务返回了无法识别的内容。") }
        if (json.optString("code") != "Ok")
            fail(if (json.optString("code") == "NoRoute") "这两点之间没有找到道路，可改用直线连接。" else "道路服务错误：${json.optString("message", json.optString("code"))}")
        val coords = json.optJSONArray("routes")?.optJSONObject(0)?.optJSONObject("geometry")?.optJSONArray("coordinates")
            ?: fail("这两点之间没有找到道路，可改用直线连接。")
        return (0 until coords.length()).mapNotNull { i ->
            val c = coords.optJSONArray(i) ?: return@mapNotNull null
            LatLon(c.optDouble(1), c.optDouble(0)).takeIf { it.valid }
        }
    }

    companion object {
        /** Joins sections end to start (each starts where the previous ends). */
        fun join(sections: List<List<LatLon>>): List<LatLon> {
            val out = ArrayList<LatLon>()
            for (s in sections) for (p in s) if (out.isEmpty() || out.last() != p) out.add(p)
            return out
        }
    }
}

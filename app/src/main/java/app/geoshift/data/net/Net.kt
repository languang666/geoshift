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

/** AMap Web service (optional key): search, address, road routes and IP location in China. */
class Amap(private val key: () -> String) {
    class AmapError(val code: String, message: String) : Exception(message) {
        /** The point lies outside AMap's coverage (abroad). */
        val outOfCoverage get() = code == "20011" || code == "20800"
    }

    val available get() = key().isNotEmpty()

    private fun lonLat(p: LatLon) = String.format(Locale.ROOT, "%.6f,%.6f", p.lon, p.lat)

    private fun get(path: String, query: String): JSONObject {
        val k = key().ifEmpty { fail("地点搜索和地址查询需要高德 Web 服务 Key，请在设置中填写。") }
        val json = runCatching { JSONObject(httpText("https://restapi.amap.com$path?key=${enc(k)}&$query", maxBytes = 2 * 1024 * 1024)) }
            .getOrElse { if (it is UserError) throw it else fail("地图服务返回了无法识别的内容。") }
        if (json.optString("status") != "1") throw AmapError(json.optString("infocode"), explain(json.optString("infocode"), json.optString("info", "未知错误")))
        return json
    }

    fun search(query: String): List<Place> {
        val tips = get("/v3/assistant/inputtips", "keywords=${enc(query)}&datatype=all").optJSONArray("tips") ?: JSONArray()
        return (0 until tips.length()).mapNotNull { i ->
            val tip = tips.optJSONObject(i) ?: return@mapNotNull null
            val v = tip.optString("location").split(",")
            if (v.size != 2) return@mapNotNull null
            val gcj = LatLon(v[1].toDoubleOrNull() ?: return@mapNotNull null, v[0].toDoubleOrNull() ?: return@mapNotNull null)
            if (!gcj.valid) return@mapNotNull null
            Place(tip.optString("name"), tip.optString("district") + tip.optString("address").takeUnless { it == "[]" }.orEmpty(), Geo.gcjToWgs(gcj))
        }
    }

    fun reverse(p: LatLon): String {
        val regeo = get("/v3/geocode/regeo", "extensions=base&location=${enc(lonLat(Geo.wgsToGcj(p)))}").optJSONObject("regeocode")
        return regeo?.optString("formatted_address").orEmpty().takeUnless { it == "[]" }.orEmpty()
    }

    /** Road path a → b (WGS-84). */
    fun direction(profile: RoadProfile, a: LatLon, b: LatLon): List<LatLon> {
        var od = "origin=${enc(lonLat(Geo.wgsToGcj(a)))}&destination=${enc(lonLat(Geo.wgsToGcj(b)))}"
        val path = when (profile) {
            RoadProfile.CAR -> "/v3/direction/driving".also { od += "&extensions=base" }
            RoadProfile.BIKE -> "/v5/direction/bicycling".also { od += "&show_fields=polyline" }
            else -> "/v3/direction/walking"
        }
        val steps = get(path, od).optJSONObject("route")?.optJSONArray("paths")?.optJSONObject(0)?.optJSONArray("steps") ?: JSONArray()
        val out = ArrayList<LatLon>()
        for (i in 0 until steps.length()) {
            for (pair in steps.optJSONObject(i)?.optString("polyline").orEmpty().split(";")) {
                val v = pair.split(",")
                if (v.size != 2) continue
                val gcj = LatLon(v[1].toDoubleOrNull() ?: continue, v[0].toDoubleOrNull() ?: continue)
                if (gcj.valid) out.add(Geo.gcjToWgs(gcj))
            }
        }
        if (out.size < 2) fail("高德没有找到这两点之间的道路，可改用直线连接。")
        return out
    }

    /** Province/city of the request's exit IP, or null for overseas IPs. */
    fun ip(): IpResult? {
        val r = runCatching { get("/v3/ip", "") }.getOrNull() ?: return null
        val rect = r.optString("rectangle")
        if (rect.isEmpty() || rect == "[]") return null
        val clean = { s: String -> s.takeUnless { it == "[]" }.orEmpty().trim() }
        val province = clean(r.optString("province"))
        val city = clean(r.optString("city"))
        val place = (province + if (city.isEmpty() || city == province) "" else " $city").trim()
        val corners = rect.split(";").map { it.split(",") }
        val centre = if (corners.size == 2 && corners.all { it.size == 2 }) runCatching {
            Geo.gcjToWgs(LatLon((corners[0][1].toDouble() + corners[1][1].toDouble()) / 2, (corners[0][0].toDouble() + corners[1][0].toDouble()) / 2))
        }.getOrNull() else null
        return IpResult("", place, centre, "高德")
    }

    companion object {
        /** AMap error codes as actionable Chinese messages. */
        fun explain(code: String, info: String): String {
            val hint = when (code) {
                "10001" -> "Key 不正确或已过期，请检查是否完整复制了 32 位 Key。"
                "10002" -> "此 Key 没有开通该服务，请在高德控制台确认 Key 的服务平台是「Web 服务」。"
                "10003" -> "今日调用次数已用完，明天会恢复。"
                "10004" -> "请求太频繁，请稍后再试。"
                "10005" -> "IP 白名单不匹配：请在高德控制台把这个 Key 的 IP 白名单清空。"
                "10006" -> "绑定的域名不匹配，请在控制台去掉域名限制。"
                "10007" -> "Key 开启了「数字签名」，请在高德控制台关闭数字签名。"
                "10008" -> "安全码校验失败：这是 Android/iOS 平台的 Key，请另外创建一个「Web 服务」平台的 Key。"
                "10009" -> "Key 平台不匹配：填入的是 Android 平台 Key，请改填「Web 服务」平台的 Key。"
                "10010" -> "当前 IP 访问超限，请稍后再试。"
                "10012" -> "此 Key 没有该接口的权限。"
                "10013" -> "此 Key 已被删除，请重新创建。"
                "10044", "10045" -> "账号或 Key 的日调用量已超限。"
                "20000", "20001" -> "请求参数有误，请重试；若持续出现请反馈。"
                "20011" -> "该地点在海外，高德个人 Key 没有海外权限。"
                "20800" -> "该地点不在中国境内，高德无法规划路线。"
                "20801" -> "起点或终点附近没有道路，请把点放到路上。"
                "20802" -> "两点之间找不到可通行的路线。"
                "20803" -> "两点距离太远，请分段添加途经点。"
                else -> ""
            }
            return "高德返回错误 $code（$info）" + if (hint.isEmpty()) "" else "：$hint"
        }
    }
}

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
    /** AMap (accurate for mainland IPs) when a key is set, otherwise ipwho.is (worldwide, keyless). */
    fun lookup(amap: Amap): IpResult {
        if (amap.available) amap.ip()?.let { return it }
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
 * Snaps route sections to real roads: AMap route planning with a key (better road data in China),
 * otherwise the public OSRM servers of FOSSGIS (OpenStreetMap). Coordinates in and out are WGS-84.
 */
class RoadRouter(private val amap: Amap) {
    val source get() = if (amap.available) "高德" else "OpenStreetMap"
    private val cache = object : LinkedHashMap<String, List<LatLon>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LatLon>>) = size > 200
    }
    private var lastAmap = 0L

    /** Road path from a to b, starting at a and ending at b. Blocking. */
    fun section(profile: RoadProfile, a: LatLon, b: LatLon): List<LatLon> {
        if (!profile.road) return listOf(a, b)
        val useAmap = amap.available
        val key = String.format(Locale.ROOT, "%s|%s|%.6f,%.6f|%.6f,%.6f", if (useAmap) "amap" else "osrm", profile.name, a.lat, a.lon, b.lat, b.lon)
        synchronized(cache) { cache[key]?.let { return it } }
        val road = if (useAmap) {
            try { viaAmap(profile, a, b) } catch (e: Amap.AmapError) {
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

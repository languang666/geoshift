package app.geoshift.data

import android.content.Context
import app.geoshift.Geo
import app.geoshift.MapNetwork
import app.geoshift.PortalStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** How a route section between two tapped points is drawn. */
enum class RoadProfile(val id: String, val label: String) {
    STRAIGHT("straight", "直线"),
    FOOT("foot", "步行"),
    BIKE("bike", "骑行"),
    CAR("car", "驾车");

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id } ?: STRAIGHT
    }
}

/**
 * Snaps route sections to real roads. Uses AMap route planning when a Web service key is set
 * (better road data in China), otherwise the public OSRM servers of FOSSGIS (OpenStreetMap data).
 * Coordinates in and out are WGS-84.
 */
class RoadRouter(private val context: Context) {
    val source: String get() = if (PortalStore.mapKey(context).isNotEmpty()) "高德" else "OpenStreetMap"

    private val cache = HashMap<String, List<LatLon>>()
    private var lastAmapRequest = 0L

    /** Road path from a to b, starting at a and ending at b. Blocking; call off the main thread. */
    fun section(profile: RoadProfile, a: LatLon, b: LatLon): List<LatLon> {
        if (profile == RoadProfile.STRAIGHT) return listOf(a, b)
        if (!PortalStore.settings(context).optBoolean("privacyAccepted"))
            throw PortalException("沿道路规划需要联网，请先在设置中同意隐私说明。")
        val amap = PortalStore.mapKey(context).isNotEmpty()
        val key = String.format(Locale.ROOT, "%s|%s|%.6f,%.6f|%.6f,%.6f", if (amap) "amap" else "osrm", profile.id, a.lat, a.lon, b.lat, b.lon)
        synchronized(cache) { cache[key]?.let { return it } }
        val road = if (amap) amap(profile, a, b) else osrm(profile, a, b)
        // Join the tapped points to where the road begins and ends, so sections connect exactly.
        val path = ArrayList<LatLon>(road.size + 2)
        path.add(a)
        for (p in road) if (Geo.distance(path.last().lat, path.last().lon, p.lat, p.lon) > 0.5) path.add(p)
        if (Geo.distance(path.last().lat, path.last().lon, b.lat, b.lon) > 0.5) path.add(b) else path[path.lastIndex] = b
        synchronized(cache) { cache[key] = path }
        return path
    }

    private fun amap(profile: RoadProfile, a: LatLon, b: LatLon): List<LatLon> {
        // Personal AMap keys allow only a few requests per second.
        synchronized(this) {
            val wait = lastAmapRequest + 350 - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastAmapRequest = System.currentTimeMillis()
        }
        return try {
            MapNetwork.direction(context, profile.id, a.lat, a.lon, b.lat, b.lon).map { LatLon(it[0], it[1]) }
        } catch (e: PortalException) {
            throw e
        } catch (e: Exception) {
            throw PortalException(e.message ?: "高德路径规划失败。")
        }
    }

    private fun osrm(profile: RoadProfile, a: LatLon, b: LatLon): List<LatLon> {
        val url = String.format(Locale.ROOT,
            "https://routing.openstreetmap.de/routed-%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
            profile.id, a.lon, a.lat, b.lon, b.lat)
        val body = try {
            fetch(url)
        } catch (e: PortalException) {
            throw e
        } catch (e: Exception) {
            throw PortalException("道路服务连接失败（${e.javaClass.simpleName}），请检查网络后重试。")
        }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw PortalException("道路服务返回了无法识别的内容。")
        if (json.optString("code") != "Ok") {
            throw PortalException(if (json.optString("code") == "NoRoute") "这两点之间没有找到道路，可改用直线连接。"
                else "道路服务错误：${json.optString("message", json.optString("code"))}")
        }
        val coords = json.optJSONArray("routes")?.optJSONObject(0)?.optJSONObject("geometry")?.optJSONArray("coordinates")
            ?: throw PortalException("这两点之间没有找到道路，可改用直线连接。")
        return (0 until coords.length()).mapNotNull { i ->
            val c = coords.optJSONArray(i) ?: return@mapNotNull null
            LatLon(c.optDouble(1), c.optDouble(0)).takeIf { Geo.valid(it.lat, it.lon) }
        }
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "Geoshift/${PortalStore.VERSION} (Android; app.geoshift)")
        try {
            val code = connection.responseCode
            // OSRM answers "no route" with HTTP 400 and a JSON body.
            val stream = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?: throw PortalException("道路服务 HTTP $code，请稍后重试。")
            stream.use {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_RESPONSE_BYTES) throw PortalException("道路服务响应过大，请把路线分成更短的几段。")
                    out.write(buffer, 0, n)
                }
                if (code !in 200..299 && code != 400) throw PortalException("道路服务 HTTP $code，请稍后重试。")
                return out.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val MAX_POINTS = 10000

        /** Joins sections end to start and thins the result to the stored route limit. */
        fun join(sections: List<List<LatLon>>): List<LatLon> {
            val out = ArrayList<LatLon>()
            for (s in sections) for (p in s) if (out.isEmpty() || out.last() != p) out.add(p)
            if (out.size <= MAX_POINTS) return out
            val step = (out.size - 1).toDouble() / (MAX_POINTS - 1)
            return (0 until MAX_POINTS).map { out[Math.round(it * step).toInt().coerceAtMost(out.lastIndex)] }
        }
    }
}

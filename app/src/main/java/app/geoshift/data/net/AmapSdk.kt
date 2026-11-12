package app.geoshift.data.net

import android.content.Context
import android.os.Build
import app.geoshift.core.Geo
import app.geoshift.core.LatLon
import app.geoshift.data.Place
import app.geoshift.data.RoadProfile
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.route.RouteSearchV2
import java.io.File

/**
 * The official AMap SDK (map + search), driven by the user's own Android-platform key. Nothing of
 * the SDK is touched until a key is saved: saving it is also the user's consent to the SDK's
 * privacy terms. Coordinates in and out are WGS-84; AMap works in GCJ-02.
 */
class AmapSdk(context: Context, private val key: () -> String) {
    class AmapError(val code: String, message: String) : Exception(message) {
        /** The point lies outside AMap's coverage (abroad). */
        val outOfCoverage get() = code == OUT_OF_SERVICE
    }

    private val app = context.applicationContext
    private var configuredKey: String? = null

    /** A key is saved (whether it authenticates is only known when AMap answers). */
    val ready get() = key().isNotEmpty()

    /** The map SDK ships ARM code only; on other devices the raster AMap layer stays in use. */
    val mapSupported get() = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    /** Applies consent and the current key to both SDK parts; cheap when nothing changed. */
    @Synchronized fun configure(): Boolean {
        val k = key()
        if (k.isEmpty()) return false
        if (k == configuredKey) return true
        MapsInitializer.updatePrivacyShow(app, true, true)
        MapsInitializer.updatePrivacyAgree(app, true)
        MapsInitializer.setApiKey(k)
        MapsInitializer.setProtocol(MapsInitializer.HTTPS)
        // Offline map data and tile cache (not setExternalBasePath: that one moves the SDK's own resources).
        MapsInitializer.sdcardDir = File(app.cacheDir, "amap").apply { mkdirs() }.absolutePath
        ServiceSettings.updatePrivacyShow(app, true, true)
        ServiceSettings.updatePrivacyAgree(app, true)
        ServiceSettings.getInstance().apply {
            setApiKey(k)
            setProtocol(ServiceSettings.HTTPS)
            setLanguage(ServiceSettings.CHINESE)
            connectionTimeOut = 8000
            soTimeOut = 15000
        }
        configuredKey = k
        return true
    }

    private fun <T> call(block: () -> T): T {
        configure()
        try {
            return block()
        } catch (e: AMapException) {
            throw AmapError(e.errorCode.toString(), explain(e.errorCode, e.errorMessage.orEmpty()))
        }
    }

    private fun LatLon.gcj() = Geo.wgsToGcj(this).let { LatLonPoint(it.lat, it.lon) }
    private fun LatLonPoint.wgs() = Geo.gcjToWgs(LatLon(latitude, longitude))

    /** Input tips near [near] (nearest city first), like AMap's own search box. */
    fun search(query: String, near: LatLon?): List<Place> = call {
        val q = InputtipsQuery(query, "").apply { near?.let { setLocation(it.gcj()) } }
        Inputtips(app, q).requestInputtips().orEmpty().mapNotNull { tip ->
            val p = tip.point ?: return@mapNotNull null
            val wgs = p.wgs().takeIf { it.valid } ?: return@mapNotNull null
            Place(tip.name.orEmpty(), tip.district.orEmpty() + tip.address.orEmpty(), wgs)
        }
    }

    fun reverse(p: LatLon): String = call {
        GeocodeSearch(app).getFromLocation(RegeocodeQuery(p.gcj(), 200f, GeocodeSearch.AMAP))?.formatAddress.orEmpty()
    }

    /** Road path a → b (WGS-84). */
    fun direction(profile: RoadProfile, a: LatLon, b: LatLon): List<LatLon> = call {
        val search = RouteSearchV2(app)
        val od = RouteSearchV2.FromAndTo(a.gcj(), b.gcj())
        val fields = RouteSearchV2.ShowFields.POLINE
        val lines: List<List<LatLonPoint>> = when (profile) {
            RoadProfile.CAR -> search.calculateDriveRoute(
                RouteSearchV2.DriveRouteQuery(od, RouteSearchV2.DrivingStrategy.DEFAULT, null, null, "").apply { showFields = fields })
                ?.paths?.firstOrNull()?.steps.orEmpty().map { it.polyline.orEmpty() }
            RoadProfile.BIKE -> search.calculateRideRoute(RouteSearchV2.RideRouteQuery(od).apply { showFields = fields })
                ?.paths?.firstOrNull()?.steps.orEmpty().map { it.polyline.orEmpty() }
            else -> search.calculateWalkRoute(RouteSearchV2.WalkRouteQuery(od).apply { showFields = fields })
                ?.paths?.firstOrNull()?.steps.orEmpty().map { it.polyline.orEmpty() }
        }
        val out = lines.flatten().map { it.wgs() }.filter { it.valid }
        if (out.size < 2) throw AmapError("3002", "高德没有找到这两点之间的道路，可改用直线连接。")
        out
    }

    companion object {
        /** Out-of-coverage codes: AMap has no data there (abroad). */
        const val OUT_OF_SERVICE = "3000"

        fun explain(code: Int, info: String): String {
            val hint = when (code) {
                1001, 1008 -> "Key 与本应用的包名或 SHA1 不匹配：请在「高德 Key」页复制包名和 SHA1，在高德控制台核对后重新保存。"
                1002 -> "Key 不正确或已过期，请检查是否完整复制。"
                1003 -> "此 Key 没有开通该服务。"
                1004 -> "今日调用次数已用完，明天会恢复。"
                1005, 1010 -> "请求太频繁，请稍后再试。"
                1009 -> "Key 平台不匹配：请创建「Android 平台」的 Key，不是 Web 服务或 Web 端。"
                1012 -> "此 Key 没有该接口的权限。"
                1013 -> "此 Key 已在高德控制台删除，请重新创建。"
                1802, 1804, 1806, 1102, 1103 -> "网络连接失败，请检查网络后重试。"
                3000 -> "该地点不在中国境内，高德无法规划。"
                3001 -> "起点或终点附近没有道路，请把点放到路上。"
                3002 -> "两点之间找不到可通行的路线。"
                3003 -> "两点距离太远，请分段添加途经点。"
                else -> ""
            }
            return "高德返回错误 $code（$info）" + if (hint.isEmpty()) "" else "：$hint"
        }
    }
}

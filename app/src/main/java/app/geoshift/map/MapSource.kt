package app.geoshift.map

import app.geoshift.core.Geo
import app.geoshift.core.LatLon

/** Map layers. Coordinates are converted only for display; everything stored stays WGS-84. */
enum class MapSource(val label: String, val menuLabel: String, val attribution: String) {
    OSM("默认地图", "全球地图 · OpenStreetMap", "© OpenStreetMap contributors"),
    VECTOR("矢量地图", "矢量地图 · 清晰可旋转（OpenFreeMap）", "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"),
    AMAP("高德地图", "高德地图 · 国内道路更全", "© 高德地图"),
    SATELLITE("全球卫星影像", "全球卫星影像 · Esri", "Sources: Esri, Maxar, Earthstar Geographics, GIS User Community");

    /** AMap tiles are drawn in GCJ-02. */
    fun toDisplay(p: LatLon): LatLon = if (this == AMAP) Geo.wgsToGcj(p) else p
    fun toWgs(p: LatLon): LatLon = if (this == AMAP) Geo.gcjToWgs(p) else p
}

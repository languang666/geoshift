package app.geoshift.ui.map

import android.content.Context
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.geoshift.map.MapSource
import app.geoshift.core.LatLon
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Vector tiles from OpenFreeMap: crisp labels, smooth zoom and rotation, no key. WGS-84 like OSM. */
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
/** Shown before online maps are allowed: no network requests at all. */
private const val OFFLINE_STYLE = """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#E9ECF2"}}]}"""

private fun amapUrls(query: String) = (1..4).joinToString(",") {
    "\"https://wprd0$it.is.autonavi.com/appmaptile?lang=zh_cn&$query&x={x}&y={y}&z={z}\""
}

/** Inverts a light raster map for dark mode while keeping its hues (as the osmdroid layer does). */
private fun darkRaster(dark: Boolean) = if (!dark) "{}" else
    """{"raster-brightness-min":0.92,"raster-brightness-max":0.08,"raster-hue-rotate":180,"raster-saturation":-0.6,"raster-contrast":-0.1}"""

/**
 * AMap road map, sharp on dense screens: tiles of the next zoom level drawn at 128 dp (what AMap's
 * own web map does on high-density screens), instead of 256-pixel tiles stretched 2-3x. GCJ-02.
 */
private fun amapStyle(dark: Boolean) = """{"version":8,
  "sources":{"amap":{"type":"raster","tiles":[${amapUrls("size=1&scl=1&style=7")}],"tileSize":128,"minzoom":3,"maxzoom":19}},
  "layers":[
    {"id":"bg","type":"background","paint":{"background-color":"${if (dark) "#1B1E27" else "#F5F3EE"}"}},
    {"id":"amap","type":"raster","source":"amap","paint":${darkRaster(dark)}}]}"""

/** Esri World Imagery, next zoom level drawn at 128 dp: twice the detail of plain 256-pixel tiles. */
private const val SATELLITE_STYLE = """{"version":8,
  "sources":{"esri":{"type":"raster","tiles":["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],"tileSize":128,"minzoom":3,"maxzoom":19}},
  "layers":[{"id":"bg","type":"background","paint":{"background-color":"#1B1E27"}},{"id":"esri","type":"raster","source":"esri"}]}"""

// MapLibre zoom counts 512-pixel tiles, osmdroid 256-pixel tiles: one level apart for the same scale.
private fun toVectorZoom(zoom: Double) = zoom - 1
private fun fromVectorZoom(zoom: Double) = zoom + 1

@Composable
fun VectorMap(
    modifier: Modifier,
    virtual: LatLon,
    simulating: Boolean,
    real: LatLon?,
    selected: LatLon?,
    route: List<LatLon>,
    routeEditing: Boolean,
    anchors: List<LatLon>,
    online: Boolean,
    dark: Boolean,
    source: MapSource,
    camera: MapCamera,
    center: CenterRequest?,
    onBearingChanged: (Double) -> Unit = {},
    onTap: (LatLon) -> Unit,
    onLongPress: (LatLon) -> Unit,
) {
    val context = LocalContext.current
    val tap = rememberUpdatedState(onTap)
    val longPress = rememberUpdatedState(onLongPress)
    val bearingChanged = rememberUpdatedState(onBearingChanged)
    val layers = remember {
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
        VectorLayers(context, MapView(context, options).apply { onCreate(null) }, camera,
            onBearingChanged = { bearingChanged.value(it) },
            onTap = { tap.value(it) }, onLongPress = { longPress.value(it) })
    }
    val view = layers.view
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle) {
        var started = false
        var resumed = false
        fun start() { if (!started) { view.onStart(); started = true } }
        fun resume() { start(); if (!resumed) { view.onResume(); resumed = true } }
        fun pause() { if (resumed) { view.onPause(); resumed = false } }
        fun stop() { pause(); if (started) { view.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
            view.onDestroy()
        }
    }
    LaunchedEffect(center, layers) {
        val request = center ?: return@LaunchedEffect
        if (camera.handledToken == request.token) return@LaunchedEffect
        camera.handledToken = request.token
        val zoom = request.zoom ?: maxOf(camera.zoom, 15.0)
        camera.target = request.target
        camera.zoom = zoom
        layers.animateTo(request.target, zoom, request.bearing)
    }
    LaunchedEffect(online, dark, source, layers) {
        MapLibre.setConnected(online)
        val spec = when {
            !online -> StyleSpec(null, OFFLINE_STYLE)
            source == MapSource.AMAP -> StyleSpec(null, amapStyle(dark))
            source == MapSource.SATELLITE -> StyleSpec(null, SATELLITE_STYLE)
            else -> StyleSpec(if (dark) DARK_STYLE else LIGHT_STYLE, null)
        }
        layers.style(spec, if (online) source else MapSource.VECTOR)
    }
    LaunchedEffect(route, routeEditing, anchors, layers) { layers.route(route, routeEditing, anchors) }
    AndroidView(factory = { view }, modifier = modifier) {
        layers.markers(virtual, simulating, real, selected)
    }
}

/** A style given by URL or by inline JSON. */
private data class StyleSpec(val url: String?, val json: String?)

private class VectorLayers(
    context: Context,
    val view: MapView,
    private val camera: MapCamera,
    private val onBearingChanged: (Double) -> Unit,
    private val onTap: (LatLon) -> Unit,
    private val onLongPress: (LatLon) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val idleIcon = simMarker(context, false).bitmap
    private val activeIcon = simMarker(context, true).bitmap
    private val realIcon = realMarker(context).bitmap
    private val pinIcon = pinDrawable(context).bitmap
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var spec: StyleSpec? = null
    private var pendingSource = MapSource.VECTOR
    /** Coordinate system of the current style: GCJ-02 for AMap, WGS-84 otherwise. Stored data stays WGS-84. */
    private var source = MapSource.VECTOR

    private fun LatLon.latLng() = source.toDisplay(this).let { LatLng(it.lat, it.lon) }
    private fun LatLon.point(): Point = source.toDisplay(this).let { Point.fromLngLat(it.lon, it.lat) }
    private fun LatLng.stored(): LatLon = source.toWgs(LatLon(latitude, longitude))

    // Latest data, re-applied whenever a style finishes loading.
    private var virtual: LatLon = camera.target
    private var simulating = false
    private var real: LatLon? = null
    private var selected: LatLon? = null
    private var routePoints: List<LatLon> = emptyList()
    private var editing = false
    private var anchors: List<LatLon> = emptyList()

    init {
        view.getMapAsync { m ->
            map = m
            m.setMinZoomPreference(2.0)
            m.setMaxZoomPreference(19.0)
            m.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false // credited by the text overlay of the map screen
                isCompassEnabled = false // handled by native Compose compass button in MapScreen
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = true
            }
            m.cameraPosition = CameraPosition.Builder().target(camera.target.latLng()).zoom(toVectorZoom(camera.zoom)).build()
            m.addOnCameraMoveListener {
                if (switching) return@addOnCameraMoveListener
                val p = m.cameraPosition
                p.target?.let { camera.target = it.stored() }
                camera.zoom = fromVectorZoom(p.zoom)
                onBearingChanged(p.bearing)
            }
            m.addOnMapClickListener { onTap(it.stored()); true }
            m.addOnMapLongClickListener { onLongPress(it.stored()); true }
            val pending = spec
            spec = null
            if (pending != null) style(pending, pendingSource)
        }
    }

    private var switching = false

    fun style(next: StyleSpec, nextSource: MapSource) {
        if (next == spec) return
        spec = next
        pendingSource = nextSource
        val m = map ?: return
        style = null
        if (nextSource != source) {
            // Same place on the ground, expressed in the new style's coordinate system.
            source = nextSource
            switching = true
            m.moveCamera(CameraUpdateFactory.newLatLng(camera.target.latLng()))
            switching = false
        }
        val builder = if (next.url != null) Style.Builder().fromUri(next.url) else Style.Builder().fromJson(next.json!!)
        m.setStyle(builder) { loaded ->
            if (spec != next) return@setStyle
            chineseLabels(loaded)
            addOverlay(loaded)
            style = loaded
            pushRoute()
            pushMarkers()
        }
    }

    fun animateTo(target: LatLon, zoom: Double, bearing: Double? = null) {
        val m = map ?: return
        val currentBearing = m.cameraPosition.bearing
        val pos = CameraPosition.Builder().target(target.latLng()).zoom(toVectorZoom(zoom)).bearing(bearing ?: currentBearing).build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 500)
    }

    fun route(points: List<LatLon>, editing: Boolean, anchors: List<LatLon>) {
        routePoints = points
        this.editing = editing
        this.anchors = anchors
        pushRoute()
    }

    fun markers(virtual: LatLon, simulating: Boolean, real: LatLon?, selected: LatLon?) {
        if (virtual == this.virtual && simulating == this.simulating && real == this.real && selected == this.selected && style != null) return
        this.virtual = virtual
        this.simulating = simulating
        this.real = real
        this.selected = selected
        pushMarkers()
    }

    /** OpenMapTiles carry names in many languages; show the Chinese one where it exists. */
    private fun chineseLabels(style: Style) {
        val name = Expression.toString(Expression.coalesce(
            Expression.get("name:zh-Hans"), Expression.get("name:zh"), Expression.get("name:nonlatin"), Expression.get("name"), Expression.literal("")))
        for (layer in style.layers) {
            if (layer !is SymbolLayer) continue
            val field = runCatching { layer.textField.expression?.toString() }.getOrNull() ?: continue
            if ("name" in field) layer.setProperties(PropertyFactory.textField(name))
        }
    }

    private fun addOverlay(style: Style) {
        style.addImage(IMG_IDLE, idleIcon)
        style.addImage(IMG_ACTIVE, activeIcon)
        style.addImage(IMG_PIN, pinIcon)
        style.addImage(IMG_REAL, realIcon)
        for (id in listOf(SRC_ROUTE, SRC_VERTICES, SRC_ENDS, SRC_REAL, SRC_VIRTUAL, SRC_SELECTED)) style.addSource(GeoJsonSource(id))
        // The route line goes under the map's labels so street names stay readable; markers go on top.
        val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
        fun addLine(layer: LineLayer) = if (firstLabel != null) style.addLayerBelow(layer, firstLabel) else style.addLayer(layer)
        addLine(LineLayer("gs-route-casing", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(8f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        addLine(LineLayer("gs-route", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor("#5266E8"), PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        style.addLayer(CircleLayer("gs-vertices", SRC_VERTICES).withProperties(
            PropertyFactory.circleRadius(4.5f), PropertyFactory.circleColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f), PropertyFactory.circleStrokeColor("#5266E8")))
        style.addLayer(CircleLayer("gs-ends", SRC_ENDS).withProperties(
            PropertyFactory.circleRadius(5f), PropertyFactory.circleStrokeWidth(2f), PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleColor(Expression.match(Expression.get("kind"), Expression.literal("#E5484D"),
                Expression.stop("start", Expression.literal("#1FA971"))))))
        style.addLayer(SymbolLayer("gs-real", SRC_REAL).withProperties(
            PropertyFactory.iconImage(IMG_REAL), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        style.addLayer(SymbolLayer("gs-virtual", SRC_VIRTUAL).withProperties(
            PropertyFactory.iconImage(IMG_IDLE), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        style.addLayer(SymbolLayer("gs-selected", SRC_SELECTED).withProperties(
            PropertyFactory.iconImage(IMG_PIN), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
    }

    private fun source(id: String): GeoJsonSource? = style?.getSourceAs(id)

    private fun pushRoute() {
        val points = routePoints
        source(SRC_ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(
            if (points.size >= 2) listOf(Feature.fromGeometry(LineString.fromLngLats(points.map { it.point() }))) else emptyList()))
        val vertices = when {
            anchors.isNotEmpty() -> anchors
            editing || points.size <= 60 -> points
            else -> emptyList()
        }
        source(SRC_VERTICES)?.setGeoJson(FeatureCollection.fromFeatures(vertices.map { Feature.fromGeometry(it.point()) }))
        val ends = buildList {
            points.firstOrNull()?.let { add(Feature.fromGeometry(it.point()).apply { addStringProperty("kind", "start") }) }
            if (points.size > 1) add(Feature.fromGeometry(points.last().point()).apply { addStringProperty("kind", "end") })
        }
        source(SRC_ENDS)?.setGeoJson(FeatureCollection.fromFeatures(ends))
    }

    private fun pushMarkers() {
        val s = style ?: return
        source(SRC_VIRTUAL)?.setGeoJson(Feature.fromGeometry(virtual.point()))
        s.getLayerAs<SymbolLayer>("gs-virtual")?.setProperties(PropertyFactory.iconImage(if (simulating) IMG_ACTIVE else IMG_IDLE))
        source(SRC_SELECTED)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(selected?.let { Feature.fromGeometry(it.point()) })))
        source(SRC_REAL)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(real?.let { Feature.fromGeometry(it.point()) })))
    }

    companion object {
        private const val SRC_ROUTE = "gs-route-src"
        private const val SRC_VERTICES = "gs-vertices-src"
        private const val SRC_ENDS = "gs-ends-src"
        private const val SRC_VIRTUAL = "gs-virtual-src"
        private const val SRC_SELECTED = "gs-selected-src"
        private const val SRC_REAL = "gs-real-src"
        private const val IMG_REAL = "gs-real"
        private const val IMG_IDLE = "gs-dot-idle"
        private const val IMG_ACTIVE = "gs-dot-active"
        private const val IMG_PIN = "gs-pin"
    }
}

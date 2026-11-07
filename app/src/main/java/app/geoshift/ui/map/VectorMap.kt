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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.geoshift.data.LatLon
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

// MapLibre zoom counts 512-pixel tiles, osmdroid 256-pixel tiles: one level apart for the same scale.
private fun toVectorZoom(zoom: Double) = zoom - 1
private fun fromVectorZoom(zoom: Double) = zoom + 1
private fun LatLon.latLng() = LatLng(lat, lon)
private fun LatLon.point(): Point = Point.fromLngLat(lon, lat)

@Composable
fun VectorMap(
    modifier: Modifier,
    virtual: LatLon,
    simulating: Boolean,
    selected: LatLon?,
    route: List<LatLon>,
    routeEditing: Boolean,
    anchors: List<LatLon>,
    online: Boolean,
    dark: Boolean,
    camera: MapCamera,
    center: CenterRequest?,
    onTap: (LatLon) -> Unit,
    onLongPress: (LatLon) -> Unit,
) {
    val context = LocalContext.current
    val tap = rememberUpdatedState(onTap)
    val longPress = rememberUpdatedState(onLongPress)
    val layers = remember {
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
        VectorLayers(context, MapView(context, options).apply { onCreate(null) }, camera,
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
        layers.animateTo(request.target, zoom)
    }
    LaunchedEffect(online, dark, layers) {
        MapLibre.setConnected(online)
        layers.style(if (!online) null else if (dark) DARK_STYLE else LIGHT_STYLE)
    }
    LaunchedEffect(route, routeEditing, anchors, layers) { layers.route(route, routeEditing, anchors) }
    AndroidView(factory = { view }, modifier = modifier) {
        layers.markers(virtual, simulating, selected)
    }
}

private class VectorLayers(
    context: Context,
    val view: MapView,
    private val camera: MapCamera,
    private val onTap: (LatLon) -> Unit,
    private val onLongPress: (LatLon) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val idleIcon = dotDrawable(context, false).bitmap
    private val activeIcon = dotDrawable(context, true).bitmap
    private val pinIcon = pinDrawable(context).bitmap
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleUrl: String? = "unset"

    // Latest data, re-applied whenever a style finishes loading.
    private var virtual: LatLon = camera.target
    private var simulating = false
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
                isCompassEnabled = true
                compassGravity = Gravity.TOP or Gravity.START
                setCompassMargins((16 * density).toInt(), (112 * density).toInt(), 0, 0)
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = true
            }
            m.cameraPosition = CameraPosition.Builder().target(camera.target.latLng()).zoom(toVectorZoom(camera.zoom)).build()
            m.addOnCameraMoveListener {
                val p = m.cameraPosition
                p.target?.let { camera.target = LatLon(it.latitude, it.longitude) }
                camera.zoom = fromVectorZoom(p.zoom)
            }
            m.addOnMapClickListener { onTap(LatLon(it.latitude, it.longitude)); true }
            m.addOnMapLongClickListener { onLongPress(LatLon(it.latitude, it.longitude)); true }
            val pending = styleUrl
            styleUrl = "unset"
            if (pending != "unset") style(pending)
        }
    }

    fun style(url: String?) {
        if (url == styleUrl) return
        styleUrl = url
        val m = map ?: return
        style = null
        val builder = if (url == null) Style.Builder().fromJson(OFFLINE_STYLE) else Style.Builder().fromUri(url)
        m.setStyle(builder) { loaded ->
            if (styleUrl != url) return@setStyle
            chineseLabels(loaded)
            addOverlay(loaded)
            style = loaded
            pushRoute()
            pushMarkers()
        }
    }

    fun animateTo(target: LatLon, zoom: Double) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(target.latLng(), toVectorZoom(zoom)), 500)
    }

    fun route(points: List<LatLon>, editing: Boolean, anchors: List<LatLon>) {
        routePoints = points
        this.editing = editing
        this.anchors = anchors
        pushRoute()
    }

    fun markers(virtual: LatLon, simulating: Boolean, selected: LatLon?) {
        if (virtual == this.virtual && simulating == this.simulating && selected == this.selected && style != null) return
        this.virtual = virtual
        this.simulating = simulating
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
        for (id in listOf(SRC_ROUTE, SRC_VERTICES, SRC_ENDS, SRC_VIRTUAL, SRC_SELECTED)) style.addSource(GeoJsonSource(id))
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
    }

    companion object {
        private const val SRC_ROUTE = "gs-route-src"
        private const val SRC_VERTICES = "gs-vertices-src"
        private const val SRC_ENDS = "gs-ends-src"
        private const val SRC_VIRTUAL = "gs-virtual-src"
        private const val SRC_SELECTED = "gs-selected-src"
        private const val IMG_IDLE = "gs-dot-idle"
        private const val IMG_ACTIVE = "gs-dot-active"
        private const val IMG_PIN = "gs-pin"
    }
}

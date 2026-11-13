package app.geoshift.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.geoshift.core.LatLon
import app.geoshift.data.net.AmapSdk
import app.geoshift.map.MapSource
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import kotlinx.coroutines.delay

private fun LatLon.amap(): LatLng = MapSource.AMAP.toDisplay(this).let { LatLng(it.lat, it.lon) }
private fun LatLng.stored(): LatLon = MapSource.AMAP.toWgs(LatLon(latitude, longitude))

/**
 * The official AMap map (vector, rotatable, 3D buildings). Only created once the user saved an
 * Android key; [onFailure] reports a map that cannot be created (the caller falls back to tiles),
 * [onSlow] one that shows no map data after a while (usually a key that does not authenticate).
 */
@Composable
fun AmapMap(
    modifier: Modifier,
    sdk: AmapSdk,
    keyRevision: Any,
    virtual: LatLon,
    simulating: Boolean,
    real: LatLon?,
    selected: LatLon?,
    route: List<LatLon>,
    routeEditing: Boolean,
    anchors: List<LatLon>,
    dark: Boolean,
    camera: MapCamera,
    center: CenterRequest?,
    logoBottomMargin: Int,
    onBearingChanged: (Double) -> Unit,
    onTap: (LatLon) -> Unit,
    onLongPress: (LatLon) -> Unit,
    onFailure: (String) -> Unit,
    onSlow: () -> Unit,
) {
    val context = LocalContext.current
    val tap = rememberUpdatedState(onTap)
    val longPress = rememberUpdatedState(onLongPress)
    val bearingChanged = rememberUpdatedState(onBearingChanged)
    val result = remember(keyRevision) {
        var created: TextureMapView? = null
        runCatching {
            check(sdk.mapSupported) { "此设备的处理器架构不受高德地图 SDK 支持" }
            check(sdk.configure()) { "未设置高德 Key" }
            MapsInitializer.initialize(context.applicationContext)
            val view = TextureMapView(context).also { created = it }.apply { onCreate(null) }
            NativeLayers(context, view, camera, onBearingChanged = { bearingChanged.value(it) }).apply {
                map.setOnMapClickListener { tap.value(it.stored()) }
                map.setOnMapLongClickListener { longPress.value(it.stored()) }
                map.setOnPOIClickListener { tap.value(it.coordinate.stored()) }
                map.setOnMapLoadedListener { onMapReady(); markLoaded() }
            }
        }.onFailure { runCatching { created?.onDestroy() } }
    }
    val layers = result.getOrNull()
    if (layers == null) {
        LaunchedEffect(result) { onFailure(result.exceptionOrNull()?.message ?: "高德地图加载失败") }
        return
    }
    val view = layers.view
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle) {
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!resumed) { view.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { view.onPause(); resumed = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !resumed) {
            view.onResume(); resumed = true
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) view.onPause()
            view.onDestroy()
        }
    }
    LaunchedEffect(layers) {
        // Only a hint: the key itself is checked separately, and a slow network is no reason to
        // take the official map away (time spent in the background does not count).
        var waited = 0
        while (waited < 20_000 && !layers.dataLoaded) {
            delay(500)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) waited += 500
        }
        if (!layers.dataLoaded) onSlow()
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
    LaunchedEffect(route, routeEditing, anchors, layers) { layers.route(route, routeEditing, anchors) }
    AndroidView(factory = { view }, modifier = modifier) {
        layers.map.uiSettings.setLogoBottomMargin(logoBottomMargin)
        val type = if (dark) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL
        if (layers.map.mapType != type) layers.map.mapType = type
        layers.markers(virtual, simulating, real, selected)
    }
}

private class NativeLayers(
    context: Context,
    val view: TextureMapView,
    private val camera: MapCamera,
    private val onBearingChanged: (Double) -> Unit,
) {
    val map: AMap = view.map
    private val density = context.resources.displayMetrics.density
    private val idleIcon = BitmapDescriptorFactory.fromBitmap(simMarker(context, false).bitmap)
    private val activeIcon = BitmapDescriptorFactory.fromBitmap(simMarker(context, true).bitmap)
    private val realIcon = BitmapDescriptorFactory.fromBitmap(realMarker(context).bitmap)
    private val pinIcon = BitmapDescriptorFactory.fromBitmap(pinDrawable(context).bitmap)
    private val vertexIcon = BitmapDescriptorFactory.fromBitmap(circle(10, 0xFFFFFFFF.toInt(), 0xFF5266E8.toInt()))
    private val startIcon = BitmapDescriptorFactory.fromBitmap(circle(12, 0xFF1FA971.toInt(), 0xFFFFFFFF.toInt()))
    private val endIcon = BitmapDescriptorFactory.fromBitmap(circle(12, 0xFFE5484D.toInt(), 0xFFFFFFFF.toInt()))
    private val virtual: Marker
    private val real: Marker
    private val selected: Marker
    private val casing: Polyline = map.addPolyline(PolylineOptions().color(0xFFFFFFFF.toInt()).width(8 * density).zIndex(1f))
    private val line: Polyline = map.addPolyline(PolylineOptions().color(0xFF5266E8.toInt()).width(5 * density).zIndex(2f))
    private val vertices = mutableListOf<Marker>()
    private var ready = false
    @Volatile var dataLoaded = false
        private set

    /** The map finished its first load (cached data may never pass the tile callback). */
    fun markLoaded() { dataLoaded = true }
    private var lastRoute: List<LatLon> = emptyList()
    private var lastEditing = false
    private var lastAnchors: List<LatLon> = emptyList()
    private var lastVirtual: LatLon? = null
    private var lastSimulating: Boolean? = null
    private var lastSelected: LatLon? = null
    private var lastReal: LatLon? = null

    init {
        map.isMyLocationEnabled = false
        map.showBuildings(true)
        map.showIndoorMap(false)
        map.setMinZoomLevel(3f)
        map.setMaxZoomLevel(20f)
        map.uiSettings.apply {
            isZoomControlsEnabled = false
            isMyLocationButtonEnabled = false
            isCompassEnabled = false // the map screen has its own compass
            isScaleControlsEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
        }
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(camera.target.amap(), camera.zoom.toFloat()))
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition) {
                if (!ready) return
                camera.target = position.target.stored()
                camera.zoom = position.zoom.toDouble()
                onBearingChanged(position.bearing.toDouble())
            }
            override fun onCameraChangeFinish(position: CameraPosition) = onCameraChange(position)
        })
        map.setTileLoadCallback { success, _, _ -> if (success && !dataLoaded) dataLoaded = true }
        virtual = map.addMarker(MarkerOptions().position(camera.target.amap()).icon(idleIcon)
            .anchor(0.5f, 0.5f).infoWindowEnable(false).zIndex(20f))
        real = map.addMarker(MarkerOptions().position(camera.target.amap()).icon(realIcon)
            .anchor(0.5f, 0.5f).infoWindowEnable(false).zIndex(15f).visible(false))
        selected = map.addMarker(MarkerOptions().position(camera.target.amap()).icon(pinIcon)
            .anchor(0.5f, 1f).infoWindowEnable(false).zIndex(30f).visible(false))
        map.setOnMarkerClickListener { true }
    }

    private fun circle(sizeDp: Int, fill: Int, stroke: Int): Bitmap {
        val size = (sizeDp * density).toInt().coerceAtLeast(4)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bitmap)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.color = stroke
        c.drawCircle(r, r, r, p)
        p.color = fill
        c.drawCircle(r, r, r - 2.2f * density, p)
        return bitmap
    }

    fun onMapReady() {
        ready = true
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(camera.target.amap(), camera.zoom.toFloat()))
        Handler(Looper.getMainLooper()).post { route(lastRoute, lastEditing, lastAnchors) }
    }

    fun animateTo(target: LatLon, zoom: Double, bearing: Double?) {
        val current = map.cameraPosition
        val pos = CameraPosition(target.amap(), zoom.toFloat(), current?.tilt ?: 0f, bearing?.toFloat() ?: current?.bearing ?: 0f)
        map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 500L, null)
    }

    fun markers(virtual: LatLon, simulating: Boolean, real: LatLon?, selected: LatLon?) {
        if (real != lastReal) {
            this.real.isVisible = real != null
            real?.let { this.real.position = it.amap() }
            lastReal = real
        }
        if (virtual != lastVirtual) { this.virtual.position = virtual.amap(); lastVirtual = virtual }
        if (simulating != lastSimulating) { this.virtual.setIcon(if (simulating) activeIcon else idleIcon); lastSimulating = simulating }
        if (selected != lastSelected) {
            this.selected.isVisible = selected != null
            selected?.let { this.selected.position = it.amap() }
            lastSelected = selected
        }
    }

    fun route(points: List<LatLon>, editing: Boolean, anchors: List<LatLon>) {
        lastRoute = points
        lastEditing = editing
        lastAnchors = anchors
        vertices.forEach { it.remove() }
        vertices.clear()
        val shown = points.map { it.amap() }
        line.points = shown
        casing.points = shown
        line.isVisible = points.size >= 2
        casing.isVisible = points.size >= 2
        val middle = when {
            anchors.isNotEmpty() -> anchors.drop(1).dropLast(1)
            editing || points.size <= 60 -> points.drop(1).dropLast(1)
            else -> emptyList()
        }
        for (p in middle) vertices.add(map.addMarker(MarkerOptions().position(p.amap()).icon(vertexIcon)
            .anchor(0.5f, 0.5f).infoWindowEnable(false).zIndex(5f)))
        points.firstOrNull()?.let { vertices.add(map.addMarker(MarkerOptions().position(it.amap()).icon(startIcon)
            .anchor(0.5f, 0.5f).infoWindowEnable(false).zIndex(6f))) }
        if (points.size > 1) vertices.add(map.addMarker(MarkerOptions().position(points.last().amap()).icon(endIcon)
            .anchor(0.5f, 0.5f).infoWindowEnable(false).zIndex(6f)))
    }
}

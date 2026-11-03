package app.geoshift.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import app.geoshift.Geo
import app.geoshift.MapSource
import app.geoshift.PortalStore
import app.geoshift.data.LatLon
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import java.io.File

private fun LatLon.nativePoint(): LatLng = MapSource.AMAP.toDisplay(lat, lon).let { LatLng(it[0], it[1]) }
private fun LatLng.storedPoint(): LatLon = Geo.gcjToWgs(latitude, longitude).let { LatLon(it[0], it[1]) }

/** Only instantiated after explicit SDK consent and Android-key configuration. */
@Composable
fun AmapMap(
    modifier: Modifier,
    virtual: LatLon,
    simulating: Boolean,
    selected: LatLon?,
    route: List<LatLon>,
    routeEditing: Boolean,
    dark: Boolean,
    keyRevision: Long,
    camera: MapCamera,
    center: CenterRequest?,
    logoBottomMargin: Int,
    onTap: (LatLon) -> Unit,
    onLongPress: (LatLon) -> Unit,
    onLoaded: () -> Unit,
    onFailure: () -> Unit,
) {
    val context = LocalContext.current
    val tap = rememberUpdatedState(onTap)
    val longPress = rememberUpdatedState(onLongPress)
    val loaded = rememberUpdatedState(onLoaded)
    val result = remember(keyRevision) {
        var created: TextureMapView? = null
        runCatching {
            check(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" })
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
            MapsInitializer.setApiKey(PortalStore.amapAndroidKey(context))
            MapsInitializer.setProtocol(MapsInitializer.HTTPS)
            MapsInitializer.sdcardDir = File(context.cacheDir, "amap").apply { mkdirs() }.absolutePath
            MapsInitializer.initialize(context.applicationContext)
            val view = TextureMapView(context).also { created = it }.apply { onCreate(null) }
            NativeLayers(context, view, camera).apply {
                map.setOnMapClickListener { tap.value(it.storedPoint()) }
                map.setOnMapLongClickListener { longPress.value(it.storedPoint()) }
                map.setOnPOIClickListener { tap.value(it.coordinate.storedPoint()) }
                map.setOnMapLoadedListener { onMapReady() }
                // A loaded view is not proof that map data or key authentication succeeded.
                map.setTileLoadCallback { success, _, _ ->
                    if (success) Handler(Looper.getMainLooper()).post { loaded.value() }
                }
            }
        }.onFailure { runCatching { created?.onDestroy() } }
    }
    val layers = result.getOrNull()
    if (layers == null) {
        LaunchedEffect(result) { onFailure() }
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
    LaunchedEffect(center, layers) {
        val request = center ?: return@LaunchedEffect
        if (camera.handledToken == request.token) return@LaunchedEffect
        camera.handledToken = request.token
        val zoom = request.zoom ?: maxOf(camera.zoom, 15.0)
        camera.target = request.target
        camera.zoom = zoom
        layers.map.animateCamera(CameraUpdateFactory.newLatLngZoom(request.target.nativePoint(), zoom.toFloat()), 500L, null)
    }
    LaunchedEffect(route, routeEditing, layers) { layers.route(route, routeEditing) }
    AndroidView(factory = { view }, modifier = modifier) {
        layers.map.uiSettings.setLogoBottomMargin(logoBottomMargin)
        val type = if (dark) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL
        if (layers.map.mapType != type) layers.map.mapType = type
        layers.virtual.position = virtual.nativePoint()
        layers.virtual.setIcon(if (simulating) layers.activeIcon else layers.idleIcon)
        layers.selected.isVisible = selected != null
        selected?.let { layers.selected.position = it.nativePoint() }
    }
}

private class NativeLayers(context: Context, val view: TextureMapView, private val camera: MapCamera) {
    val map: AMap = view.map
    val idleIcon = BitmapDescriptorFactory.fromBitmap(dotDrawable(context, false).bitmap)
    val activeIcon = BitmapDescriptorFactory.fromBitmap(dotDrawable(context, true).bitmap)
    private val pinIcon = BitmapDescriptorFactory.fromBitmap(pinDrawable(context).bitmap)
    val virtual: Marker
    val selected: Marker
    private val line = map.addPolyline(PolylineOptions().color(0xff5266e8.toInt()).width(5 * context.resources.displayMetrics.density).zIndex(2f))
    private val vertices = mutableListOf<Marker>()
    private var ready = false
    private var lastRoute: List<LatLon> = emptyList()
    private var lastEditing = false
    private val vertexIcon = BitmapDescriptorFactory.fromBitmap(Bitmap.createScaledBitmap(dotDrawable(context, true).bitmap,
        (14 * context.resources.displayMetrics.density).toInt(), (14 * context.resources.displayMetrics.density).toInt(), true))

    init {
        map.isMyLocationEnabled = false
        map.showBuildings(true)
        map.showIndoorMap(false)
        map.setMinZoomLevel(3f)
        map.setMaxZoomLevel(20f)
        map.uiSettings.apply {
            isZoomControlsEnabled = false
            isMyLocationButtonEnabled = false
            isCompassEnabled = true
            isScaleControlsEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
        }
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(camera.target.nativePoint(), camera.zoom.toFloat()))
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition) {
                if (!ready) return
                camera.target = position.target.storedPoint()
                camera.zoom = position.zoom.toDouble()
            }
            override fun onCameraChangeFinish(position: CameraPosition) = onCameraChange(position)
        })
        virtual = map.addMarker(MarkerOptions().position(camera.target.nativePoint()).icon(idleIcon)
            .anchor(0.5f, 0.5f).infoWindowEnable(false).zIndex(20f))
        selected = map.addMarker(MarkerOptions().position(camera.target.nativePoint()).icon(pinIcon)
            .anchor(0.5f, 1f).infoWindowEnable(false).zIndex(30f).visible(false))
        map.setOnMarkerClickListener { true }
    }

    fun onMapReady() {
        val target = camera.target.nativePoint()
        val zoom = camera.zoom.toFloat()
        ready = true
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
        route(lastRoute, lastEditing)
    }

    fun route(points: List<LatLon>, editing: Boolean) {
        lastRoute = points
        lastEditing = editing
        vertices.forEach { it.remove() }
        vertices.clear()
        line.points = points.map { it.nativePoint() }
        line.isVisible = points.size >= 2
        points.forEachIndexed { index, point ->
            if (index == 0 || index == points.lastIndex || (editing && points.size <= 200)) {
                val icon = when (index) {
                    0 -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                    points.lastIndex -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                    else -> vertexIcon
                }
                vertices.add(map.addMarker(MarkerOptions().position(point.nativePoint()).icon(icon)
                    .anchor(0.5f, if (index == 0 || index == points.lastIndex) 1f else 0.5f).infoWindowEnable(false).zIndex(5f)))
            }
        }
    }
}

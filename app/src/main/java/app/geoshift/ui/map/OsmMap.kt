package app.geoshift.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
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
import app.geoshift.core.LatLon
import app.geoshift.map.MapSource
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.api.IGeoPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

/** A request to move the camera; a new [token] re-triggers the same target. */
data class CenterRequest(val target: LatLon, val zoom: Double? = null, val bearing: Double? = null, val token: Long = System.nanoTime())

class MapCamera(var target: LatLon, var zoom: Double = 15.0, var handledToken: Long? = null)

private const val INDIGO = 0xFF5266E8.toInt()
private const val PIN = 0xFFE5484D.toInt()

private fun MapSource.display(point: LatLon): GeoPoint =
    toDisplay(point).let { GeoPoint(it.lat, it.lon) }

private fun MapSource.wgs(point: IGeoPoint): LatLon =
    toWgs(LatLon(point.latitude, point.longitude))

/** Shared map view; only display coordinates change when switching tile sources. */
/** The plain OpenStreetMap layer; the other layers are drawn by MapLibre (VectorMap). */
@Composable
fun OsmMap(
    modifier: Modifier,
    virtual: LatLon,
    simulating: Boolean,
    real: LatLon?,
    selected: LatLon?,
    route: List<LatLon>,
    routeEditing: Boolean,
    online: Boolean,
    anchors: List<LatLon> = emptyList(),
    dark: Boolean,
    source: MapSource,
    camera: MapCamera,
    center: CenterRequest?,
    onTap: (LatLon) -> Unit,
    onLongPress: (LatLon) -> Unit,
) {
    val context = LocalContext.current
    val tap = rememberUpdatedState(onTap)
    val longPress = rememberUpdatedState(onLongPress)
    val currentSource = rememberUpdatedState(source)
    val renderedSource = remember { arrayOf(source) }
    val switching = remember { booleanArrayOf(false) }
    val routePoints = remember(route, source) { route.map { source.display(it) } }
    val anchorPoints = remember(anchors, source) { anchors.map { source.display(it) } }

    val mapView = remember {
        MapView(context).apply {
            setUseDataConnection(online)
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            isTilesScaledToDpi = true
            controller.setZoom(camera.zoom)
            controller.setCenter(source.display(camera.target))
        }
    }
    val routeOverlay = remember { RouteOverlay(context) }
    val virtualMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
    }
    val realMarker = remember {
        Marker(mapView).apply {
            icon = realMarker(context)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
    }
    val selectedMarker = remember {
        Marker(mapView).apply {
            icon = pinDrawable(context)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
    }
    val idleIcon = remember { simMarker(context, active = false) }
    val activeIcon = remember { simMarker(context, active = true) }

    remember {
        mapView.addMapListener(object : MapListener {
            private fun save(): Boolean {
                if (!switching[0]) {
                    camera.target = renderedSource[0].wgs(mapView.mapCenter)
                    camera.zoom = mapView.zoomLevelDouble
                }
                return false
            }
            override fun onScroll(event: ScrollEvent?) = save()
            override fun onZoom(event: ZoomEvent?) = save()
        })
        val events = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                tap.value(currentSource.value.wgs(p)); return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                longPress.value(currentSource.value.wgs(p)); return true
            }
        })
        mapView.overlays.add(events)
        mapView.overlays.add(routeOverlay)
        mapView.overlays.add(realMarker)
        mapView.overlays.add(virtualMarker)
        mapView.overlays.add(selectedMarker)
        true
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    LaunchedEffect(center) {
        val c = center ?: return@LaunchedEffect
        if (camera.handledToken == c.token) return@LaunchedEffect
        camera.handledToken = c.token
        mapView.controller.animateTo(source.display(c.target), c.zoom ?: maxOf(mapView.zoomLevelDouble, 15.0), 500L)
    }

    AndroidView(factory = { mapView }, modifier = modifier) { mv ->
        mv.setUseDataConnection(online)
        if (renderedSource[0] != source) {
            switching[0] = true
            mv.controller.stopAnimation(false)
            val camera = renderedSource[0].wgs(mv.mapCenter)
            val zoom = mv.zoomLevelDouble
            mv.setTileSource(TileSourceFactory.MAPNIK)
            mv.controller.setZoom(zoom)
            mv.controller.setCenter(source.display(camera))
            renderedSource[0] = source
            switching[0] = false
        }
        mv.overlayManager.tilesOverlay.setColorFilter(if (dark) DARK_TILES else null)
        mv.overlayManager.tilesOverlay.setLoadingBackgroundColor(if (dark) 0xFF1B1E27.toInt() else 0xFFE9ECF2.toInt())
        virtualMarker.position = source.display(virtual)
        virtualMarker.icon = if (simulating) activeIcon else idleIcon
        realMarker.isEnabled = real != null
        real?.let { realMarker.position = source.display(it) }
        selectedMarker.isEnabled = selected != null
        selected?.let { selectedMarker.position = source.display(it) }
        routeOverlay.points = routePoints
        routeOverlay.editing = routeEditing
        routeOverlay.anchors = anchorPoints
        mv.invalidate()
    }
}

/** Draws a route line with vertex dots; start and end are emphasised. */
private class RouteOverlay(context: Context) : Overlay() {
    var points: List<GeoPoint> = emptyList()
    var editing = false
    /** Tapped points of a road route; drawn instead of every vertex of the road line. */
    var anchors: List<GeoPoint> = emptyList()
    private val density = context.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * density; color = INDIGO
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val casing = Paint(line).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 8 * density }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = INDIGO }
    private val endFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val point = Point()
    private val path = Path()

    /** Screen positions of [points] (x, y pairs), reused between frames so drawing allocates nothing. */
    private var pixels = FloatArray(0)

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        val count = points.size
        if (shadow || count == 0) return
        val projection = mapView.projection
        if (pixels.size < count * 2) pixels = FloatArray(count * 2)
        path.reset()
        for (i in 0 until count) {
            projection.toPixels(points[i], point)
            val x = point.x.toFloat()
            val y = point.y.toFloat()
            pixels[i * 2] = x
            pixels[i * 2 + 1] = y
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (count > 1) {
            canvas.drawPath(path, casing)
            canvas.drawPath(path, line)
        }
        if (anchors.isNotEmpty()) {
            for (a in anchors) {
                projection.toPixels(a, point)
                vertex(canvas, point.x.toFloat(), point.y.toFloat())
            }
        } else if (editing || count <= 60) {
            for (i in 0 until count) vertex(canvas, pixels[i * 2], pixels[i * 2 + 1])
        }
        end(canvas, pixels[0], pixels[1], 0xFF1FA971.toInt())
        if (count > 1) end(canvas, pixels[count * 2 - 2], pixels[count * 2 - 1], PIN)
    }

    private fun vertex(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, 4.5f * density, dot)
        canvas.drawCircle(x, y, 4.5f * density, dotRing)
    }

    private fun end(canvas: Canvas, x: Float, y: Float, color: Int) {
        endFill.color = color
        canvas.drawCircle(x, y, 7 * density, dot)
        canvas.drawCircle(x, y, 5 * density, endFill)
    }

    override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?) = false
}

/** Inverts tiles and pulls saturation down so OSM looks like a dark map. */
private val DARK_TILES = ColorMatrixColorFilter(ColorMatrix().apply {
    val invert = ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f))
    val desaturate = ColorMatrix().apply { setSaturation(0.25f) }
    val dim = ColorMatrix(floatArrayOf(
        0.85f, 0f, 0f, 0f, 0f,
        0f, 0.87f, 0f, 0f, 0f,
        0f, 0f, 0.95f, 0f, 8f,
        0f, 0f, 0f, 1f, 0f))
    set(invert)
    postConcat(desaturate)
    postConcat(dim)
})

internal fun pinDrawable(context: Context): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val w = (34 * d).toInt()
    val h = (44 * d).toInt()
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bitmap)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = w / 2f
    val r = 15 * d
    val path = Path().apply {
        moveTo(cx, h.toFloat() - 1 * d)
        cubicTo(cx - 4 * d, h - 10 * d, cx - r, r + 9 * d, cx - r, r + 2 * d)
        arcTo(cx - r, 2 * d, cx + r, 2 * d + 2 * r, 180f, 180f, false)
        cubicTo(cx + r, r + 9 * d, cx + 4 * d, h - 10 * d, cx, h.toFloat() - 1 * d)
        close()
    }
    p.color = PIN
    c.drawPath(path, p)
    p.color = 0xFFFFFFFF.toInt()
    c.drawCircle(cx, r + 2 * d, 5.5f * d, p)
    return BitmapDrawable(context.resources, bitmap)
}

package app.geoshift.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.geoshift.data.Capabilities
import app.geoshift.data.CellPreset
import app.geoshift.data.EnvironmentRecord
import app.geoshift.data.Geoshift
import app.geoshift.data.LogEntry
import app.geoshift.data.Report
import app.geoshift.data.Runtime
import app.geoshift.data.SavedPlace
import app.geoshift.data.SavedRoute
import app.geoshift.data.Settings
import app.geoshift.data.WifiPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the screens show. */
data class AppState(
    val version: String,
    val settings: Settings,
    val runtime: Runtime,
    val caps: Capabilities,
    val places: List<SavedPlace>,
    val routes: List<SavedRoute>,
    val wifiPresets: List<WifiPreset>,
    val cellPresets: List<CellPreset>,
    val environments: List<EnvironmentRecord>,
    val logs: List<LogEntry>,
    val reports: Map<String, Report>,
    /** AMap Android key saved: official AMap map, search and routes. */
    val sdkKeyConfigured: Boolean,
) {
    /** The selected route, looked up once per state. */
    val currentRouteRecord: SavedRoute? by lazy { routes.firstOrNull { it.id == runtime.routeId } }

    /** Points of the selected route, or none. */
    val currentRoute: List<app.geoshift.core.LatLon> get() = currentRouteRecord?.points.orEmpty()

    /** Length of the selected route in metres (cached on the record). */
    val currentRouteLength: Double get() = currentRouteRecord?.length ?: 0.0

    /** Name of the favourite (or environment) at the simulated position, or "". */
    val positionName: String by lazy { app.geoshift.data.positionName(runtime, places, environments) }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val engine = Geoshift.get(app)
    private val store = engine.store

    private val records = combine(store.places.items, store.routes.items, store.wifiPresets.items, store.cellPresets.items, store.environments.items) { a, b, c, d, e ->
        Records(a, b, c, d, e)
    }

    private class Records(val places: List<SavedPlace>, val routes: List<SavedRoute>, val wifi: List<WifiPreset>,
                          val cells: List<CellPreset>, val environments: List<EnvironmentRecord>)

    val state: StateFlow<AppState?> = combine(
        combine(store.settings, store.runtime, engine.caps, ::Triple),
        records,
        combine(store.logs, engine.bridge.reports, store.sdkKey, ::Triple),
    ) { (settings, runtime, caps), r, (logs, reports, sdkKey) ->
        AppState(Geoshift.VERSION, settings, runtime, caps, r.places, r.routes, r.wifi, r.cells, r.environments, logs, reports,
            sdkKey.isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _frameworkHelp = MutableStateFlow(false)
    val frameworkHelp: StateFlow<Boolean> = _frameworkHelp.asStateFlow()
    fun showFrameworkHelp() { _frameworkHelp.value = true }
    fun hideFrameworkHelp() { _frameworkHelp.value = false }

    @Volatile var foreground = true

    init {
        // Permissions and the module's state change outside the app; check them while it is open.
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (foreground) runCatching { engine.capabilities() }
                delay(2000)
            }
        }
    }

    fun refresh() { viewModelScope.launch(Dispatchers.IO) { runCatching { engine.capabilities() } } }

    fun toast(message: String) { _messages.tryEmit(message) }

    /**
     * Runs an operation off the main thread. Shows [success] or the error; [onDone] gets the result
     * on the main thread.
     */
    fun <T> run(success: String? = null, onDone: ((T) -> Unit)? = null, block: Geoshift.() -> T) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = withContext(Dispatchers.IO) { engine.block() }
                success?.let { _messages.emit(it) }
                onDone?.invoke(result)
            } catch (e: Throwable) {
                val message = e.message ?: e.javaClass.simpleName
                _messages.emit(message)
                withContext(Dispatchers.IO) { store.log(message) }
            } finally {
                _busy.value = false
                refresh()
            }
        }
    }
}

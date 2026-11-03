package app.geoshift.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.geoshift.PortalStore
import app.geoshift.data.PortalActions
import app.geoshift.data.PortalState

class PortalViewModel(app: Application) : AndroidViewModel(app) {
    val actions = PortalActions(app)

    private val _state = MutableStateFlow<PortalState?>(null)
    val state: StateFlow<PortalState?> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val lock = Mutex()

    @Volatile var foreground = true

    init {
        refresh()
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                if (foreground) poll()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            lock.withLock { _state.value = actions.snapshot() }
        }
    }

    // Last polled status text. RuntimeState holds JSONObject fields, which never compare equal, so
    // without this check every poll would emit a "new" state and redraw the whole UI once a second.
    private var lastStatus = ""

    private suspend fun poll() {
        val current = _state.value ?: return
        try {
            val status = actions.status()
            val text = status.toString()
            lock.withLock {
                if (text == lastStatus && _state.value === current) return@withLock
                lastStatus = text
                _state.value = (_state.value ?: current).withStatus(status)
            }
        } catch (_: Exception) {
        }
    }

    private val _lsposedHelp = MutableStateFlow(false)
    val lsposedHelp: StateFlow<Boolean> = _lsposedHelp.asStateFlow()
    fun showLsposedHelp() { _lsposedHelp.value = true }
    fun hideLsposedHelp() { _lsposedHelp.value = false }

    fun toast(message: String) {
        _messages.tryEmit(message)
    }

    /**
     * Runs an action off the main thread, then refreshes state. Shows [success] or the error message.
     * [onDone] is called on the main thread with the result when the action succeeds.
     */
    fun <T> run(success: String? = null, onDone: ((T) -> Unit)? = null, block: PortalActions.() -> T) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = withContext(Dispatchers.IO) { actions.block() }
                success?.let { _messages.emit(it) }
                onDone?.invoke(result)
            } catch (e: Throwable) {
                val message = e.message ?: e.javaClass.simpleName
                _messages.emit(message)
                withContext(Dispatchers.IO) { PortalStore.log(getApplication(), message) }
            } finally {
                _busy.value = false
                withContext(Dispatchers.IO) { lock.withLock { _state.value = actions.snapshot() } }
            }
        }
    }
}

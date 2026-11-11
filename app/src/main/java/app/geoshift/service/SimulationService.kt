package app.geoshift.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import app.geoshift.MainActivity
import app.geoshift.core.Clock
import app.geoshift.core.FixBuilder
import app.geoshift.core.toLocation
import app.geoshift.data.Backend
import app.geoshift.data.Geoshift

/**
 * Foreground owner of a running simulation. With the Android mock backend it feeds the test
 * providers at the set interval; with Xposed, system_server injects and plays routes on its own and
 * this only keeps the app's view current and shows the "stop" notification.
 */
class SimulationService : Service() {
    private val clock = Handler(Looper.getMainLooper())
    private val ticker = Runnable { tick() }
    private lateinit var engine: Geoshift
    private var manager: LocationManager? = null
    private var providers = false
    private var shownPaused: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        engine = Geoshift.get(this)
        manager = getSystemService(LOCATION_SERVICE) as LocationManager
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "位置模拟", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("定位服务准备中"))
        running = this
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SimulationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(app.geoshift.R.drawable.ic_stat_geoshift)
            .setContentTitle("Geoshift")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if (intent?.action == ACTION_STOP) {
            engine.stop()
            return START_NOT_STICKY
        }
        try {
            engine.ensureReady()
            if (engine.store.settings.value.backend == Backend.MOCK && !providers) installProviders()
            emitNow()
        } catch (e: Exception) {
            fail(e)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        clock.removeCallbacksAndMessages(null)
        removeProviders()
        if (engine.store.runtime.value.enabled) engine.store.updateRuntime { it.copy(enabled = false, paused = false, speed = 0.0, anchor = null) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun installProviders() {
        val m = manager ?: throw Exception("系统定位服务不可用。")
        try {
            for (name in PROVIDERS) {
                runCatching { m.removeTestProvider(name) }
                val gps = name == LocationManager.GPS_PROVIDER
                m.addTestProvider(name, !gps, gps, false, false, true, true, true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE)
                m.setTestProviderEnabled(name, true)
            }
            providers = true
        } catch (e: Exception) {
            removeProviders()
            throw Exception("建立模拟定位失败，请重新检查开发者选项中的模拟位置应用。", e)
        }
    }

    private fun removeProviders() {
        manager?.let { m -> for (name in PROVIDERS) runCatching { m.removeTestProvider(name) } }
        providers = false
    }

    /** Restarts the clock so the current position is reported at once. */
    private fun emitNow() {
        clock.removeCallbacks(ticker)
        tick()
    }

    private fun tick() {
        val r = engine.store.runtime.value
        if (!r.enabled) { stopSelf(); return }
        try {
            engine.advanceRoute()
            val s = engine.store.settings.value
            val now = engine.store.runtime.value
            if (shownPaused != now.paused) {
                shownPaused = now.paused
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID,
                    notification(if (now.paused) "路线已暂停；保持当前位置" else "位置模拟运行中，可随时停止"))
            }
            if (providers) {
                emit()
                if (s.doubleReport) emit()
            }
            val interval = s.interval.coerceIn(100, 60000).toLong()
            val next = when {
                // The hooks play the route on their own; only the app's own view needs updating, and
                // nobody looks at it while Geoshift is in the background.
                s.backend == Backend.XPOSED -> if (engine.uiVisible || now.joystick) minOf(interval, 5000) else 10_000
                now.routeMode || s.keepReporting || now.paused -> interval
                else -> -1
            }
            if (next > 0) clock.postDelayed(ticker, next)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun emit() {
        val s = engine.store.settings.value
        val r = engine.store.runtime.value
        val config = engine.localConfig(s, r)
        val plan = r.anchor?.let { engine.plan(it.routeId) }
        val at = SystemClock.elapsedRealtime()
        for (name in PROVIDERS) manager?.setTestProviderLocation(name, FixBuilder.build(config, name, at, Clock.SYSTEM, plan).toLocation(mock = true))
    }

    private fun fail(e: Exception) {
        val message = e.message ?: "模拟服务异常"
        engine.store.log(message)
        engine.store.updateRuntime { it.copy(enabled = false, paused = false, error = message, anchor = null) }
        stopSelf()
    }

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL = "geoshift_location"
        private const val ACTION_STOP = "STOP"
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        private val MAIN = Handler(Looper.getMainLooper())
        @Volatile private var running: SimulationService? = null

        fun start(c: Context) { c.startForegroundService(Intent(c, SimulationService::class.java)) }
        fun stop(c: Context) { c.stopService(Intent(c, SimulationService::class.java)) }
        /** The position or state changed: report it now instead of at the next tick. */
        fun nudge() { MAIN.post { running?.emitNow() } }
    }
}

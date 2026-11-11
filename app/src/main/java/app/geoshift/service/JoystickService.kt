package app.geoshift.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import app.geoshift.core.Geo
import app.geoshift.data.Geoshift
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** A small overlay joystick that moves the simulated point. */
class JoystickService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val move = Runnable { step() }
    private lateinit var engine: Geoshift
    private var windows: WindowManager? = null
    private var stick: Stick? = null
    /** Deflection, each in [-1, 1]; (0, 0) is idle. */
    private var dx = 0f
    private var dy = 0f
    private var moving = false

    override fun onCreate() {
        super.onCreate()
        engine = Geoshift.get(this)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "摇杆悬浮窗", NotificationManager.IMPORTANCE_LOW))
        val close = PendingIntent.getService(this, 2, Intent(this, JoystickService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(app.geoshift.R.drawable.ic_stat_geoshift)
            .setContentTitle("Geoshift 摇杆")
            .setContentText("拖动圆盘移动；点右上角 × 关闭悬浮窗")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "关闭", close).build())
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if (intent?.action == ACTION_CLOSE) { stopSelf(); return START_NOT_STICKY }
        try {
            engine.ensureReady()
            if (!Settings.canDrawOverlays(this)) throw Exception("尚未授予悬浮窗权限。")
            if (stick == null) show()
            engine.store.updateRuntime(persist = false) { it.copy(joystick = true) }
        } catch (e: Exception) {
            engine.store.updateRuntime(persist = false) { it.copy(joystick = false, error = e.message.orEmpty()) }
            engine.store.log("悬浮窗：${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun show() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windows = wm
        val view = Stick(this)
        stick = view
        val size = (152 * resources.displayMetrics.density).toInt()
        val p = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        p.x = 20
        wm.addView(view, p)
        handler.post(move)
    }

    /** Moves the point by one step of travel in the joystick direction. */
    private fun step() {
        if (stick == null) return
        try {
            val length = hypot(dx, dy).toDouble()
            if (length > 0.1) {
                val heading = Geo.normalizeBearing(Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())))
                val metres = engine.store.settings.value.speed * min(1.0, length) * STEP_MS / 1000.0
                // Report the speed actually travelled, so speed, heading and position change agree.
                engine.joystickStep(metres, heading, metres * 1000.0 / STEP_MS)
                moving = true
            } else if (moving) {
                moving = false
                engine.joystickStep(0.0, 0.0, 0.0)
            }
        } catch (e: Exception) {
            engine.store.log("摇杆：${e.message}")
        }
        handler.postDelayed(move, STEP_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stick?.let { s -> runCatching { windows?.removeView(s) } }
        stick = null
        engine.store.updateRuntime { it.copy(joystick = false, speed = 0.0) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ViewConstructor")
    private inner class Stick(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = w * RING
            paint.style = Paint.Style.FILL
            paint.color = FACE
            c.drawCircle(w / 2, h / 2, r, paint)
            paint.color = ACCENT
            paint.strokeWidth = 2f
            paint.style = Paint.Style.STROKE
            c.drawCircle(w / 2, h / 2, r, paint)
            paint.style = Paint.Style.FILL
            c.drawCircle(w / 2 + dx * r * .55f, h / 2 + dy * r * .55f, r * .31f, paint)
            paint.textSize = w * .13f
            c.drawText("×", w * .82f, h * .17f, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val w = width.toFloat()
            val h = height.toFloat()
            when {
                e.actionMasked == MotionEvent.ACTION_DOWN && e.x > w * .75f && e.y < h * .27f -> { stopSelf(); return true }
                e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL -> { dx = 0f; dy = 0f }
                else -> {
                    dx = (e.x - w / 2) / (w * RING)
                    dy = (e.y - h / 2) / (w * RING)
                    val n = hypot(dx, dy)
                    if (n > 1) { dx /= n; dy /= n }
                }
            }
            invalidate()
            return true
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 102
        private const val CHANNEL = "geoshift_joystick"
        private const val ACTION_CLOSE = "CLOSE"
        private const val STEP_MS = 100L
        private const val RING = 0.37f
        private const val FACE = 0xeef6f8fc.toInt()
        private const val ACCENT = 0xff2f62f5.toInt()

        fun command(c: Context, enabled: Boolean) {
            val i = Intent(c, JoystickService::class.java)
            if (enabled) c.startForegroundService(i) else c.stopService(i)
        }
    }
}

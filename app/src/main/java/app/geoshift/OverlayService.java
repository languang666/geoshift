package app.geoshift;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import org.json.JSONObject;

/** A small, explicit overlay joystick; it only moves the configured centre. */
public final class OverlayService extends Service {
    private static final int NOTIFICATION_ID = 102;
    private static final String CHANNEL = "geoshift_joystick";
    private static final long STEP_MS = 100;
    private static final float RING = 0.37f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable move = this::step;
    private WindowManager windows;
    private Stick stick;
    /** Joystick deflection, each in [-1, 1]; (0, 0) is idle. */
    private float dx, dy;

    public static void command(Context c, boolean enabled) {
        Intent i = new Intent(c, OverlayService.class);
        if (enabled) {
            c.startForegroundService(i);
        } else {
            PortalStore.updateRuntime(c, PortalStore.obj("overlay", false));
            c.stopService(i);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel(CHANNEL, "摇杆悬浮窗", NotificationManager.IMPORTANCE_LOW));
        PendingIntent close = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction("CLOSE"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Geoshift 摇杆")
                .setContentText("拖动圆盘移动；点右上角 × 关闭悬浮窗")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "关闭", close).build())
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "CLOSE".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            SimulationService.ensureReady(this);
            if (!Settings.canDrawOverlays(this)) throw new Exception("尚未授予悬浮窗权限。");
            if (stick == null) showStick();
            PortalStore.updateRuntime(this, PortalStore.obj("overlay", true));
        } catch (Exception e) {
            PortalStore.updateRuntime(this, PortalStore.obj("overlay", false, "error", String.valueOf(e.getMessage())));
            PortalStore.log(this, "悬浮窗：" + e.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void showStick() {
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        stick = new Stick();
        int size = (int) (152 * getResources().getDisplayMetrics().density);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        p.x = 20;
        windows.addView(stick, p);
        handler.post(move);
    }

    /** Moves the centre by one STEP_MS worth of travel in the joystick direction. */
    private void step() {
        if (stick == null) return;
        try {
            double length = Math.hypot(dx, dy);
            if (length > 0.1) {
                JSONObject s = PortalStore.settings(this);
                JSONObject r = PortalStore.runtimeFields(this, "lat", "lon", "enabled");
                double heading = Geo.normalizeBearing(Math.toDegrees(Math.atan2(dx, -dy)));
                double metres = PortalStore.speed(s) * Math.min(1, length) * STEP_MS / 1000.0;
                double[] p = Geo.move(r.optDouble("lat"), r.optDouble("lon"), metres, heading);
                PortalStore.updateRuntime(this, PortalStore.obj("lat", p[0], "lon", p[1], "bearing", heading));
                if (r.optBoolean("enabled")) SimulationService.command(this, "UPDATE");
            }
        } catch (Exception e) {
            PortalStore.log(this, "摇杆：" + e.getMessage());
        }
        handler.postDelayed(move, STEP_MS);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (stick != null && windows != null) {
            try {
                windows.removeView(stick);
            } catch (Exception ignored) {}
        }
        stick = null;
        PortalStore.updateRuntime(this, PortalStore.obj("overlay", false));
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private final class Stick extends View {
        private static final int FACE = 0xeef6f8fc;
        private static final int ACCENT = 0xff5266e8;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Stick() {
            super(OverlayService.this);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = w * RING;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(FACE);
            c.drawCircle(cx, cy, r, paint);
            paint.setColor(ACCENT);
            paint.setStrokeWidth(2);
            paint.setStyle(Paint.Style.STROKE);
            c.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx + dx * r * .55f, cy + dy * r * .55f, r * .31f, paint);
            paint.setTextSize(w * .13f);
            c.drawText("×", w * .82f, h * .17f, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float w = getWidth(), h = getHeight();
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && event.getX() > w * .75f && event.getY() < h * .27f) {
                stopSelf();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dx = dy = 0;
            } else {
                dx = (event.getX() - w / 2) / (w * RING);
                dy = (event.getY() - h / 2) / (w * RING);
                double n = Math.hypot(dx, dy);
                if (n > 1) {
                    dx /= n;
                    dy /= n;
                }
            }
            invalidate();
            return true;
        }
    }
}

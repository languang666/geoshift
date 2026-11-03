package app.geoshift;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Random;

/** Foreground owner of the route clock and Android test providers. No shell or native libraries. */
public final class SimulationService extends Service {
    private static final int NOTIFICATION_ID = 101;
    private static final String CHANNEL = "geoshift_location";
    private static final String[] PROVIDERS = {"gps", "network"};

    /** The running instance, used for in-process "position changed" nudges. Main thread only. */
    private static SimulationService running;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Runnable ticker = this::tick;
    private LocationManager manager;
    private boolean providers;
    private long previous;
    private double segmentDistance;

    // ---- Public entry points ------------------------------------------------------------------

    public static void ensureReady(Context c) throws Exception {
        JSONObject s = PortalStore.settings(c);
        if (!s.optBoolean("privacyAccepted")) throw new Exception("请先阅读并同意使用说明。");
        if (!PortalStore.preciseLocationGranted(c)) throw new Exception("请先授予精确位置权限。");
        if (!PortalStore.locationEnabled(c)) throw new Exception("请先打开系统定位开关。");
        if ("lsposed".equals(s.optString("backend"))) {
            if (!PortalStore.hookActive(c))
                throw new Exception("尚未连接 LSPosed 系统模块，请确认作用域并重启相关进程。");
        } else if (!PortalStore.mockSelected(c)) {
            throw new Exception("请在开发者选项的“模拟位置信息应用”中选择 Geoshift。");
        }
    }

    /** START / PAUSE / RESUME / STOP go through the service; UPDATE only nudges a running clock. */
    public static void command(Context c, String action) {
        if ("STOP".equals(action)) {
            PortalStore.updateRuntime(c, PortalStore.obj("enabled", false, "paused", false, "speed", 0));
            c.stopService(new Intent(c, SimulationService.class));
            return;
        }
        if ("UPDATE".equals(action)) {
            MAIN.post(() -> {
                if (running != null) running.emitNow();
            });
            return;
        }
        c.startForegroundService(new Intent(c, SimulationService.class).setAction(action));
    }

    // ---- Lifecycle ----------------------------------------------------------------------------

    @Override public void onCreate() {
        super.onCreate();
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel(CHANNEL, "位置模拟", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, notification("定位服务准备中"));
        running = this;
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, SimulationService.class).setAction("STOP"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Geoshift")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? "STOP" : intent.getAction();
        if ("STOP".equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            ensureReady(this);
            JSONObject s = PortalStore.settings(this);
            if ("START".equals(action)) {
                JSONObject r = PortalStore.runtime(this);
                if ("route".equals(r.optString("mode"))) {
                    JSONArray route = r.optJSONArray("route");
                    if (route == null || route.length() < 2) throw new Exception("请先选择至少两个点的路线。");
                    JSONObject first = route.getJSONObject(0);
                    PortalStore.updateRuntime(this, PortalStore.obj("lat", first.getDouble("lat"),
                            "lon", first.getDouble("lon"), "routeIndex", 0, "progress", 0));
                    segmentDistance = 0;
                }
                previous = 0;
                PortalStore.updateRuntime(this, PortalStore.obj("enabled", true, "paused", false, "error", ""));
            } else if ("PAUSE".equals(action)) {
                PortalStore.updateRuntime(this, PortalStore.obj("paused", true, "speed", 0));
            } else if ("RESUME".equals(action)) {
                PortalStore.updateRuntime(this, PortalStore.obj("paused", false));
            }
            JSONObject state = PortalStore.runtimeLight(this);
            if (!state.optBoolean("enabled")) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if ("android_mock".equals(s.optString("backend")) && !providers) installProviders();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,
                    notification(state.optBoolean("paused") ? "路线已暂停；保持当前位置" : "位置模拟运行中，可随时停止"));
            emitNow();
        } catch (Exception e) {
            fail(e);
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (running == this) running = null;
        clock.removeCallbacksAndMessages(null);
        removeProviders();
        PortalStore.updateRuntime(this, PortalStore.obj("enabled", false, "paused", false, "speed", 0));
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- Providers ----------------------------------------------------------------------------

    private void installProviders() throws Exception {
        if (manager == null) throw new Exception("系统定位服务不可用。");
        try {
            for (String name : PROVIDERS) {
                try {
                    manager.removeTestProvider(name);
                } catch (Exception ignored) {}
                boolean gps = "gps".equals(name);
                manager.addTestProvider(name, !gps, gps, false, false, true, true, true,
                        android.location.provider.ProviderProperties.POWER_USAGE_LOW,
                        android.location.provider.ProviderProperties.ACCURACY_FINE);
                manager.setTestProviderEnabled(name, true);
            }
            providers = true;
        } catch (Exception e) {
            removeProviders();
            throw new Exception("建立模拟定位失败，请重新检查开发者选项中的模拟位置应用。", e);
        }
    }

    private void removeProviders() {
        if (manager != null) {
            for (String name : PROVIDERS) {
                try {
                    manager.removeTestProvider(name);
                } catch (Exception ignored) {}
            }
        }
        providers = false;
    }

    // ---- Clock --------------------------------------------------------------------------------

    /** Restarts the clock so the current position is reported immediately. */
    private void emitNow() {
        clock.removeCallbacks(ticker);
        tick();
    }

    private void tick() {
        JSONObject s = PortalStore.settings(this);
        JSONObject r = PortalStore.runtimeLight(this);
        if (!r.optBoolean("enabled")) {
            stopSelf();
            return;
        }
        try {
            long now = SystemClock.elapsedRealtime();
            double dt = previous == 0 ? 0 : Math.min(3, Math.max(0, (now - previous) / 1000.0));
            previous = now;
            boolean route = "route".equals(r.optString("mode"));
            if (route && !r.optBoolean("paused")) {
                advance(PortalStore.runtimeFields(this, "route", "routeIndex"), s, dt);
                r = PortalStore.runtimeLight(this);
            }
            if (providers) {
                emit(r, s);
                if (s.optBoolean("secondReport")) emit(r, s);
            }
            int interval = Math.max(100, Math.min(60000, s.optInt("interval", 1000)));
            if ("lsposed".equals(s.optString("backend"))) {
                // The system hook injects on its own clock; this push is the lease heartbeat.
                PortalStore.pushToSystem(this);
                clock.postDelayed(ticker, Math.min(interval, 5000));
            } else if (route || s.optBoolean("enhanced", true) || r.optBoolean("paused")) {
                clock.postDelayed(ticker, interval);
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    private void advance(JSONObject r, JSONObject s, double dt) throws Exception {
        JSONArray points = r.optJSONArray("route");
        if (points == null || points.length() < 2) throw new Exception("当前路线无效。");
        int last = points.length() - 1;
        int index = Math.max(0, r.optInt("routeIndex", 0));
        double speed = PortalStore.speed(s);
        double remaining = speed * dt;
        while (index < last) {
            double length = segmentLength(points, index);
            if (length < 0.01 || segmentDistance + remaining >= length) {
                remaining = Math.max(0, remaining - (length - segmentDistance));
                segmentDistance = 0;
                index++;
                continue;
            }
            segmentDistance += remaining;
            break;
        }
        if (index >= last) {
            JSONObject end = points.getJSONObject(last);
            PortalStore.updateRuntime(this, PortalStore.obj("lat", end.getDouble("lat"), "lon", end.getDouble("lon"),
                    "routeIndex", index, "progress", 1, "paused", true, "speed", 0));
            PortalStore.log(this, "路线已完成，保持终点位置；可手动停止定位服务。");
            return;
        }
        JSONObject a = points.getJSONObject(index), b = points.getJSONObject(index + 1);
        double aLat = a.getDouble("lat"), aLon = a.getDouble("lon"), bLat = b.getDouble("lat"), bLon = b.getDouble("lon");
        double length = Geo.distance(aLat, aLon, bLat, bLon);
        double fraction = length < 0.01 ? 0 : segmentDistance / length;
        double[] p = Geo.interpolate(aLat, aLon, bLat, bLon, fraction);
        PortalStore.updateRuntime(this, PortalStore.obj("lat", p[0], "lon", p[1],
                "bearing", Geo.bearing(aLat, aLon, bLat, bLon), "speed", speed,
                "routeIndex", index, "progress", (index + fraction) / last));
    }

    private static double segmentLength(JSONArray points, int index) throws Exception {
        JSONObject a = points.getJSONObject(index), b = points.getJSONObject(index + 1);
        return Geo.distance(a.getDouble("lat"), a.getDouble("lon"), b.getDouble("lat"), b.getDouble("lon"));
    }

    private void emit(JSONObject r, JSONObject s) throws Exception {
        double lat = r.getDouble("lat"), lon = r.getDouble("lon");
        if (s.optBoolean("jitter")) {
            double[] p = Geo.move(lat, lon, random.nextDouble(), random.nextDouble() * 360);
            lat = p[0];
            lon = p[1];
        }
        float accuracy = (float) s.optDouble("accuracy", 5);
        double altitude = s.optDouble("altitude", 0);
        float bearing = (float) r.optDouble("bearing", 0);
        float speed = (float) (r.optBoolean("paused") ? 0 : r.optDouble("speed", 0));
        for (String provider : PROVIDERS) {
            Location location = new Location(provider);
            location.setLatitude(lat);
            location.setLongitude(lon);
            location.setAccuracy(accuracy);
            location.setAltitude(altitude);
            location.setBearing(bearing);
            location.setSpeed(speed);
            location.setTime(System.currentTimeMillis());
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            if (Build.VERSION.SDK_INT >= 31) location.setMock(true);
            Bundle extras = new Bundle();
            extras.putBoolean("geoshift.mock", true);
            location.setExtras(extras);
            manager.setTestProviderLocation(provider, location);
        }
    }

    private void fail(Exception error) {
        String message = error.getMessage() == null ? "模拟服务异常" : error.getMessage();
        PortalStore.log(this, message);
        PortalStore.updateRuntime(this, PortalStore.obj("enabled", false, "paused", false, "error", message));
        stopSelf();
    }
}

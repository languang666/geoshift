package test.probe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.CellInfo;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;

/** Plain app (no LSPosed scope) that reads every location clue an app SDK would use and logs it. */
public class ProbeActivity extends Activity {
    private static final String TAG = "GEOPROBE";
    private static final String PI_ACTION = "test.probe.LOCATION";
    private final Map<String, String> results = new TreeMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor main = handler::post;
    private TextView view;
    private int gnssCallbacks, nmeaCount, measurementCount, navCount, bleResults;
    private final java.util.Set<String> bleDevices = new java.util.TreeSet<>();
    private android.bluetooth.le.ScanCallback bleCallback;
    private String measurementSample = "";
    private String gnssSummary = "无回调", nmeaSample = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        view = new TextView(this);
        view.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        setContentView(scroll);
        run();
    }

    private static String loc(Location l) {
        if (l == null) return "null";
        return String.format(java.util.Locale.US, "%.6f,%.6f provider=%s mock=%s acc=%.1f alt=%s spd=%s brg=%s", l.getLatitude(), l.getLongitude(), l.getProvider(), l.isMock(),
                l.getAccuracy(), l.hasAltitude() ? String.format(java.util.Locale.US, "%.1f", l.getAltitude()) : "-",
                l.hasSpeed() ? String.format(java.util.Locale.US, "%.2f", l.getSpeed()) : "-", l.hasBearing() ? String.valueOf(l.getBearing()) : "-");
    }

    private void put(String key, String value) {
        results.put(key, value);
    }

    @SuppressWarnings({"MissingPermission", "deprecation"})
    private void run() {
        LocationManager lm = getSystemService(LocationManager.class);
        for (String p : new String[]{"gps", "network", "fused", "passive"}) {
            try { put("1.last." + p, loc(lm.getLastKnownLocation(p))); } catch (Throwable e) { put("1.last." + p, "ERR " + e); }
        }
        for (String p : new String[]{"gps", "network", "fused"}) {
            final String key = "2.listener." + p;
            put(key, "no callback");
            try {
                lm.requestLocationUpdates(p, 500, 0, (LocationListener) l -> put(key, loc(l)), Looper.getMainLooper());
            } catch (Throwable e) { put(key, "ERR " + e); }
        }
        put("3.current.gps", "no callback");
        try { lm.getCurrentLocation("gps", null, main, l -> put("3.current.gps", loc(l))); } catch (Throwable e) { put("3.current.gps", "ERR " + e); }
        put("4.pendingIntent.gps", "no callback");
        try {
            registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    Location l = i.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location.class);
                    if (l != null) put("4.pendingIntent.gps", loc(l));
                }
            }, new IntentFilter(PI_ACTION), Context.RECEIVER_EXPORTED);
            PendingIntent pi = PendingIntent.getBroadcast(this, 1, new Intent(PI_ACTION).setPackage(getPackageName()),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            lm.requestLocationUpdates("gps", 500, 0, pi);
        } catch (Throwable e) { put("4.pendingIntent.gps", "ERR " + e); }
        try {
            lm.registerGnssStatusCallback(main, new GnssStatus.Callback() {
                @Override public void onSatelliteStatusChanged(GnssStatus s) {
                    gnssCallbacks++;
                    int used = 0;
                    for (int i = 0; i < s.getSatelliteCount(); i++) if (s.usedInFix(i)) used++;
                    gnssSummary = "sats=" + s.getSatelliteCount() + " used=" + used + " firstSvid=" + (s.getSatelliteCount() > 0 ? s.getSvid(0) : -1)
                            + " firstCn0=" + (s.getSatelliteCount() > 0 ? s.getCn0DbHz(0) : -1);
                }
            });
            lm.addNmeaListener(main, (OnNmeaMessageListener) (msg, ts) -> {
                nmeaCount++;
                if (msg.contains("GGA") || nmeaSample.isEmpty()) nmeaSample = msg.trim();
            });
        } catch (Throwable e) { put("5.gnss", "ERR " + e); }
        try {
            lm.registerGnssMeasurementsCallback(main, new android.location.GnssMeasurementsEvent.Callback() {
                @Override public void onGnssMeasurementsReceived(android.location.GnssMeasurementsEvent e) {
                    measurementCount++;
                    measurementSample = "n=" + e.getMeasurements().size() + " clockBias=" + e.getClock().getFullBiasNanos();
                }
            });
            lm.registerGnssNavigationMessageCallback(main, new android.location.GnssNavigationMessage.Callback() {
                @Override public void onGnssNavigationMessageReceived(android.location.GnssNavigationMessage m) { navCount++; }
            });
        } catch (Throwable e) { put("5.gnssRaw", "ERR " + e); }

        // Google Play services fused location (what FusedLocationProviderClient-based SDKs see).
        try {
            com.google.android.gms.location.FusedLocationProviderClient f =
                    com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this);
            put("10.gms.last", "no callback");
            f.getLastLocation().addOnCompleteListener(t -> put("10.gms.last", t.isSuccessful() ? loc(t.getResult()) : "ERR " + t.getException()));
            put("10.gms.currentHigh", "no callback");
            f.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnCompleteListener(t -> put("10.gms.currentHigh", t.isSuccessful() ? loc(t.getResult()) : "ERR " + t.getException()));
            put("10.gms.currentBalanced", "no callback");
            f.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnCompleteListener(t -> put("10.gms.currentBalanced", t.isSuccessful() ? loc(t.getResult()) : "ERR " + t.getException()));
            put("10.gms.updates", "no callback");
            f.requestLocationUpdates(new com.google.android.gms.location.LocationRequest.Builder(
                    com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000).build(),
                    new com.google.android.gms.location.LocationCallback() {
                        @Override public void onLocationResult(com.google.android.gms.location.LocationResult r) {
                            put("10.gms.updates", loc(r.getLastLocation()));
                        }
                    }, Looper.getMainLooper());
        } catch (Throwable e) { put("10.gms.last", "ERR " + e); }
        // Bluetooth LE scan: beacons nearby are a location clue for SDKs.
        try {
            android.bluetooth.le.BluetoothLeScanner scanner = getSystemService(android.bluetooth.BluetoothManager.class).getAdapter().getBluetoothLeScanner();
            bleCallback = new android.bluetooth.le.ScanCallback() {
                @Override public void onScanResult(int type, android.bluetooth.le.ScanResult r) { bleResults++; bleDevices.add(r.getDevice().getAddress()); }
                @Override public void onBatchScanResults(List<android.bluetooth.le.ScanResult> rs) { for (android.bluetooth.le.ScanResult r : rs) onScanResult(0, r); }
                @Override public void onScanFailed(int code) { put("11.ble.scan", "FAILED " + code); }
            };
            if (scanner == null) put("11.ble.scan", "no scanner (Bluetooth off?)");
            else scanner.startScan(bleCallback);
        } catch (Throwable e) { put("11.ble.scan", "ERR " + e); }

        WifiManager wifi = getSystemService(WifiManager.class);
        try { wifi.startScan(); } catch (Throwable ignored) {}
        TelephonyManager tm = getSystemService(TelephonyManager.class);
        put("8.cell.callback", "no callback");
        try {
            tm.registerTelephonyCallback(main, new CellCallback());
        } catch (Throwable e) { put("8.cell.callback", "ERR " + e); }
        put("8.cell.requestUpdate", "no callback");
        try {
            tm.requestCellInfoUpdate(main, new TelephonyManager.CellInfoCallback() {
                @Override public void onCellInfo(List<CellInfo> cells) { put("8.cell.requestUpdate", cells(cells)); }
            });
        } catch (Throwable e) { put("8.cell.requestUpdate", "ERR " + e); }

        put("7.wifi.networkCallback", "no callback");
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            cm.registerNetworkCallback(new android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(),
                    new android.net.ConnectivityManager.NetworkCallback(android.net.ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                        @Override public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities nc) {
                            Object info = nc.getTransportInfo();
                            if (info instanceof WifiInfo) put("7.wifi.networkCallback", "ssid=" + ((WifiInfo) info).getSSID() + " bssid=" + ((WifiInfo) info).getBSSID());
                        }
                    });
        } catch (Throwable e) { put("7.wifi.networkCallback", "ERR " + e); }
        handler.postDelayed(() -> finish(lm, wifi, tm), 15000);
        view.setText("采集中，15 秒…");
    }

    private class CellCallback extends TelephonyCallback implements TelephonyCallback.CellInfoListener, TelephonyCallback.ServiceStateListener {
        @Override public void onCellInfoChanged(List<CellInfo> cells) { put("8.cell.callback", cells(cells)); }
        @Override public void onServiceStateChanged(android.telephony.ServiceState state) { put("8.cell.serviceStateCallback", serviceCells(state)); }
    }

    private static String serviceCells(android.telephony.ServiceState state) {
        if (state == null) return "null";
        StringBuilder b = new StringBuilder("op=" + state.getOperatorNumeric());
        for (android.telephony.NetworkRegistrationInfo n : state.getNetworkRegistrationInfoList()) {
            if (n.getCellIdentity() != null) b.append(" | ").append(n.getCellIdentity().toString().replaceAll("\\s+", " "));
        }
        return b.length() > 400 ? b.substring(0, 400) : b.toString();
    }

    private static String cells(List<CellInfo> cells) {
        if (cells == null) return "null";
        StringBuilder b = new StringBuilder("count=" + cells.size());
        for (CellInfo c : cells) b.append(" | ").append(c.getCellIdentity().toString().replaceAll("\\s+", " "));
        return b.length() > 400 ? b.substring(0, 400) : b.toString();
    }

    @SuppressWarnings({"MissingPermission", "deprecation"})
    private void finish(LocationManager lm, WifiManager wifi, TelephonyManager tm) {
        put("5.gnss", "callbacks=" + gnssCallbacks + " " + gnssSummary);
        put("6.nmea", "count=" + nmeaCount + " sample=" + nmeaSample);
        if (!results.containsKey("11.ble.scan")) {
            put("11.ble.scan", "results=" + bleResults + " devices=" + bleDevices.size() + " " + bleDevices);
            try { getSystemService(android.bluetooth.BluetoothManager.class).getAdapter().getBluetoothLeScanner().stopScan(bleCallback); } catch (Throwable ignored) {}
        }
        if (!results.containsKey("5.gnssRaw")) put("5.gnssRaw", "measurements=" + measurementCount + " " + measurementSample + " navMessages=" + navCount);
        try {
            List<ScanResult> scans = wifi.getScanResults();
            StringBuilder b = new StringBuilder("count=" + scans.size());
            for (ScanResult r : scans) b.append(" | ").append(r.SSID).append(" ").append(r.BSSID);
            put("7.wifi.scan", b.toString());
        } catch (Throwable e) { put("7.wifi.scan", "ERR " + e); }
        try {
            WifiInfo info = wifi.getConnectionInfo();
            put("7.wifi.connection", "ssid=" + info.getSSID() + " bssid=" + info.getBSSID());
        } catch (Throwable e) { put("7.wifi.connection", "ERR " + e); }
        try { put("8.cell.getAllCellInfo", cells(tm.getAllCellInfo())); } catch (Throwable e) { put("8.cell.getAllCellInfo", "ERR " + e); }
        try { put("8.cell.getCellLocation", String.valueOf(tm.getCellLocation())); } catch (Throwable e) { put("8.cell.getCellLocation", "ERR " + e); }
        try { put("8.cell.getServiceState", serviceCells(tm.getServiceState())); } catch (Throwable e) { put("8.cell.getServiceState", "ERR " + e); }
        for (String p : new String[]{"gps", "network", "fused"}) {
            try { put("9.lastAfter." + p, loc(lm.getLastKnownLocation(p))); } catch (Throwable e) { put("9.lastAfter." + p, "ERR " + e); }
        }
        StringBuilder all = new StringBuilder();
        for (Map.Entry<String, String> e : results.entrySet()) {
            String line = e.getKey() + " = " + e.getValue();
            Log.i(TAG, line);
            all.append(line).append('\n');
        }
        Log.i(TAG, "DONE");
        view.setText(all);
    }
}

package app.geoshift;

import android.location.GnssStatus;
import android.net.wifi.ScanResult;
import android.os.SystemClock;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import de.robv.android.xposed.XposedHelpers;

/**
 * Builds the simulated Wi-Fi, cell and satellite data. Shared by system_server, the phone process
 * and app-process hooks so every path reports the same environment.
 */
final class FakeEnvironment {
    private static final int MAX_CELLS = 32;

    private FakeEnvironment() {}

    /** Settings say the real environment (Wi-Fi, cells, satellites) should be hidden while simulating. */
    static boolean spoofEnvironment(JSONObject settings) {
        return settings != null && (settings.optBoolean("spoofEnvironment", true) || settings.optBoolean("protectWifi"));
    }

    // ---- Wi-Fi --------------------------------------------------------------------------------

    /** The configured Wi-Fi preset, or null when Wi-Fi simulation is off or incomplete. */
    static JSONObject wifiPreset(JSONObject config) {
        JSONObject r = config.optJSONObject("runtime");
        if (r == null || !r.optBoolean("wifiEnabled")) return null;
        JSONObject w = r.optJSONObject("wifi");
        if (w == null || w.optString("ssid").isEmpty() || w.optString("bssid").isEmpty()) return null;
        return w;
    }

    /** Scan results an app should see: the preset access point, or nothing. */
    static List<ScanResult> scanResults(JSONObject config) {
        List<ScanResult> list = new ArrayList<>();
        JSONObject w = wifiPreset(config);
        if (w == null) return list;
        // The public constructor arrived in Android 11; earlier versions have the same one hidden.
        ScanResult result = android.os.Build.VERSION.SDK_INT >= 30 ? new ScanResult()
                : (ScanResult) XposedHelpers.newInstance(ScanResult.class);
        result.SSID = w.optString("ssid");
        result.BSSID = w.optString("bssid").toLowerCase(java.util.Locale.ROOT);
        result.capabilities = "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]";
        result.level = -48;
        result.frequency = 2437;
        result.timestamp = SystemClock.elapsedRealtime() * 1000;
        Object ssid = wifiSsid(result.SSID);
        if (ssid != null) {
            try { XposedHelpers.callMethod(result, "setWifiSsid", ssid); } catch (Throwable ignored) {}
        }
        list.add(result);
        return list;
    }

    /** android.net.wifi.WifiSsid for the name, or null if the platform has no usable factory. */
    static Object wifiSsid(String name) {
        try {
            Class<?> type = Class.forName("android.net.wifi.WifiSsid");
            try {
                return XposedHelpers.callStaticMethod(type, "fromBytes", (Object) name.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable older) {
                return XposedHelpers.callStaticMethod(type, "createFromAsciiEncoded", name);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    // ---- Cells --------------------------------------------------------------------------------

    /** True when the user configured simulated cells. */
    static boolean cellsConfigured(JSONObject config) {
        JSONObject r = config.optJSONObject("runtime");
        return r != null && r.optBoolean("cellEnabled");
    }

    /** Simulated cells, or an empty list when cell simulation is off (hides the real towers). */
    static List<Object> cellsOrEmpty(JSONObject config) throws Throwable {
        return cellsConfigured(config) ? cells(config) : new ArrayList<>();
    }

    static List<Object> cells(JSONObject config) throws Throwable {
        JSONObject r = config.getJSONObject("runtime");
        JSONArray values = r.optJSONArray("cells");
        List<Object> result = new ArrayList<>();
        if (values != null && values.length() > 0) {
            for (int i = 0; i < Math.min(MAX_CELLS, values.length()); i++) result.add(cell(values.getJSONObject(i)));
        } else {
            result.add(cell(r.getJSONObject("cell")));
        }
        return result;
    }

    /** Identity of the first simulated cell, or an identity with every field unavailable. */
    static Object cellIdentityOrEmpty(JSONObject config) throws Throwable {
        if (cellsConfigured(config)) {
            List<Object> cells = cells(config);
            if (!cells.isEmpty()) return XposedHelpers.callMethod(cells.get(0), "getCellIdentity");
        }
        return XposedHelpers.newInstance(android.telephony.CellIdentityLte.class);
    }

    /**
     * Copy of a ServiceState with the real serving-cell identities removed, the same way Android
     * does it for apps without location permission. With a cell preset, registrations that carried
     * an identity get the preset one instead, so this matches what getAllCellInfo reports.
     */
    static Object serviceState(Object state, JSONObject config) throws Throwable {
        Object copy = XposedHelpers.callMethod(state, "createLocationInfoSanitizedCopy", false);
        if (!cellsConfigured(config)) return copy;
        Object identity = cellIdentityOrEmpty(config);
        List<?> real = (List<?>) XposedHelpers.getObjectField(state, "mNetworkRegistrationInfos");
        List<?> safe = (List<?>) XposedHelpers.getObjectField(copy, "mNetworkRegistrationInfos");
        for (int i = 0; i < Math.min(real.size(), safe.size()); i++) {
            if (XposedHelpers.getObjectField(real.get(i), "mCellIdentity") != null)
                XposedHelpers.setObjectField(safe.get(i), "mCellIdentity", identity);
        }
        return copy;
    }

    private static void field(Object object, String name, Object value) throws Throwable {
        Field f = XposedHelpers.findField(object.getClass(), name);
        f.set(object, value);
    }

    static Object cell(JSONObject v) throws Throwable {
        String type = v.optString("networkType", "LTE");
        boolean lte = "LTE".equals(type);
        if (!lte && !"GSM".equals(type)) throw new IllegalArgumentException("Only GSM/LTE supported");
        Object info = XposedHelpers.newInstance(lte ? CellInfoLte.class : CellInfoGsm.class);
        Object identity = XposedHelpers.callMethod(info, "getCellIdentity");
        Object signal = XposedHelpers.callMethod(info, "getCellSignalStrength");
        try {
            field(identity, "mMccStr", v.getString("mcc"));
            field(identity, "mMncStr", v.getString("mnc"));
        } catch (NoSuchFieldError old) {
            field(identity, "mMcc", Integer.parseInt(v.getString("mcc")));
            field(identity, "mMnc", Integer.parseInt(v.getString("mnc")));
        }
        int cid = v.getInt("cid");
        field(identity, lte ? "mCi" : "mCid", cid);
        field(identity, lte ? "mTac" : "mLac", v.getInt(lte ? "tac" : "lac"));
        if (lte) {
            field(identity, "mPci", v.getInt("pci"));
            field(identity, "mEarfcn", v.optInt("earfcn", 0));
            field(signal, "mRsrp", v.optInt("rssi", -85));
        } else {
            field(identity, "mArfcn", v.optInt("arfcn", 0));
            field(signal, "mRssi", v.optInt("rssi", -85));
        }
        XposedHelpers.callMethod(info, "setRegistered", v.optBoolean("registered", true));
        long now = SystemClock.elapsedRealtimeNanos();
        try {
            XposedHelpers.callMethod(info, "setTimeStamp", now);
        } catch (Throwable timestamp) {
            field(info, "mTimeStamp", now);
        }
        int read = ((Number) XposedHelpers.callMethod(identity, lte ? "getCi" : "getCid")).intValue();
        if (read != cid) throw new IllegalStateException("Cell identity readback mismatch");
        return info;
    }

    // ---- GNSS ---------------------------------------------------------------------------------

    // Constellation, its carrier frequency in Hz, and the highest valid satellite id.
    private static final int[] CONSTELLATIONS = {GnssStatus.CONSTELLATION_GPS, GnssStatus.CONSTELLATION_BEIDOU,
            GnssStatus.CONSTELLATION_GALILEO, GnssStatus.CONSTELLATION_GLONASS};
    private static final float[] CARRIER_HZ = {1575.42e6f, 1561.098e6f, 1575.42e6f, 1602.0e6f};
    private static final int[] MAX_SVID = {32, 63, 36, 24};

    /** One simulated satellite. */
    static final class Sat {
        final int constellation, svid;
        final float carrierHz;
        final double elevation, azimuth, cn0;
        final boolean used;

        Sat(int constellation, int svid, float carrierHz, double elevation, double azimuth, double cn0, boolean used) {
            this.constellation = constellation;
            this.svid = svid;
            this.carrierHz = carrierHz;
            this.elevation = elevation;
            this.azimuth = azimuth;
            this.cn0 = cn0;
            this.used = used;
        }
    }

    /**
     * Simulated sky. Satellites keep fixed ids but drift across the sky over minutes and their signal
     * strength wobbles, as a real receiver would show; values follow the clock, so all apps agree.
     * GnssStatus and NMEA are both built from this, so they always match.
     */
    static java.util.List<Sat> sky(JSONObject config) {
        int count = satelliteCount(config), used = satellitesUsed(count);
        double minutes = SystemClock.elapsedRealtime() / 60000.0;
        java.util.List<Sat> out = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int k = i % CONSTELLATIONS.length;
            int svid = 1 + (i / CONSTELLATIONS.length * 7 + k * 3) % MAX_SVID[k];
            float carrier = CARRIER_HZ[k];
            // GLONASS satellites each use their own channel, 0.5625 MHz apart.
            if (CONSTELLATIONS[k] == GnssStatus.CONSTELLATION_GLONASS) carrier += (svid % 14 - 7) * 0.5625e6f;
            double elevation = elevation(i, minutes);
            double azimuth = ((i * 137) % 360 + minutes * 0.5 * (i % 2 == 0 ? 1 : -1) + 360) % 360;
            out.add(new Sat(CONSTELLATIONS[k], svid, carrier, elevation, azimuth, cn0(i, minutes), i < used));
        }
        return out;
    }

    @androidx.annotation.RequiresApi(30)
    static GnssStatus gnss(JSONObject config) {
        GnssStatus.Builder b = new GnssStatus.Builder();
        for (Sat t : sky(config)) {
            b.addSatellite(t.constellation, t.svid, (float) t.cn0, (float) t.elevation, (float) t.azimuth,
                    true, true, t.used, true, t.carrierHz, false, 0);
        }
        return b.build();
    }

    static int satelliteCount(JSONObject config) {
        JSONObject s = config.optJSONObject("settings");
        return Math.max(4, Math.min(32, s == null ? 12 : s.optInt("satelliteCount", 12)));
    }

    static int satellitesUsed(int count) { return Math.min(count, Math.max(4, count * 3 / 4)); }

    private static double elevation(int i, double minutes) { return 15 + (i * 37) % 70 + 6 * Math.sin(minutes / 40.0 + i); }

    /** Higher satellites are received more strongly. */
    private static double cn0(int i, double minutes) {
        return 18 + elevation(i, minutes) * 0.28 + 2.5 * FakeLocation.wave(minutes * 60, i * 1.7);
    }

    /**
     * Extras a real GNSS fix carries (AOSP GnssLocationProvider): satellites used in the fix and their
     * signal strength. Location SDKs read them to weigh GPS against their own Wi-Fi positioning, and they
     * must agree with the simulated sky.
     */
    static android.os.Bundle gpsExtras(JSONObject config) {
        int used = satellitesUsed(satelliteCount(config));
        double minutes = SystemClock.elapsedRealtime() / 60000.0, sum = 0, max = 0;
        for (int i = 0; i < used; i++) {
            double cn0 = cn0(i, minutes);
            sum += cn0;
            max = Math.max(max, cn0);
        }
        android.os.Bundle extras = new android.os.Bundle();
        extras.putInt("satellites", used);
        extras.putInt("meanCn0", (int) Math.round(sum / used));
        extras.putInt("maxCn0", (int) Math.round(max));
        return extras;
    }
}

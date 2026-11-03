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
        ScanResult result = new ScanResult();
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

    @androidx.annotation.RequiresApi(30)
    static GnssStatus gnss(JSONObject config) {
        JSONObject s = config.optJSONObject("settings");
        int count = Math.max(4, Math.min(32, s == null ? 12 : s.optInt("satelliteCount", 12)));
        GnssStatus.Builder b = new GnssStatus.Builder();
        for (int i = 0; i < count; i++) {
            b.addSatellite(i % 2 == 0 ? GnssStatus.CONSTELLATION_GPS : GnssStatus.CONSTELLATION_BEIDOU,
                    i + 1, 26 + i % 12, 15 + (i * 7) % 70, (i * 137) % 360,
                    true, true, i < Math.min(12, count), true, 1575420000f, false, 0);
        }
        return b.build();
    }
}

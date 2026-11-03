package app.geoshift;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real Wi-Fi and cell scans of the phone's current physical radio environment. */
public final class DeviceScanner {
    private static final long CELL_REFRESH_TIMEOUT_MS = 2500;

    private DeviceScanner() {}

    private static void requireLocation(Context c) throws Exception {
        if (!PortalStore.preciseLocationGranted(c)) throw new Exception("请先授予精确位置权限。");
        if (!PortalStore.locationEnabled(c)) throw new Exception("请打开系统定位开关后重试。");
    }

    public static JSONObject wifi(Context c) throws Exception {
        requireLocation(c);
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) throw new Exception("请先开启 Wi-Fi。Android 会限制扫描刷新频率。");
        boolean accepted;
        List<ScanResult> list;
        try {
            accepted = wm.startScan();
            list = wm.getScanResults();
        } catch (SecurityException denied) {
            throw new Exception("Wi-Fi 扫描权限不可用，请检查精确位置和附近 Wi-Fi 权限。", denied);
        }
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        if (list != null) {
            for (ScanResult s : list) {
                if (!seen.add(s.BSSID)) continue;
                out.put(PortalStore.obj("ssid", s.SSID, "bssid", s.BSSID, "rssi", s.level,
                        "frequency", s.frequency, "timestamp", s.timestamp));
            }
        }
        return PortalStore.obj("results", out, "cached", true, "refreshRequested", accepted,
                "message", "显示系统缓存；系统会异步刷新并限制扫描频率，稍后可再次查看。");
    }

    public static JSONObject cells(Context c) throws Exception {
        requireLocation(c);
        if (!c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            throw new Exception("此设备没有蜂窝通信硬件。");
        TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) throw new Exception("系统电话服务不可用。");

        List<TelephonyManager> managers = new ArrayList<>();
        if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                SubscriptionManager sm = (SubscriptionManager) c.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                List<SubscriptionInfo> subs = sm == null ? null : sm.getActiveSubscriptionInfoList();
                if (subs != null) for (SubscriptionInfo sub : subs) managers.add(tm.createForSubscriptionId(sub.getSubscriptionId()));
            } catch (SecurityException ignored) {}
        }
        if (managers.isEmpty()) managers.add(tm);

        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        boolean refreshed = false;
        for (TelephonyManager manager : managers) {
            List<CellInfo> cells = Build.VERSION.SDK_INT >= 29 ? refresh(manager) : null;
            refreshed |= cells != null;
            if (cells == null) cells = manager.getAllCellInfo();
            if (cells == null) continue;
            for (CellInfo cell : cells) {
                JSONObject item = parse(cell);
                if (item != null && seen.add(item.toString())) out.put(item);
            }
        }
        return PortalStore.obj("results", out, "cached", !refreshed, "message", out.length() == 0
                ? "系统没有返回基站信息。请检查 SIM、精确位置权限和网络状态。"
                : "已注册小区与系统可见邻区；是否返回 5G、邻区和刷新结果由手机决定。");
    }

    /** Asks the modem for fresh cell info; returns null on timeout or error. */
    @androidx.annotation.RequiresApi(29)
    private static List<CellInfo> refresh(TelephonyManager manager) {
        AtomicReference<List<CellInfo>> answer = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        try {
            manager.requestCellInfoUpdate(Runnable::run, new TelephonyManager.CellInfoCallback() {
                @Override public void onCellInfo(List<CellInfo> values) {
                    answer.set(values);
                    latch.countDown();
                }

                @Override public void onError(int code, Throwable detail) {
                    latch.countDown();
                }
            });
            latch.await(CELL_REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception ignored) {}
        return answer.get();
    }

    private static Object available(int n) {
        return n == Integer.MAX_VALUE ? JSONObject.NULL : n;
    }

    private static JSONObject parse(CellInfo value) {
        try {
            JSONObject out = PortalStore.obj("registered", value.isRegistered(), "timestamp",
                    Build.VERSION.SDK_INT >= 30 ? value.getTimestampMillis() : value.getTimeStamp() / 1000000);
            if (value instanceof CellInfoLte) {
                CellIdentityLte i = ((CellInfoLte) value).getCellIdentity();
                out.put("networkType", "LTE");
                out.put("mcc", Build.VERSION.SDK_INT >= 28 ? i.getMccString() : String.valueOf(i.getMcc()));
                out.put("mnc", Build.VERSION.SDK_INT >= 28 ? i.getMncString() : String.valueOf(i.getMnc()));
                out.put("cid", available(i.getCi()));
                out.put("tac", available(i.getTac()));
                out.put("pci", available(i.getPci()));
                out.put("earfcn", available(i.getEarfcn()));
                out.put("rssi", ((CellInfoLte) value).getCellSignalStrength().getDbm());
            } else if (value instanceof CellInfoGsm) {
                CellIdentityGsm i = ((CellInfoGsm) value).getCellIdentity();
                out.put("networkType", "GSM");
                out.put("mcc", Build.VERSION.SDK_INT >= 28 ? i.getMccString() : String.valueOf(i.getMcc()));
                out.put("mnc", Build.VERSION.SDK_INT >= 28 ? i.getMncString() : String.valueOf(i.getMnc()));
                out.put("cid", available(i.getCid()));
                out.put("lac", available(i.getLac()));
                out.put("rssi", ((CellInfoGsm) value).getCellSignalStrength().getDbm());
            } else if (value instanceof CellInfoWcdma) {
                CellIdentityWcdma i = ((CellInfoWcdma) value).getCellIdentity();
                out.put("networkType", "WCDMA");
                out.put("mcc", Build.VERSION.SDK_INT >= 28 ? i.getMccString() : String.valueOf(i.getMcc()));
                out.put("mnc", Build.VERSION.SDK_INT >= 28 ? i.getMncString() : String.valueOf(i.getMnc()));
                out.put("cid", available(i.getCid()));
                out.put("lac", available(i.getLac()));
                out.put("psc", available(i.getPsc()));
                out.put("rssi", ((CellInfoWcdma) value).getCellSignalStrength().getDbm());
            } else if (Build.VERSION.SDK_INT >= 29 && value instanceof CellInfoNr) {
                CellIdentityNr i = (CellIdentityNr) ((CellInfoNr) value).getCellIdentity();
                out.put("networkType", "NR");
                out.put("mcc", i.getMccString());
                out.put("mnc", i.getMncString());
                out.put("nci", i.getNci() == Long.MAX_VALUE ? JSONObject.NULL : String.valueOf(i.getNci()));
                out.put("tac", available(i.getTac()));
                out.put("pci", available(i.getPci()));
                out.put("rssi", ((CellInfoNr) value).getCellSignalStrength().getDbm());
            } else if (value instanceof CellInfoCdma) {
                CellIdentityCdma i = ((CellInfoCdma) value).getCellIdentity();
                out.put("networkType", "CDMA");
                out.put("cid", available(i.getBasestationId()));
                out.put("networkId", available(i.getNetworkId()));
                out.put("systemId", available(i.getSystemId()));
                out.put("rssi", ((CellInfoCdma) value).getCellSignalStrength().getDbm());
            } else {
                return null;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}

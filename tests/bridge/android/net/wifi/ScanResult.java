package android.net.wifi;
public final class ScanResult {
    public String SSID, BSSID, capabilities;
    public int level, frequency;
    public long timestamp;
    public void setWifiSsid(WifiSsid ssid){SSID=ssid.name;}
}

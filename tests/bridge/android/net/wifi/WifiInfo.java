package android.net.wifi;
public class WifiInfo {
    public String bssid="11:22:33:44:55:66";
    public WifiInfo(){}
    public WifiInfo(WifiInfo o){bssid=o.bssid;}
    public void setBSSID(String b){bssid=b;}
    public String getBSSID(){return bssid;}
}

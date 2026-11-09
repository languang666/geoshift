package android.net.wifi;
public class WifiInfo {
    public String bssid="11:22:33:44:55:66";
    public WifiSsid ssid;
    public int frequency, rssi, networkId=-1;
    public java.net.InetAddress mIpAddress;
    public String passpointName, mloAddress;
    public static boolean failConstruction;
    public WifiInfo(){if(failConstruction)throw new IllegalStateException("injected construction failure");}
    public WifiInfo(WifiInfo o){bssid=o.bssid;}
    public void setBSSID(String b){bssid=b;}
    public String getBSSID(){return bssid;}
    public void setSSID(WifiSsid value){ssid=value;}
    public String getSSID(){return ssid==null?"<unknown ssid>":ssid.name;}
    public void setFrequency(int value){frequency=value;}
    public int getFrequency(){return frequency;}
    public int getRssi(){return rssi;}
    public int getNetworkId(){return networkId;}
    public void setNetworkId(int value){networkId=value;}
    public void setRssi(int value){rssi=value;}
    public String getSupplicantState(){return "COMPLETED";}
    public void setSupplicantState(String state){}
}

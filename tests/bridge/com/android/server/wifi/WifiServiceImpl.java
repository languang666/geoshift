package com.android.server.wifi;
import java.util.*;
public final class WifiServiceImpl {
    public static final class Slice {
        public static boolean rejectSimulated, rejectAll;
        public final List<?> values;
        public Slice(List<?> values){
            if(rejectAll||(rejectSimulated&&!values.isEmpty()&&values.get(0) instanceof android.net.wifi.ScanResult))
                throw new IllegalStateException("injected reply wrapping failure");
            this.values=values;
        }
    }
    public static final class Info extends android.net.wifi.WifiInfo {
        private String bssid="11:22:33:44:55:66";
        public Info(){}
        public Info(Info original){bssid=original.bssid;}
        public void setBSSID(String value){bssid=value;}
        public String getBSSID(){return bssid;}
    }
    public final Info realInfo=new Info();
    public final Slice realScans=new Slice(Arrays.asList("physical AP"));
    public Slice getScanResults(){return realScans;}
    public Info getConnectionInfo(){return realInfo;}
}

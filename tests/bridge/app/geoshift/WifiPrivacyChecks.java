package app.geoshift;
import com.android.server.wifi.WifiServiceImpl;
import de.robv.android.xposed.XposedBridge;
public final class WifiPrivacyChecks {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static final class Control implements WifiPrivacyHooks.Controller {
        boolean active;
        String stage;
        int errors;
        // Simulating without a Wi-Fi preset: real access points are hidden.
        final org.json.JSONObject config=new org.json.JSONObject().put("runtime",new org.json.JSONObject().put("wifiEnabled",false));
        public org.json.JSONObject activeFor(int uid){return active&&uid>=10000?config:null;}
        public void report(Throwable error,String stage,String detail){if(error!=null)errors++;this.stage=stage;}
    }
    public static void main(String[] args)throws Throwable {
        XposedBridge.reset();Control c=new Control();new WifiPrivacyHooks(c).install(WifiPrivacyChecks.class.getClassLoader());
        check("installed".equals(c.stage),"service entry installed");
        WifiServiceImpl service=new WifiServiceImpl();
        service.realInfo.setSSID(android.net.wifi.WifiSsid.fromBytes("real home".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        service.realInfo.frequency=5745;service.realInfo.rssi=-70;service.realInfo.networkId=7;service.realInfo.mIpAddress=java.net.InetAddress.getByAddress(new byte[]{(byte)192,(byte)168,1,23});
        service.realInfo.passpointName="real operator";service.realInfo.mloAddress="real MLO address";
        Object original=XposedBridge.invoke(service,"getScanResults",new Class<?>[0]);
        check(original==service.realScans,"untouched while not simulating");
        c.active=true;
        WifiServiceImpl.Slice hidden=(WifiServiceImpl.Slice)XposedBridge.invoke(service,"getScanResults",new Class<?>[0]);
        check(hidden.values.isEmpty(),"Android-style slice scan result filtered");
        check(service.realScans.values.size()==1,"service scan cache is untouched");
        WifiServiceImpl.Info copy=(WifiServiceImpl.Info)XposedBridge.invoke(service,"getConnectionInfo",new Class<?>[0]);
        check(copy!=service.realInfo&&copy.getBSSID().equals("02:00:00:00:00:00"),"connection reply copy is masked");
        check(copy.getSSID().equals("<unknown ssid>"),"no preset hides the real SSID");
        check(copy.passpointName==null&&copy.mloAddress==null,"no copied Passpoint or MLO identity");
        check(service.realInfo.getBSSID().equals("11:22:33:44:55:66"),"physical connection object is untouched");
        check(copy.networkId==7&&copy.rssi==-70&&copy.frequency==5745&&copy.mIpAddress==service.realInfo.mIpAddress,"link fields kept so Wi-Fi still counts as connected");
        android.net.connectivity.com.android.server.ConnectivityService cs=new android.net.connectivity.com.android.server.ConnectivityService();
        android.net.NetworkCapabilities nc=new android.net.NetworkCapabilities().setTransportInfo(new android.net.wifi.WifiInfo());
        Class<?>[] sig={android.net.NetworkCapabilities.class,boolean.class,int.class,int.class,String.class,String.class};
        android.net.NetworkCapabilities out=(android.net.NetworkCapabilities)XposedBridge.invoke(cs,"createWithLocationInfoSanitizedIfNecessaryWhenParceled",sig,nc,true,1,10123,"app",null);
        check(out!=nc&&((android.net.wifi.WifiInfo)out.getTransportInfo()).getBSSID().equals("02:00:00:00:00:00"),"network capabilities Wi-Fi masked");
        check(((android.net.wifi.WifiInfo)nc.getTransportInfo()).getBSSID().equals("11:22:33:44:55:66"),"service capabilities untouched");
        check(XposedBridge.invoke(cs,"createWithLocationInfoSanitizedIfNecessaryWhenParceled",sig,nc,true,1,1000,"android",null)==nc,"system callers keep real Wi-Fi");
        org.json.JSONObject preset=new org.json.JSONObject().put("ssid","test AP").put("bssid","AA:BB:CC:DD:EE:FF").put("frequency",5180).put("rssi",-61);
        c.config.getJSONObject("runtime").put("wifiEnabled",true).put("wifi",preset);
        copy=(WifiServiceImpl.Info)XposedBridge.invoke(service,"getConnectionInfo",new Class<?>[0]);
        android.net.wifi.ScanResult scan=(android.net.wifi.ScanResult)((WifiServiceImpl.Slice)XposedBridge.invoke(service,"getScanResults",new Class<?>[0])).values.get(0);
        check(copy.getSSID().equals(scan.SSID)&&copy.getBSSID().equals(scan.BSSID),"scan and connection share preset identity");
        check(copy.frequency==scan.frequency&&copy.frequency==5180&&copy.rssi==scan.level&&copy.rssi==-61,"scan and connection share frequency and RSSI");
        out=(android.net.NetworkCapabilities)XposedBridge.invoke(cs,"createWithLocationInfoSanitizedIfNecessaryWhenParceled",sig,nc,true,1,10123,"app",null);
        android.net.wifi.WifiInfo transport=(android.net.wifi.WifiInfo)out.getTransportInfo();
        check(transport.getSSID().equals(copy.getSSID())&&transport.frequency==copy.frequency&&transport.rssi==copy.rssi,"network callback uses the same preset");
        check(copy.networkId==7&&copy.mIpAddress!=null,"preset keeps network id and IP");
        android.net.wifi.WifiSsid.fail=true;
        try {
            Object blank=XposedBridge.invoke(service,"getConnectionInfo",new Class<?>[0]);
            check(blank instanceof WifiServiceImpl.Info&&blank!=service.realInfo,"failed rewrite returns an empty WifiInfo, not null");
            check(((WifiServiceImpl.Info)blank).getSSID().equals("<unknown ssid>")&&((WifiServiceImpl.Info)blank).networkId==-1,"empty WifiInfo carries no real identity");
        } finally {android.net.wifi.WifiSsid.fail=false;}
        check(service.realInfo.getSSID().equals("real home")&&service.realInfo.frequency==5745&&service.realInfo.rssi==-70,"original connection fields remain intact");
        android.net.wifi.WifiInfo.failConstruction=true;
        try {
            check(XposedBridge.invoke(service,"getConnectionInfo",new Class<?>[0])==null,"construction failure does not return the real connection");
            out=(android.net.NetworkCapabilities)XposedBridge.invoke(cs,"createWithLocationInfoSanitizedIfNecessaryWhenParceled",sig,nc,true,1,10123,"app",null);
            check(out!=null&&out.getTransportInfo()==null,"construction failure keeps capabilities without real transport info");
        } finally {android.net.wifi.WifiInfo.failConstruction=false;}
        check(c.errors==3,"rewrite and construction failures reported");
        WifiServiceImpl.Slice.rejectSimulated=true;
        try {
            hidden=(WifiServiceImpl.Slice)XposedBridge.invoke(service,"getScanResults",new Class<?>[0]);
            check(hidden.values.isEmpty(),"failed preset wrapping falls back to an empty reply");
            WifiServiceImpl.Slice.rejectAll=true;
            check(XposedBridge.invoke(service,"getScanResults",new Class<?>[0])==null,"unsupported empty wrapper never returns real access points");
        } finally {WifiServiceImpl.Slice.rejectSimulated=false;WifiServiceImpl.Slice.rejectAll=false;}
        check(c.errors==5,"scan wrapping failures reported");
        preset.put("frequency",-1).put("rssi",999);
        check(FakeEnvironment.wifiFrequency(preset)==2437&&FakeEnvironment.wifiRssi(preset)==0,"invalid radio fields bounded");
        c.active=false;
        check(XposedBridge.invoke(service,"getScanResults",new Class<?>[0])==service.realScans,"stop restores original scan replies");
        check(XposedBridge.invoke(service,"getConnectionInfo",new Class<?>[0])==service.realInfo,"stop restores original connection reply");
        XposedBridge.reset();Control late=new Control();
        ClassLoader absent=new ClassLoader(WifiPrivacyChecks.class.getClassLoader()){
            @Override public Class<?> loadClass(String name)throws ClassNotFoundException{
                if(name.equals("com.android.server.wifi.WifiServiceImpl"))throw new ClassNotFoundException(name);return super.loadClass(name);
            }
        };
        new WifiPrivacyHooks(late).install(absent);check("waiting".equals(late.stage),"APEX may be loaded later");
        XposedBridge.invoke(WifiPrivacyChecks.class.getClassLoader(),"loadClass",new Class<?>[]{String.class},"com.android.server.wifi.WifiServiceImpl");
        check("installed".equals(late.stage),"late APEX service discovery");
        System.out.println("PASS: "+checks+" system Wi-Fi privacy checks (simulated hook dispatcher).");
    }
}

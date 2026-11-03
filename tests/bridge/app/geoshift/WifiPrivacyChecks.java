package app.geoshift;
import com.android.server.wifi.WifiServiceImpl;
import de.robv.android.xposed.XposedBridge;
public final class WifiPrivacyChecks {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static final class Control implements WifiPrivacyHooks.Controller {
        boolean active;
        String stage;
        // Simulating without a Wi-Fi preset: real access points are hidden.
        final org.json.JSONObject config=new org.json.JSONObject().put("runtime",new org.json.JSONObject().put("wifiEnabled",false));
        public org.json.JSONObject activeFor(int uid){return active&&uid>=10000?config:null;}
        public void report(Throwable error,String stage,String detail){if(error!=null)throw new AssertionError(error);this.stage=stage;}
    }
    public static void main(String[] args)throws Throwable {
        XposedBridge.reset();Control c=new Control();new WifiPrivacyHooks(c).install(WifiPrivacyChecks.class.getClassLoader());
        check("installed".equals(c.stage),"service entry installed");
        WifiServiceImpl service=new WifiServiceImpl();
        Object original=XposedBridge.invoke(service,"getScanResults",new Class<?>[0]);
        check(original==service.realScans,"untouched while not simulating");
        c.active=true;
        WifiServiceImpl.Slice hidden=(WifiServiceImpl.Slice)XposedBridge.invoke(service,"getScanResults",new Class<?>[0]);
        check(hidden.values.isEmpty(),"Android-style slice scan result filtered");
        check(service.realScans.values.size()==1,"service scan cache is untouched");
        WifiServiceImpl.Info copy=(WifiServiceImpl.Info)XposedBridge.invoke(service,"getConnectionInfo",new Class<?>[0]);
        check(copy!=service.realInfo&&copy.getBSSID().equals("02:00:00:00:00:00"),"connection reply copy is masked");
        check(service.realInfo.getBSSID().equals("11:22:33:44:55:66"),"physical connection object is untouched");
        android.net.connectivity.com.android.server.ConnectivityService cs=new android.net.connectivity.com.android.server.ConnectivityService();
        android.net.NetworkCapabilities nc=new android.net.NetworkCapabilities().setTransportInfo(new android.net.wifi.WifiInfo());
        Class<?>[] sig={android.net.NetworkCapabilities.class,boolean.class,int.class,int.class,String.class,String.class};
        android.net.NetworkCapabilities out=(android.net.NetworkCapabilities)XposedBridge.invoke(cs,"createWithLocationInfoSanitizedIfNecessaryWhenParceled",sig,nc,true,1,10123,"app",null);
        check(out!=nc&&((android.net.wifi.WifiInfo)out.getTransportInfo()).getBSSID().equals("02:00:00:00:00:00"),"network capabilities Wi-Fi masked");
        check(((android.net.wifi.WifiInfo)nc.getTransportInfo()).getBSSID().equals("11:22:33:44:55:66"),"service capabilities untouched");
        check(XposedBridge.invoke(cs,"createWithLocationInfoSanitizedIfNecessaryWhenParceled",sig,nc,true,1,1000,"android",null)==nc,"system callers keep real Wi-Fi");
        c.active=false;
        check(XposedBridge.invoke(service,"getScanResults",new Class<?>[0])==service.realScans,"stop restores original scan replies");
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

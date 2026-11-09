package app.geoshift;

import android.location.ILocationListener;
import android.location.Location;
import com.android.internal.telephony.IPhoneStateListener;
import de.robv.android.xposed.XposedBridge;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/** Exercises production callback hooks, ownership tracking and malformed-config failure paths. */
public final class SystemSpoofChecks {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static final class Control implements SystemSpoofHooks.Controller {
        JSONObject config;
        int errors;
        public JSONObject location(){return config;}
        public JSONObject cells(){return config;}
        public JSONObject gnss(){return null;}
        public boolean covers(JSONObject config,int uid){return uid==10123;}
        public Location realFix(String provider){return null;}
        public void report(String stage,String detail){if("error".equals(stage))errors++;}
    }
    public static void main(String[] args)throws Throwable {
        XposedBridge.reset();
        Control control=new Control();
        new SystemSpoofHooks(control).install(SystemSpoofChecks.class.getClassLoader());
        ILocationListener.Stub.Proxy listener=new ILocationListener.Stub.Proxy();
        Location real=new Location("gps");real.setLatitude(39);real.setLongitude(116);
        Class<?>[] locationSignature={List.class};
        List<Location> realList=Collections.singletonList(real);
        XposedBridge.invoke(listener,"onLocationChanged",locationSignature,realList);
        check(listener.delivered==realList,"inactive leaves real callback unchanged");
        control.config=new JSONObject().put("settings",new JSONObject()).put("runtime",new JSONObject().put("lat",30).put("lon",120));
        XposedBridge.invoke(listener,"onLocationChanged",locationSignature,realList);
        check(listener.delivered!=realList&&Math.abs(listener.delivered.get(0).getLatitude()-30)<0.001,"callback replaces real position");
        check(realList.get(0)==real&&real.getLatitude()==39,"real callback packet is not mutated");
        int calls=listener.calls;
        control.config=new JSONObject().put("settings",new JSONObject()); // Missing runtime: generation fails.
        XposedBridge.invoke(listener,"onLocationChanged",locationSignature,realList);
        check(listener.calls==calls,"generation failure drops callback instead of real position");
        IPhoneStateListener.Stub.Proxy phone=new IPhoneStateListener.Stub.Proxy();
        control.config=new JSONObject().put("runtime",new JSONObject().put("cellEnabled",true)); // Missing cell preset.
        XposedBridge.invoke(phone,"onCellInfoChanged",locationSignature,Collections.singletonList("real tower"));
        check(phone.calls==0,"malformed cell preset does not deliver real towers");
        control.config.getJSONObject("runtime").put("cellEnabled",false);
        XposedBridge.invoke(phone,"onCellInfoChanged",locationSignature,Collections.singletonList("real tower"));
        check(phone.calls==1&&phone.cells.isEmpty(),"no preset hides towers with an empty list");
        control.config.put("settings",new JSONObject().put("appScope","selected"));
        int errors=control.errors;
        XposedBridge.invoke(phone,"onCellInfoChanged",locationSignature,Collections.singletonList("real tower"));
        check(control.errors==errors+1,"unknown owner emits a diagnostic");
        check(phone.cells.size()==1,"unknown owner does not expand selected scope");
        CallbackOwners.record(phone,10123);
        check(CallbackOwners.ownerOf(phone)==10123,"registered callback owner resolves");
        XposedBridge.invoke(phone,"onCellInfoChanged",locationSignature,Collections.singletonList("real tower"));
        check(phone.cells.isEmpty(),"known selected owner receives sanitized cells");
        // Xiaomi LocationResult replies can be shared by selected and unselected callers.
        control.config.put("runtime",new JSONObject().put("lat",30).put("lon",120));
        com.android.server.location.MiuiBlurLocationManager miui=new com.android.server.location.MiuiBlurLocationManager();
        miui.cached=android.location.LocationResult.create(realList);
        Class<?>[] miuiSignature={int.class};
        Object masked=XposedBridge.invoke(miui,"getBlurryLocation",miuiSignature,10123);
        check(masked!=miui.cached&&Math.abs(((android.location.LocationResult)masked).getLastLocation().getLatitude()-30)<0.001,"selected Xiaomi caller receives an independent simulated packet");
        check(miui.cached.getLastLocation()==real&&real.getLatitude()==39,"Xiaomi cache retains the original real fix");
        check(XposedBridge.invoke(miui,"getBlurryLocation",miuiSignature,10234)==miui.cached,"unselected Xiaomi caller keeps the real packet");
        errors=control.errors;
        android.location.LocationResult.failCreation=true;
        try {check(XposedBridge.invoke(miui,"getBlurryLocation",miuiSignature,10123)==null,"Xiaomi packet construction failure does not return real cache");}
        finally {android.location.LocationResult.failCreation=false;}
        check(control.errors==errors+1,"Xiaomi packet failure emits a diagnostic");
        control.config=null;
        XposedBridge.invoke(listener,"onLocationChanged",locationSignature,realList);
        check(listener.delivered==realList,"stopping restores real callbacks");
        System.out.println("PASS: "+checks+" production callback/ownership checks (API fixtures).");
    }
}

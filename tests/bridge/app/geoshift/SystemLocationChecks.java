package app.geoshift;
import android.location.Location;
import android.location.LocationResult;
import android.os.Build;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.*;

public final class SystemLocationChecks {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static class ExternalBase {public Object acceptLocationChange(LocationResult result){return null;}}
    public static final class ManagerFixture {
        public static class Cached extends ExternalBase {}
        public static class Current {public void acceptLocationChange(LocationResult result,boolean cache){}}
        public static class Holder {public static class Stream {private void acceptLocationChange(LocationResult result){}}}
        public abstract static class Abstract {public abstract void acceptLocationChange(LocationResult result);}
        public static class WrongType {public void acceptLocationChange(String text){}}
    }
    public static class ManagerBase {
        protected Location getLastLocation(int user){return null;}
        public void onReportLocation(LocationResult result){}
    }
    public static final class ManagerApi extends ManagerBase {
        private String getLastLocation(){return "wrong return type";}
        public void onReportLocation(String ignored){}
        public void onReportLocation(LocationResult result,long time){}
    }
    public static void main(String[] args)throws Exception {
        Object preexistingGps = new Object(), preexistingFused = new Object();
        Set<Object> discovered = new HashSet<>();
        ProviderDiscovery.discover(Arrays.asList("gps", "network", "fused"), discovered, name -> {
            if (name.equals("gps")) return preexistingGps;
            if (name.equals("network")) throw new IllegalStateException("OEM missing provider");
            return preexistingFused;
        });
        check(discovered.contains(preexistingGps) && discovered.contains(preexistingFused), "pre-existing providers survive a missing intermediate provider");
        ProviderDiscovery.discover(Arrays.asList("gps"), discovered, name -> preexistingGps);
        check(discovered.size() == 2, "repeat discovery does not duplicate providers");
        List<Method> methods=DeliveryTargets.find(ManagerFixture.class);
        check(methods.size()==3,"cached, current, streaming and inherited entry discovery");
        check(new HashSet<>(methods).size()==methods.size(),"no duplicate superclass hooks");
        check(methods.stream().anyMatch(m->m.getDeclaringClass()==ExternalBase.class),"OEM superclass entry");
        check(methods.stream().noneMatch(m->m.getDeclaringClass()==ManagerFixture.WrongType.class),"ignore unrelated overloads");
        check(DeliveryTargets.find(String.class).isEmpty(),"missing entry stays missing");
        check(DeliveryTargets.reports(ManagerApi.class).size()==2,"typed report overloads and superclass discovery");
        check(DeliveryTargets.cached(ManagerApi.class).size()==1,"cache return type must be Location");
        check(DeliveryTargets.reports(ManagerApi.class).stream().noneMatch(m->m.getParameterTypes()[0]==String.class),"unrelated report overload not hooked");
        JSONObject config=new JSONObject().put("runtime",new JSONObject().put("lat",39.9042).put("lon",116.4074).put("speed",6.0).put("bearing",45.0))
            .put("settings",new JSONObject().put("accuracy",5.0).put("altitude",22.0));
        for(String provider:new String[]{"gps","network","fused"}){
            Location fix=FakeLocation.build(config,provider);
            check(provider.equals(fix.getProvider()),"provider is preserved: "+provider);
            check(fix.getLatitude()==39.9042&&fix.getLongitude()==116.4074,"WGS-84 fix: "+provider);
            check(!fix.isMock()&&!fix.isFromMockProvider(),"framework flag: "+provider);
            check(fix.getElapsedRealtimeNanos()==123456000000L&&fix.getTime()>1_000_000,"complete timestamps: "+provider);
            LocationResult packet=(LocationResult)LocationPacket.wrap(fix);
            check(packet.getLastLocation()==fix,"typed framework packet: "+provider);
        }
        Location fix=FakeLocation.build(config,"fused");
        check(fix.getAccuracy()==5&&fix.getAltitude()==22&&fix.getSpeed()==6&&fix.getBearing()==45,"fix metadata");
        check(fix.getExtras()==null,"no custom mock bundle");
        config.getJSONObject("runtime").put("paused",true);check(FakeLocation.build(config,"gps").getSpeed()==0,"paused route fix");
        Build.VERSION.SDK_INT=26;check(!FakeLocation.build(config,"network").isMock(),"legacy constructor keeps flag clear");
        System.out.println("PASS: "+checks+" system delivery/fix checks (API fixtures; not live system_server).");
    }
}

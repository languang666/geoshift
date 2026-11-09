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
            check(Geo.distance(fix.getLatitude(),fix.getLongitude(),39.9042,116.4074)<("network".equals(provider)?9:3.5),"WGS-84 fix stays near target: "+provider);
            check(!fix.isMock()&&!fix.isFromMockProvider(),"framework flag: "+provider);
            check(fix.getElapsedRealtimeNanos()==123456000000L&&fix.getTime()>1_000_000,"complete timestamps: "+provider);
            LocationResult packet=(LocationResult)LocationPacket.wrap(fix);
            check(packet.getLastLocation()==fix,"typed framework packet: "+provider);
        }
        Location fix=FakeLocation.build(config,"fused");
        check(fix.getAccuracy()>=4&&fix.getAccuracy()<=6&&Math.abs(fix.getAltitude()-22)<=1.5&&fix.getSpeed()==6&&fix.getBearing()==45,"fix metadata");
        check(FakeLocation.build(config,"network").getAccuracy()>=18,"network fix is coarse");
        check(FakeLocation.build(config,"network").getAltitude()==0&&FakeLocation.build(config,"network").getSpeed()==0,"network fix has no altitude or motion");
        check(fix.getExtras()==null,"no custom mock bundle");
        config.getJSONObject("runtime").put("paused",true);check(FakeLocation.build(config,"gps").getSpeed()<0.3,"paused route fix is nearly still");
        Location past=new Location("gps");past.setLatitude(1);past.setLongitude(2);past.setElapsedRealtimeNanos(113456000000L);
        Location recent=new Location("gps");recent.setLatitude(3);recent.setLongitude(4);recent.setElapsedRealtimeNanos(123456000000L);
        LocationResult realBatch=LocationResult.create(Arrays.asList(past,recent));
        LocationResult batch=(LocationResult)LocationPacket.replace(config,realBatch);
        check(batch!=realBatch&&batch.getLocations().size()==2,"framework delivery keeps the entire batch");
        check(batch.getLocations().get(0).getElapsedRealtimeNanos()==113456000000L&&batch.getLastLocation().getElapsedRealtimeNanos()==123456000000L,"framework delivery keeps historical timestamps");
        check(Geo.distance(batch.getLocations().get(0).getLatitude(),batch.getLocations().get(0).getLongitude(),39.9042,116.4074)<4,"historical batch coordinates replaced");
        check(realBatch.getLocations().get(0)==past&&past.getLatitude()==1,"real batch untouched");
        LocationResult secondPass=(LocationResult)LocationPacket.replace(config,batch);
        check(secondPass.getLocations().size()==2&&secondPass.getLocations().get(0).getElapsedRealtimeNanos()==113456000000L,"multiple delivery layers preserve history");
        Build.VERSION.SDK_INT=26;check(!FakeLocation.build(config,"network").isMock(),"legacy constructor keeps flag clear");
        // A cached fix older than 10 minutes must not make the simulated one equally stale.
        android.os.SystemClock.now=3_600_000L;
        try {
            JSONObject staleConfig=new JSONObject().put("runtime",new JSONObject().put("lat",31.2).put("lon",121.4))
                    .put("settings",new JSONObject().put("accuracy",5));
            Location old=new Location("gps");old.setElapsedRealtimeNanos(60_000L*1_000_000L);
            Location fresh=new Location("gps");fresh.setElapsedRealtimeNanos((3_600_000L-30_000L)*1_000_000L);
            check(FakeLocation.replace(staleConfig,old).getElapsedRealtimeNanos()==3_600_000L*1_000_000L,"stale original gets a fresh fake fix");
            check(FakeLocation.replace(staleConfig,fresh).getElapsedRealtimeNanos()==(3_600_000L-30_000L)*1_000_000L,"recent batched fix keeps its own moment");
        } finally {android.os.SystemClock.now=123456L;}
        System.out.println("PASS: "+checks+" system delivery/fix checks (API fixtures; not live system_server).");
    }
}

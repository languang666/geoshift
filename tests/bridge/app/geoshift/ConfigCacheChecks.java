package app.geoshift;

import org.json.JSONObject;

/** A transport failure, malformed response and confirmed stop have different cache semantics. */
public final class ConfigCacheChecks {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args) {
        ConfigCache cache=new ConfigCache(500);
        int[] reads={0};
        ConfigCache.Reader unavailable=()->{reads[0]++;throw new IllegalStateException("IPC unavailable");};
        JSONObject active=new JSONObject().put("runtime",new JSONObject().put("enabled",true));
        check(cache.get(0,unavailable)==null,"no invented state before first successful read");
        check(cache.failure()!=null,"read failure is observable");
        cache.get(100,unavailable);
        check(reads[0]==1,"failed reads are rate limited, including at clock zero");
        check(cache.get(500,()->active)==active&&cache.failure()==null,"successful read activates and clears error");
        check(cache.get(700,unavailable)==active&&reads[0]==1,"within TTL keeps the active snapshot without IPC");
        check(cache.get(1000,unavailable)==active,"IPC failure does not disable privacy");
        check(cache.failure()!=null,"active-cache IPC failure reported");
        check(cache.get(1500,()->{throw new IllegalArgumentException("malformed JSON");})==active,"malformed reply does not replace active state");
        check(cache.get(2000,()->null)==null,"confirmed stopped reply clears active state");
        check(cache.failure()==null,"confirmed stop clears old errors");
        check(cache.get(2500,unavailable)==null,"later IPC failure cannot resurrect stopped state");
        JSONObject restarted=new JSONObject().put("runtime",new JSONObject().put("enabled",true));
        check(cache.get(3000,()->restarted)==restarted,"subsequent start accepts a new snapshot");
        check(cache.get(100,()->null)==null,"clock rollback forces a fresh read rather than freezing the cache");
        System.out.println("PASS: "+checks+" configuration IPC/cache checks.");
    }
}

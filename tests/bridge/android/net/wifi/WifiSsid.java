package android.net.wifi;
import java.nio.charset.StandardCharsets;
public final class WifiSsid {
    public final String name;
    private WifiSsid(String name){this.name=name;}
    public static boolean fail;
    public static WifiSsid fromBytes(byte[] bytes){if(fail)throw new IllegalStateException("injected");return new WifiSsid(new String(bytes,StandardCharsets.UTF_8));}
}

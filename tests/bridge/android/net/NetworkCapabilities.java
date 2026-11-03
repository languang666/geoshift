package android.net;
public class NetworkCapabilities {
    private Object info;
    public NetworkCapabilities(){}
    public NetworkCapabilities(NetworkCapabilities o){info=o.info;}
    public Object getTransportInfo(){return info;}
    public NetworkCapabilities setTransportInfo(Object i){info=i;return this;}
}

package android.location;
public interface ILocationListener {
    final class Stub {
        public static final class Proxy implements android.os.IInterface {
            private final android.os.IBinder binder=new android.os.IBinder(){};
            public java.util.List<Location> delivered;
            public int calls;
            public android.os.IBinder asBinder(){return binder;}
            public void onLocationChanged(java.util.List<Location> values){delivered=values;calls++;}
        }
    }
}

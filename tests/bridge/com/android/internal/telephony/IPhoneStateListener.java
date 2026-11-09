package com.android.internal.telephony;
public interface IPhoneStateListener {
    final class Stub {
        public static final class Proxy implements android.os.IInterface {
            private final android.os.IBinder binder=new android.os.IBinder(){};
            public int calls;
            public java.util.List<?> cells;
            public android.os.IBinder asBinder(){return binder;}
            public void onCellInfoChanged(java.util.List<?> values){cells=values;calls++;}
        }
    }
}

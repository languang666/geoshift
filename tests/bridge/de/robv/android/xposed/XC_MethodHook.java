package de.robv.android.xposed;
import java.lang.reflect.Member;
public class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam p) throws Throwable {}
    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        Object result;
        Throwable error;
        boolean early;
        public Object getResult(){return result;}
        public void setResult(Object value){result=value;error=null;early=true;}
        public boolean hasThrowable(){return error!=null;}
    }
    public final class Unhook { public void unhook(){ XposedBridge.remove(XC_MethodHook.this); } }
}

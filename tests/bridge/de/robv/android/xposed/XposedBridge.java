package de.robv.android.xposed;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
/** Explicit callback dispatcher; does not instrument the JVM or claim to emulate LSPosed. */
public final class XposedBridge {
    private static final Map<Method,List<XC_MethodHook>> hooks=new HashMap<>();
    public static void reset(){hooks.clear();}
    public static Object invokeOriginalMethod(java.lang.reflect.Member member,Object target,Object[] args)throws Throwable{
        try{return ((Method)member).invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
    static void remove(XC_MethodHook hook){for(List<XC_MethodHook> l:hooks.values())l.remove(hook);}
    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member member,XC_MethodHook hook){
        hooks.computeIfAbsent((Method)member,k->new ArrayList<>()).add(hook);return hook.new Unhook();
    }
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> type,String name,XC_MethodHook hook){
        Set<XC_MethodHook.Unhook> out=new HashSet<>();
        for(Method m:type.getDeclaredMethods())if(m.getName().equals(name))out.add(hookMethod(m,hook));
        return out;
    }
    public static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Throwable{
        Method method=null;
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())try{method=c.getDeclaredMethod(name,types);break;}catch(NoSuchMethodException ignored){}
        if(method==null)throw new NoSuchMethodException(name);
        XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.method=method;p.thisObject=target;p.args=args;
        List<XC_MethodHook> list=new ArrayList<>(hooks.getOrDefault(method,Collections.emptyList()));
        for(XC_MethodHook hook:list){hook.beforeHookedMethod(p);if(p.early)break;}
        if(!p.early)try{method.setAccessible(true);p.result=method.invoke(target,p.args);}catch(InvocationTargetException e){p.error=e.getCause();}
        Collections.reverse(list);for(XC_MethodHook hook:list)hook.afterHookedMethod(p);
        if(p.error!=null)throw p.error;return p.result;
    }
}

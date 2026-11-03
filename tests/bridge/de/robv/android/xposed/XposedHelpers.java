package de.robv.android.xposed;
public final class XposedHelpers {
    public static Class<?> findClassIfExists(String name,ClassLoader loader){try{return Class.forName(name,false,loader);}catch(ClassNotFoundException e){return null;}}
    public static Object callMethod(Object object,String name,Object...args){
        try{
            for(java.lang.reflect.Method m:object.getClass().getMethods())
                if(m.getName().equals(name)&&m.getParameterCount()==args.length)return m.invoke(object,args);
            throw new NoSuchMethodException(name);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public static Object newInstance(Class<?> type,Object...args){
        try{
            for(java.lang.reflect.Constructor<?> c:type.getDeclaredConstructors())
                if(c.getParameterCount()==args.length){c.setAccessible(true);return c.newInstance(args);}
            throw new NoSuchMethodException(type.getName());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public static Object callStaticMethod(Class<?> type,String name,Object...args){
        try{
            for(java.lang.reflect.Method m:type.getMethods())
                if(m.getName().equals(name)&&m.getParameterCount()==args.length)return m.invoke(null,args);
            throw new NoSuchMethodException(name);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public static java.lang.reflect.Field findField(Class<?> type,String name){
        for(Class<?> c=type;c!=null;c=c.getSuperclass()){
            try{java.lang.reflect.Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
        }
        throw new NoSuchFieldError(name);
    }
}

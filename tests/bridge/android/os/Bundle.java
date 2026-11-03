package android.os;
import java.util.HashMap;
import java.util.Map;
public final class Bundle {
    private final Map<String,Object> values=new HashMap<>();
    public Bundle(){}
    public Bundle(Bundle other){values.putAll(other.values);}
    public boolean containsKey(String key){return values.containsKey(key);}
    public void putBoolean(String key,boolean value){values.put(key,value);}
    public boolean getBoolean(String key){return Boolean.TRUE.equals(values.get(key));}
    public void putInt(String key,int value){values.put(key,value);}
    public int getInt(String key){return ((Number)values.get(key)).intValue();}
}

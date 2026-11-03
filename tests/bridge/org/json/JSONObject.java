package org.json;
import java.util.HashMap;
import java.util.Map;
/** Small in-memory configuration fixture; JSON parsing is outside this hook test. */
public final class JSONObject {
    private final Map<String,Object> values = new HashMap<>();
    public JSONObject put(String key, Object value) { values.put(key,value); return this; }
    public JSONObject getJSONObject(String key) { return (JSONObject) values.get(key); }
    public double getDouble(String key) { return ((Number) values.get(key)).doubleValue(); }
    public double optDouble(String key,double fallback) { Object v=values.get(key); return v instanceof Number ? ((Number)v).doubleValue() : fallback; }
    public boolean optBoolean(String key) { return Boolean.TRUE.equals(values.get(key)); }
    public boolean optBoolean(String key,boolean fallback) { Object v=values.get(key); return v instanceof Boolean ? (Boolean)v : fallback; }
    public JSONObject optJSONObject(String key) { Object v=values.get(key); return v instanceof JSONObject ? (JSONObject)v : null; }
    public JSONArray optJSONArray(String key) { Object v=values.get(key); return v instanceof JSONArray ? (JSONArray)v : null; }
    public String optString(String key,String fallback) { Object v=values.get(key); return v == null ? fallback : v.toString(); }
    public String getString(String key) { return values.get(key).toString(); }
    public int getInt(String key) { return ((Number) values.get(key)).intValue(); }
    public int optInt(String key,int fallback) { Object v=values.get(key); return v instanceof Number ? ((Number)v).intValue() : fallback; }
    public String optString(String key) { Object v=values.get(key); return v == null ? "" : v.toString(); }
}

package app.geoshift;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Optional, explicit high-level map requests. Never sends presets, scans, or logs. */
public final class MapNetwork {
    private static final String HOST = "https://restapi.amap.com";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private MapNetwork() {}

    /** An AMap reply with status 0; [code] is its infocode. */
    public static final class AmapException extends Exception {
        public final String code;
        AmapException(String code, String message) { super(message); this.code = code; }
        /** The point lies outside AMap's coverage for this key (abroad, or not on the mainland). */
        public boolean outOfCoverage() { return "20011".equals(code) || "20800".equals(code); }
    }

    /** "lon,lat" with the at most 6 decimals AMap accepts. */
    private static String lonLat(double[] latLon) {
        return String.format(java.util.Locale.ROOT, "%.6f,%.6f", latLon[1], latLon[0]);
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static JSONObject get(Context context, String path, String query) throws Exception {
        if (!PortalStore.settings(context).optBoolean("privacyAccepted"))
            throw new Exception("请先阅读并同意隐私说明，再使用在线地图或搜索。");
        String key = PortalStore.mapKey(context);
        if (key.isEmpty())
            throw new Exception("地点搜索和地址查询需要高德 Web 服务 Key，请在设置中填写；地图标点和路线编辑无需此 Key。");
        URL url = new URL(HOST + path + "?key=" + encode(key) + "&" + query);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "Geoshift/" + PortalStore.VERSION + " (Android; app.geoshift)");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("地图服务 HTTP " + code + "，请稍后重试。");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n, total = 0;
                while ((n = stream.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_RESPONSE_BYTES) throw new Exception("地图响应超过限制。");
                    out.write(buffer, 0, n);
                }
                JSONObject response = new JSONObject(out.toString("UTF-8"));
                if (!"1".equals(response.optString("status")))
                    throw new AmapException(response.optString("infocode"),
                            explain(response.optString("infocode"), response.optString("info", "未知错误")));
                return response;
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Turns AMap error codes into actionable Chinese messages. */
    static String explain(String code, String info) {
        String hint;
        switch (code) {
            case "10001": hint = "Key 不正确或已过期，请检查是否完整复制了 32 位 Key。"; break;
            case "10002": hint = "此 Key 没有开通该服务，请在高德控制台确认 Key 的服务平台是「Web 服务」。"; break;
            case "10003": hint = "今日调用次数已用完，明天会恢复。"; break;
            case "10004": hint = "请求太频繁，请稍后再试。"; break;
            case "10005": hint = "IP 白名单不匹配：请在高德控制台把这个 Key 的 IP 白名单清空。"; break;
            case "10006": hint = "绑定的域名不匹配，请在控制台去掉域名限制。"; break;
            case "10007": hint = "Key 开启了「数字签名」，请在高德控制台关闭数字签名。"; break;
            case "10008": hint = "安全码校验失败：这是 Android/iOS 平台的 Key，请另外创建一个「Web 服务」平台的 Key。"; break;
            case "10009": hint = "Key 平台不匹配：填入的是 Android 平台 Key，请改填「Web 服务」平台的 Key。"; break;
            case "10010": hint = "当前 IP 访问超限，请稍后再试。"; break;
            case "10012": hint = "此 Key 没有该接口的权限。"; break;
            case "10013": hint = "此 Key 已被删除，请重新创建。"; break;
            case "10044": case "10045": hint = "账号或 Key 的日调用量已超限。"; break;
            case "20000": case "20001": hint = "请求参数有误，请重试；若持续出现请反馈。"; break;
            case "20011": hint = "该地点在海外，高德个人 Key 没有海外权限。"; break;
            case "20800": hint = "该地点不在中国境内，高德无法规划路线。"; break;
            case "20801": hint = "起点或终点附近没有道路，请把点放到路上。"; break;
            case "20802": hint = "两点之间找不到可通行的路线。"; break;
            case "20803": hint = "两点距离太远，请分段添加途经点。"; break;
            default: hint = "";
        }
        return "高德返回错误 " + code + "（" + info + "）" + (hint.isEmpty() ? "" : "：" + hint);
    }

    public static JSONObject search(Context c, String query) throws Exception {
        query = query == null ? "" : query.trim();
        if (query.isEmpty() || query.length() > 200) throw new Exception("请输入 1–200 字的地点关键词。");
        JSONArray tips = get(c, "/v3/assistant/inputtips", "keywords=" + encode(query) + "&datatype=all").optJSONArray("tips");
        JSONArray out = new JSONArray();
        if (tips == null) return PortalStore.obj("results", out);
        for (int i = 0; i < tips.length(); i++) {
            JSONObject tip = tips.optJSONObject(i);
            if (tip == null) continue;
            String[] values = tip.optString("location", "").split(",");
            if (values.length != 2) continue;
            try {
                double lon = Double.parseDouble(values[0]), lat = Double.parseDouble(values[1]);
                double[] wgs = Geo.gcj02ToWgs84(lat, lon);
                out.put(PortalStore.obj("name", tip.optString("name"),
                        "address", tip.optString("district") + tip.optString("address"),
                        "lat", wgs[0], "lon", wgs[1]));
            } catch (Exception ignored) {}
        }
        return PortalStore.obj("results", out);
    }

    public static JSONObject reverse(Context c, double lat, double lon) throws Exception {
        if (!Geo.valid(lat, lon)) throw new Exception("经纬度无效。");
        double[] gcj = Geo.wgs84ToGcj02(lat, lon);
        JSONObject answer = get(c, "/v3/geocode/regeo", "extensions=base&location=" + encode(lonLat(gcj)));
        JSONObject regeo = answer.optJSONObject("regeocode");
        return PortalStore.obj("address", regeo == null ? "" : regeo.optString("formatted_address"));
    }

    /**
     * Road path between two WGS-84 points from AMap route planning, as WGS-84 [lat, lon] pairs.
     * profile: foot / bike / car.
     */
    public static java.util.List<double[]> direction(Context c, String profile, double aLat, double aLon, double bLat, double bLon) throws Exception {
        if (!Geo.valid(aLat, aLon) || !Geo.valid(bLat, bLon)) throw new Exception("经纬度无效。");
        double[] a = Geo.wgs84ToGcj02(aLat, aLon), b = Geo.wgs84ToGcj02(bLat, bLon);
        String od = "origin=" + encode(lonLat(a)) + "&destination=" + encode(lonLat(b));
        String path;
        switch (profile) {
            case "car": path = "/v3/direction/driving"; od += "&extensions=base"; break;
            case "bike": path = "/v5/direction/bicycling"; od += "&show_fields=polyline"; break;
            default: path = "/v3/direction/walking";
        }
        JSONObject route = get(c, path, od).optJSONObject("route");
        JSONArray paths = route == null ? null : route.optJSONArray("paths");
        JSONObject first = paths == null ? null : paths.optJSONObject(0);
        JSONArray steps = first == null ? null : first.optJSONArray("steps");
        java.util.List<double[]> out = new java.util.ArrayList<>();
        if (steps != null) {
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                for (String pair : step.optString("polyline").split(";")) {
                    String[] v = pair.split(",");
                    if (v.length != 2) continue;
                    try {
                        double[] w = Geo.gcj02ToWgs84(Double.parseDouble(v[1]), Double.parseDouble(v[0]));
                        out.add(w);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        if (out.size() < 2) throw new Exception("高德没有找到这两点之间的道路，可改用直线连接。");
        return out;
    }
}

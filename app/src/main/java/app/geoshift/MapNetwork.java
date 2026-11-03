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
                    throw new Exception(explain(response.optString("infocode"), response.optString("info", "未知错误")));
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
        JSONObject answer = get(c, "/v3/geocode/regeo", "extensions=base&location=" + encode(gcj[1] + "," + gcj[0]));
        JSONObject regeo = answer.optJSONObject("regeocode");
        return PortalStore.obj("address", regeo == null ? "" : regeo.optString("formatted_address"));
    }
}

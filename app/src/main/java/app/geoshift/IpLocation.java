package app.geoshift;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Locates the device's current public exit IP, to warn when it is far from the simulated point
 * (apps such as DingTalk carry an IP-geolocation library and cross-check the IP's city). The module
 * cannot forge the exit IP; this is advisory only and must be triggered explicitly, since it sends
 * the exit IP to a geolocation service. Nothing is sent automatically.
 *
 * <p>With an AMap Web key it uses AMap /v3/ip (accurate for mainland China IPs); AMap returns nothing
 * for overseas IPs, so those and the keyless case fall back to ipwho.is (HTTPS, no key). Coordinates
 * are returned as WGS-84; AMap's are converted from GCJ-02.
 */
public final class IpLocation {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private IpLocation() {}

    /** One exit-IP lookup. [hasCoord] false means only [place]/[ip] are known. */
    public static final class Result {
        public final String ip;        // exit IP as reported, may be empty (AMap does not return it)
        public final String place;     // human place label, e.g. "广东省 深圳市" or "United States, Los Angeles"
        public final boolean hasCoord; // true when lat/lon are usable (WGS-84)
        public final double lat, lon;
        public final String source;    // "高德" or "ipwho.is"

        Result(String ip, String place, boolean hasCoord, double lat, double lon, String source) {
            this.ip = ip == null ? "" : ip;
            this.place = place == null ? "" : place;
            this.hasCoord = hasCoord;
            this.lat = lat; this.lon = lon;
            this.source = source;
        }
    }

    public static Result lookup(Context context) throws Exception {
        String key = PortalStore.mapKey(context);
        if (!key.isEmpty()) {
            Result amap = tryAmap(context, key);
            if (amap != null) return amap; // null = overseas / empty, fall through to the keyless service
        }
        return ipwho();
    }

    /** AMap /v3/ip, located by the request's source IP. Returns null for overseas or empty replies. */
    private static Result tryAmap(Context context, String key) {
        try {
            JSONObject r = get("https://restapi.amap.com/v3/ip?key=" + enc(key), context);
            if (!"1".equals(r.optString("status"))) return null;
            // AMap answers overseas IPs with empty province/city/rectangle (often "[]").
            String rectangle = r.optString("rectangle");
            if (rectangle.isEmpty() || rectangle.equals("[]")) return null;
            String province = clean(r.optString("province"));
            String city = clean(r.optString("city"));
            String place = (province + (city.isEmpty() || city.equals(province) ? "" : " " + city)).trim();
            // rectangle is "lon1,lat1;lon2,lat2" in GCJ-02; use its centre.
            String[] corners = rectangle.split(";");
            if (corners.length == 2) {
                String[] a = corners[0].split(","), b = corners[1].split(",");
                if (a.length == 2 && b.length == 2) {
                    double lon = (Double.parseDouble(a[0]) + Double.parseDouble(b[0])) / 2;
                    double lat = (Double.parseDouble(a[1]) + Double.parseDouble(b[1])) / 2;
                    double[] wgs = Geo.gcj02ToWgs84(lat, lon);
                    if (Geo.valid(wgs[0], wgs[1])) return new Result("", place, true, wgs[0], wgs[1], "高德");
                }
            }
            if (!place.isEmpty()) return new Result("", place, false, 0, 0, "高德");
            return null;
        } catch (Throwable ignored) {
            return null; // any AMap failure: let the keyless service answer
        }
    }

    /** ipwho.is: HTTPS, no key, works worldwide, returns WGS-84 lat/lon. */
    private static Result ipwho() throws Exception {
        JSONObject r = get("https://ipwho.is/", null);
        if (!r.optBoolean("success", true) && r.has("message"))
            throw new Exception("IP 定位服务返回错误：" + r.optString("message"));
        String ip = r.optString("ip");
        String country = clean(r.optString("country"));
        String city = clean(r.optString("city"));
        String region = clean(r.optString("region"));
        String place = join(country, region.equals(city) ? "" : region, city);
        double lat = r.optDouble("latitude", Double.NaN), lon = r.optDouble("longitude", Double.NaN);
        boolean has = !Double.isNaN(lat) && !Double.isNaN(lon) && Geo.valid(lat, lon);
        return new Result(ip, place, has, has ? lat : 0, has ? lon : 0, "ipwho.is");
    }

    private static JSONObject get(String spec, Context context) throws Exception {
        URL url = new URL(spec);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "Geoshift/" + PortalStore.VERSION + " (Android; app.geoshift)");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("IP 定位服务 HTTP " + code + "，请稍后重试。");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n, total = 0;
                while ((n = stream.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_RESPONSE_BYTES) throw new Exception("IP 定位响应超过限制。");
                    out.write(buffer, 0, n);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {
            connection.disconnect();
        }
    }

    /** AMap returns "[]" for empty string fields; treat those as blank. */
    private static String clean(String v) {
        return v == null || v.equals("[]") ? "" : v.trim();
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    private static String enc(String v) throws Exception {
        return java.net.URLEncoder.encode(v, "UTF-8");
    }
}

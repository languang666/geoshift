package app.geoshift;

/** Coordinates are always latitude, longitude, in degrees; distances are metres. */
public final class Geo {
    public static final double EARTH_RADIUS_METRES = 6371008.8;
    private static final double A = 6378245.0;
    private static final double EE = 0.006693421622965943;

    private Geo() {}

    public static boolean valid(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180;
    }

    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        check(lat1, lon1);
        check(lat2, lon2);
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_METRES * 2 * Math.atan2(Math.sqrt(Math.max(0, h)),
                Math.sqrt(Math.max(0, 1 - Math.min(1, h))));
    }

    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        return distance(lat1, lon1, lat2, lon2);
    }

    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        check(lat1, lon1);
        check(lat2, lon2);
        double a = Math.toRadians(lat1), b = Math.toRadians(lat2);
        double d = Math.toRadians(lon2 - lon1);
        return normalizeBearing(Math.toDegrees(Math.atan2(Math.sin(d) * Math.cos(b),
                Math.cos(a) * Math.sin(b) - Math.sin(a) * Math.cos(b) * Math.cos(d))));
    }

    public static double normalizeBearing(double degrees) {
        if (!Double.isFinite(degrees)) throw new IllegalArgumentException("方向必须是有限数值");
        return ((degrees % 360) + 360) % 360;
    }

    /** Moves along a great circle. Returns latitude, longitude. */
    public static double[] move(double latitude, double longitude, double metres, double degrees) {
        check(latitude, longitude);
        if (!Double.isFinite(metres) || metres < 0)
            throw new IllegalArgumentException("移动距离必须是非负有限数值");
        double angle = metres / EARTH_RADIUS_METRES;
        double heading = Math.toRadians(normalizeBearing(degrees));
        double lat = Math.toRadians(latitude), lon = Math.toRadians(longitude);
        double nextLat = Math.asin(Math.max(-1, Math.min(1,
                Math.sin(lat) * Math.cos(angle) + Math.cos(lat) * Math.sin(angle) * Math.cos(heading))));
        double nextLon = lon + Math.atan2(Math.sin(heading) * Math.sin(angle) * Math.cos(lat),
                Math.cos(angle) - Math.sin(lat) * Math.sin(nextLat));
        return new double[]{Math.toDegrees(nextLat), wrapLongitude(Math.toDegrees(nextLon))};
    }

    public static double[] interpolate(double lat1, double lon1, double lat2, double lon2, double fraction) {
        check(lat1, lon1);
        check(lat2, lon2);
        if (!Double.isFinite(fraction)) throw new IllegalArgumentException("进度必须是有限数值");
        if (fraction <= 0) return new double[]{lat1, lon1};
        if (fraction >= 1) return new double[]{lat2, lon2};
        return move(lat1, lon1, distance(lat1, lon1, lat2, lon2) * fraction,
                bearing(lat1, lon1, lat2, lon2));
    }

    public static double[] wgsToGcj(double latitude, double longitude) {
        check(latitude, longitude);
        if (outsideChina(latitude, longitude)) return new double[]{latitude, longitude};
        double[] delta = delta(latitude, longitude);
        return new double[]{latitude + delta[0], longitude + delta[1]};
    }

    /** Iterative inverse of the conventional GCJ-02 transform, in degrees. */
    public static double[] gcjToWgs(double latitude, double longitude) {
        check(latitude, longitude);
        if (outsideChina(latitude, longitude)) return new double[]{latitude, longitude};
        double lat = latitude, lon = longitude;
        for (int i = 0; i < 12; i++) {
            double[] candidate = wgsToGcj(lat, lon);
            double dLat = candidate[0] - latitude, dLon = candidate[1] - longitude;
            lat -= dLat;
            lon -= dLon;
            if (Math.max(Math.abs(dLat), Math.abs(dLon)) < 1e-8) break;
        }
        return new double[]{lat, lon};
    }

    public static double[] wgs84ToGcj02(double latitude, double longitude) {
        return wgsToGcj(latitude, longitude);
    }

    public static double[] gcj02ToWgs84(double latitude, double longitude) {
        return gcjToWgs(latitude, longitude);
    }

    private static boolean outsideChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double[] delta(double lat, double lon) {
        double x = lon - 105, y = lat - 35;
        double dLat = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        dLat += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        dLat += (20 * Math.sin(y * Math.PI) + 40 * Math.sin(y * Math.PI / 3)) * 2 / 3;
        dLat += (160 * Math.sin(y * Math.PI / 12) + 320 * Math.sin(y * Math.PI / 30)) * 2 / 3;
        double dLon = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        dLon += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        dLon += (20 * Math.sin(x * Math.PI) + 40 * Math.sin(x * Math.PI / 3)) * 2 / 3;
        dLon += (150 * Math.sin(x * Math.PI / 12) + 300 * Math.sin(x * Math.PI / 30)) * 2 / 3;
        double radians = Math.toRadians(lat);
        double magic = 1 - EE * Math.sin(radians) * Math.sin(radians);
        double sqrt = Math.sqrt(magic);
        return new double[]{dLat * 180 / ((A * (1 - EE) / (magic * sqrt)) * Math.PI),
                dLon * 180 / ((A / sqrt * Math.cos(radians)) * Math.PI)};
    }

    private static double wrapLongitude(double value) {
        return ((value + 180) % 360 + 360) % 360 - 180;
    }

    private static void check(double latitude, double longitude) {
        if (!valid(latitude, longitude)) throw new IllegalArgumentException("经纬度超出范围或不是有限数值");
    }
}

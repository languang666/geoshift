package app.geoshift;

/** Map coordinates are display-only; stored locations and routes always remain WGS-84. */
public enum MapSource {
    DEFAULT("osm", "默认地图", "© OpenStreetMap contributors"),
    AMAP("amap", "高德地图", "© 高德地图"),
    SATELLITE("satellite", "全球卫星影像", "Sources: Esri, Maxar, Earthstar Geographics, GIS User Community");

    public final String key;
    public final String label;
    public final String attribution;

    MapSource(String key, String label, String attribution) {
        this.key = key;
        this.label = label;
        this.attribution = attribution;
    }

    public static MapSource fromKey(String key) {
        return "amap".equals(key) ? AMAP : "satellite".equals(key) ? SATELLITE : DEFAULT;
    }

    public double[] toDisplay(double latitude, double longitude) {
        if (this == AMAP) return Geo.wgsToGcj(latitude, longitude);
        if (!Geo.valid(latitude, longitude)) throw new IllegalArgumentException("无效坐标");
        return new double[]{latitude, longitude};
    }

    public double[] toWgs(double latitude, double longitude) {
        if (this == AMAP) return Geo.gcjToWgs(latitude, longitude);
        if (!Geo.valid(latitude, longitude)) throw new IllegalArgumentException("无效坐标");
        return new double[]{latitude, longitude};
    }
}

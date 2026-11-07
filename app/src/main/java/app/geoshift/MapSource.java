package app.geoshift;

/** Map coordinates are display-only; stored locations and routes always remain WGS-84. */
public enum MapSource {
    DEFAULT("osm", "默认地图", "全球地图 · OpenStreetMap", "© OpenStreetMap contributors"),
    VECTOR("vector", "矢量地图", "矢量地图 · 清晰可旋转（OpenFreeMap）", "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"),
    AMAP("amap", "高德地图", "高德地图 · 国内道路更全", "© 高德地图"),
    SATELLITE("satellite", "全球卫星影像", "全球卫星影像 · Esri", "Sources: Esri, Maxar, Earthstar Geographics, GIS User Community");

    public final String key;
    public final String label;
    public final String menuLabel;
    public final String attribution;

    MapSource(String key, String label, String menuLabel, String attribution) {
        this.key = key;
        this.label = label;
        this.menuLabel = menuLabel;
        this.attribution = attribution;
    }

    public static MapSource fromKey(String key) {
        for (MapSource s : values()) if (s.key.equals(key)) return s;
        return DEFAULT;
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

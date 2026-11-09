package app.geoshift;
/** Provider-cache classifier fixture only; callback rewriting uses the production implementation. */
final class SystemHook {
    static boolean nearSimulated(org.json.JSONObject config,android.location.Location location){return false;}
}

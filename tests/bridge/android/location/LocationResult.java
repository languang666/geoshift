package android.location;
import java.util.List;
public final class LocationResult {
    private final List<Location> mLocations;
    public static boolean failCreation;
    private LocationResult(List<Location> fixes){this.mLocations=fixes;}
    public static LocationResult create(List<Location> fixes){if(failCreation)throw new IllegalStateException("injected packet failure");return new LocationResult(fixes);}
    public Location getLastLocation(){return mLocations.get(mLocations.size()-1);}
    public List<Location> asList(){return java.util.Collections.unmodifiableList(mLocations);}
    public int size(){return mLocations.size();}
    public Location get(int i){return mLocations.get(i);}
}

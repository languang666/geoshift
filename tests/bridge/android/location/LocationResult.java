package android.location;
import java.util.List;
public final class LocationResult {
    private final List<Location> fixes;
    private LocationResult(List<Location> fixes){this.fixes=fixes;}
    public static LocationResult create(List<Location> fixes){return new LocationResult(fixes);}
    public Location getLastLocation(){return fixes.get(fixes.size()-1);}
}

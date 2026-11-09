package app.geoshift;

import android.location.Location;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import org.json.JSONObject;

/** Android 12+ framework delivery packet; use the List overload explicitly. */
final class LocationPacket {
    static Object wrap(Location location) {
        try {
            Class<?> result = Class.forName("android.location.LocationResult", false, Location.class.getClassLoader());
            return result.getMethod("create", List.class).invoke(null, Collections.singletonList(location));
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("LocationResult.create unavailable", e); }
    }

    static Object replace(JSONObject config, Object original) throws Exception {
        List<?> fixes = (List<?>) original.getClass().getMethod("getLocations").invoke(original);
        List<Location> replaced = new ArrayList<>(Math.max(1, fixes.size()));
        for (Object fix : fixes) replaced.add(FakeLocation.replace(config, fix));
        if (replaced.isEmpty()) replaced.add(FakeLocation.replace(config, null));
        return original.getClass().getMethod("create", List.class).invoke(null, replaced);
    }
    private LocationPacket() {}
}

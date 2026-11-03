package app.geoshift;

import android.location.Location;
import java.util.Collections;
import java.util.List;

/** Android 12+ framework delivery packet; use the List overload explicitly. */
final class LocationPacket {
    static Object wrap(Location location) {
        try {
            Class<?> result = Class.forName("android.location.LocationResult", false, Location.class.getClassLoader());
            return result.getMethod("create", List.class).invoke(null, Collections.singletonList(location));
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("LocationResult.create unavailable", e); }
    }
    private LocationPacket() {}
}

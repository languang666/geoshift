package app.geoshift;

import android.location.Location;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
        List<?> fixes = fixes(original);
        List<Location> replaced = new ArrayList<>(Math.max(1, fixes.size()));
        for (Object fix : fixes) replaced.add(FakeLocation.replace(config, fix));
        if (replaced.isEmpty()) replaced.add(FakeLocation.replace(config, null));
        return original.getClass().getMethod("create", List.class).invoke(null, replaced);
    }
    /** AOSP LocationResult exposes asList() (Android 12-16); size()/get(i) and mLocations are fallbacks. */
    static List<?> fixes(Object packet) throws Exception {
        Class<?> type = packet.getClass();
        try { return (List<?>) type.getMethod("asList").invoke(packet); } catch (NoSuchMethodException ignored) {}
        try {
            int size = (Integer) type.getMethod("size").invoke(packet);
            Method get = type.getMethod("get", int.class);
            List<Object> out = new ArrayList<>(size);
            for (int i = 0; i < size; i++) out.add(get.invoke(packet, i));
            return out;
        } catch (NoSuchMethodException ignored) {}
        Field field = type.getDeclaredField("mLocations");
        field.setAccessible(true);
        return new ArrayList<>((List<?>) field.get(packet));
    }
    private LocationPacket() {}
}

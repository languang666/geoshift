package app.geoshift;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;

/** Preserves the Binder method's declared container type, including Android 15+ list slices. */
final class ReplyFactory {
    static Object emptyList(Class<?> type) throws ReflectiveOperationException {
        return list(type, Collections.emptyList());
    }

    /** Wraps [items] in the declared reply type: a List, or a slice class with a List constructor. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object list(Class<?> type, List<?> items) throws ReflectiveOperationException {
        if (List.class.isAssignableFrom(type)) {
            if (type.isInstance(items)) return items;
            List result = (List) type.getDeclaredConstructor().newInstance();
            result.addAll(items);
            return result;
        }
        Constructor<?> constructor = type.getDeclaredConstructor(List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(items);
    }
    private ReplyFactory() {}
}

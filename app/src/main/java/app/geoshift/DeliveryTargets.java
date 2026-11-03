package app.geoshift;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Find typed delivery methods without depending on OEM registration class names. */
final class DeliveryTargets {
    static List<Method> find(Class<?> manager) {
        return select(manager, true, method -> "acceptLocationChange".equals(method.getName())
                && method.getParameterCount() > 0 && "android.location.LocationResult".equals(method.getParameterTypes()[0].getName()));
    }

    static List<Method> reports(Class<?> manager) {
        return select(manager, false, method -> "onReportLocation".equals(method.getName())
                && method.getParameterCount() > 0 && "android.location.LocationResult".equals(method.getParameterTypes()[0].getName()));
    }

    static List<Method> cached(Class<?> manager) {
        return select(manager, false, method -> "getLastLocation".equals(method.getName())
                && "android.location.Location".equals(method.getReturnType().getName()));
    }

    private static List<Method> select(Class<?> manager, boolean nested, Predicate<Method> predicate) {
        List<Class<?>> queue = new ArrayList<>();
        Set<Class<?>> visited = new LinkedHashSet<>();
        Set<Method> matches = new LinkedHashSet<>();
        queue.add(manager);
        for (int i = 0; i < queue.size(); i++) {
            Class<?> type = queue.get(i);
            if (type == null || type == Object.class || !visited.add(type)) continue;
            if (nested) for (Class<?> child : type.getDeclaredClasses()) queue.add(child);
            queue.add(type.getSuperclass());
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isAbstract(method.getModifiers()) && predicate.test(method))
                    matches.add(method);
            }
        }
        return new ArrayList<>(matches);
    }
    private DeliveryTargets() {}
}

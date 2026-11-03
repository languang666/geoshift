package app.geoshift;

import java.util.List;
import java.util.Set;

/** Discover existing managers independently, so one missing provider cannot block the others. */
final class ProviderDiscovery {
    interface Lookup { Object find(String name) throws Throwable; }
    static void discover(List<String> names, Set<Object> managers, Lookup lookup) {
        for (String name : names) {
            try {
                Object manager = lookup.find(name);
                if (manager != null) managers.add(manager);
            } catch (Throwable unavailable) { /* Other providers may still be available. */ }
        }
    }
    private ProviderDiscovery() {}
}

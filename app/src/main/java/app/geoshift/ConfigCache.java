package app.geoshift;

import org.json.JSONObject;

/** A failed IPC read is not an explicit stop. Only successful reads replace the last snapshot. */
final class ConfigCache {
    interface Reader { JSONObject read() throws Throwable; }

    private final long ttlMs;
    private JSONObject snapshot;
    private boolean attempted;
    private long attemptedAt;
    private Throwable failure;

    ConfigCache(long ttlMs) { this.ttlMs = ttlMs; }

    synchronized JSONObject get(long now, Reader reader) {
        if (attempted && now >= attemptedAt && now - attemptedAt < ttlMs) return snapshot;
        attempted = true;
        attemptedAt = now;
        try {
            JSONObject next = reader.read();
            snapshot = next; // A successful null reply means the system explicitly stopped simulation.
            failure = null;
        } catch (Throwable unavailable) {
            failure = unavailable;
        }
        return snapshot;
    }

    synchronized Throwable failure() { return failure; }
}

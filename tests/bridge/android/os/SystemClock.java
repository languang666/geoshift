package android.os;
public final class SystemClock {
    /** Settable by checks that need a long uptime; defaults keep the original fixture values. */
    public static long now = 123456L;
    public static long elapsedRealtime() { return now; }
    public static long elapsedRealtimeNanos() { return now * 1_000_000L; }
}

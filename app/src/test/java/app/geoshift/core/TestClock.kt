package app.geoshift.core

/** A clock the test moves by hand. */
class TestClock(var elapsed: Long = 5_000_000L, var wall: Long = 1_794_000_000_000L) : Clock {
    override fun elapsedMs() = elapsed
    override fun elapsedNanos() = elapsed * 1_000_000L
    override fun wallMs() = wall
    fun advance(ms: Long) { elapsed += ms; wall += ms }
}

package app.geoshift.core

import kotlin.math.floor
import kotlin.math.sin

/**
 * Deterministic, smooth noise. Every value depends only on the time and a seed, so all apps and all
 * processes see the same simulated fix at the same moment, and past fixes can be replayed exactly.
 */
object Noise {
    /** Smooth value in [-1, 1] that changes over tens of seconds (sum of two slow sines). */
    fun wave(seconds: Double, seed: Double) = 0.6 * sin(seconds / 23.0 + seed) + 0.4 * sin(seconds / 61.0 + seed * 2.7)

    /** Integral of [wave] dt, used to keep route speed changes exact. */
    fun waveIntegral(seconds: Double, seed: Double) =
        -0.6 * 23.0 * kotlin.math.cos(seconds / 23.0 + seed) - 0.4 * 61.0 * kotlin.math.cos(seconds / 61.0 + seed * 2.7)

    /** Pseudo-random value in [0, 1) fixed for a (value, salt) pair (SplitMix64 finaliser). */
    private fun unit(value: Long, salt: Long): Double {
        var z = value * salt + 0x632BE59BD9B4E019L
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return (z ushr 11) * (1.0 / (1L shl 53))
    }

    /** Smooth bounded noise in [-1, 1] with no repeating period (quintic-blended value noise). */
    fun smooth(seconds: Double, period: Double, seed: Long, channel: Long): Double {
        val position = seconds / period
        val bucket = floor(position).toLong()
        val f = position - bucket
        val blend = f * f * f * (f * (f * 6 - 15) + 10)
        val a = unit(bucket xor seed, channel) * 2 - 1
        val b = unit((bucket + 1) xor seed, channel) * 2 - 1
        return a + (b - a) * blend
    }

    /** Slow positional drift in [-1, 1]. */
    fun drift(seconds: Double, seed: Long, channel: Long) =
        0.7 * smooth(seconds, 17.3, seed, channel) + 0.3 * smooth(seconds, 53.1, seed, channel xor -0x29170147999a026dL)
}

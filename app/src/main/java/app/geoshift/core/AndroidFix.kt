package app.geoshift.core

import android.location.Location
import android.os.Build
import android.os.Bundle

/** android.location.Location for a simulated fix. [mock] marks it as a test-provider fix. */
fun Fix.toLocation(mock: Boolean = false): Location {
    val l = Location(provider)
    l.latitude = point.lat
    l.longitude = point.lon
    l.accuracy = accuracy
    l.time = wallTime
    l.elapsedRealtimeNanos = elapsedNanos
    altitude?.let { l.altitude = it }
    verticalAccuracy?.let { l.verticalAccuracyMeters = it }
    speed?.let { l.speed = it }
    speedAccuracy?.let { l.speedAccuracyMetersPerSecond = it }
    bearing?.let { l.bearing = it }
    bearingAccuracy?.let { l.bearingAccuracyDegrees = it }
    val extras = Bundle()
    gps?.let {
        extras.putInt("satellites", it.satellites)
        extras.putInt("meanCn0", it.meanCn0)
        extras.putInt("maxCn0", it.maxCn0)
    }
    if (mock) extras.putBoolean("geoshift.mock", true)
    if (!extras.isEmpty) l.extras = extras
    // A hook-delivered fix must not carry the mock flag; a test-provider fix always does.
    if (Build.VERSION.SDK_INT >= 31) l.isMock = mock
    return l
}

val Location.mockFlag: Boolean
    get() = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider

package id.ohmymonev.android.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal class LocationFailure(val reason: String, val code: Int, message: String) : Exception(message)

/** One run owns this cache. Never persists coordinates or keeps a location subscription. */
internal class DeviceLocation(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var cached: Location? = null

    fun clear() { cached = null }

    suspend fun current(maxAgeMs: Long, timeoutMs: Long): Location {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw LocationFailure("permission", 1, "Izinkan lokasi akurat.")
        val service = manager ?: throw LocationFailure("unavailable", 2, "Lokasi tidak tersedia.")
        if (!LocationManagerCompat.isLocationEnabled(service))
            throw LocationFailure("disabled", 2, "Aktifkan lokasi perangkat.")
        cached?.takeIf { fresh(it, maxAgeMs) }?.let { return it }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { service.isProviderEnabled(it) }
        if (providers.isEmpty()) throw LocationFailure("unavailable", 2, "Lokasi tidak tersedia.")
        val listeners = mutableListOf<LocationListenerCompat>()
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            val location = withTimeoutOrNull(timeoutMs.coerceIn(1, 30_000)) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    continuation.invokeOnCancellation { listeners.forEach { service.removeUpdates(it) } }
                    providers.forEach { provider ->
                        if (!continuation.isActive) return@forEach
                        val listener = object : LocationListenerCompat {
                            override fun onLocationChanged(fix: Location) {
                                if (continuation.isActive && valid(fix) && (fresh(fix, maxAgeMs) || fix.elapsedRealtimeNanos >= started))
                                    continuation.resume(fix)
                            }
                        }
                        listeners += listener
                        LocationManagerCompat.requestLocationUpdates(service, provider,
                            LocationRequestCompat.Builder(0).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build(),
                            ContextCompat.getMainExecutor(context), listener)
                    }
                }
            } ?: throw LocationFailure("timeout", 3, "Lokasi belum ditemukan. Coba lagi.")
            cached = location
            return location
        } catch (_: SecurityException) {
            throw LocationFailure("permission", 1, "Izinkan lokasi akurat.")
        } catch (_: IllegalArgumentException) {
            throw LocationFailure("unavailable", 2, "Lokasi tidak tersedia.")
        } finally { listeners.forEach { service.removeUpdates(it) } }
    }

    companion object {
        fun ageMs(location: Location): Long = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        fun valid(location: Location) = location.latitude.isFinite() && location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0 && location.hasAccuracy() &&
            location.accuracy.isFinite() && location.accuracy >= 0
        fun fresh(location: Location, maxAgeMs: Long) = valid(location) && maxAgeMs > 0 && ageMs(location) in 0..maxAgeMs
    }
}

package id.ohmymonev.android.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class LocationSource { DEVICE, IP }

internal data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double?,
    val obtainedAtMs: Long,
    val elapsedRealtimeMs: Long,
    val source: LocationSource,
    val fallbackReason: String? = null,
)

internal interface LocationNetworkMonitor {
    fun activeNetwork(): Any?
    fun observe(onChange: () -> Unit): () -> Unit
}

internal class LocationResolver(
    private val context: Context,
    private val deviceAllowed: () -> Boolean = { true },
    private val device: NativeLocationSource = DeviceLocation(context),
    private val ip: IpLocationSource = IpLocationClient(),
    private val network: LocationNetworkMonitor = AndroidLocationNetworkMonitor(context),
) {
    private data class Flight(val result: kotlinx.coroutines.Deferred<LocationFix>, var waiters: Int)

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkEpoch = AtomicLong()
    private val closed = AtomicBoolean(false)
    private val networkLock = Any()
    private var observedNetwork = network.activeNetwork()
    private var cached: LocationFix? = null
    private var cachedEpoch = networkEpoch.get()
    private var flight: Flight? = null
    @Volatile private var latest: LocationFix? = null
    private val unregisterNetwork = network.observe(::networkChanged)

    val lastSource: String? get() = latest?.source?.name
    val lastFallbackReason: String? get() = latest?.fallbackReason

    suspend fun resolve(maxAgeMs: Long, timeoutMs: Long = 30_000L): LocationFix {
        if (closed.get()) throw LocationFailure("closed", 2, "Lokasi tidak tersedia.")
        val maxAge = maxAgeMs.coerceIn(0L, MAX_CACHE_AGE_MS)
        val timeout = timeoutMs.coerceIn(1L, MAX_REQUEST_TIMEOUT_MS)
        val deviceReason = deviceUnavailableReason()
        val request = mutex.withLock {
            if (closed.get()) throw LocationFailure("closed", 2, "Lokasi tidak tersedia.")
            refreshNetwork()
            synchronized(networkLock) {
                cached?.takeIf {
                    cachedEpoch == networkEpoch.get() && fresh(it, maxAge) &&
                        (if (it.source == LocationSource.DEVICE) deviceReason == null
                        else if (deviceReason == null) it.fallbackReason == "timeout" || it.fallbackReason == "unavailable"
                        else it.fallbackReason == deviceReason)
                }
            }?.let { return it }
            flight?.also { it.waiters++ } ?: Flight(
                scope.async(start = CoroutineStart.LAZY) { fetch(timeout) },
                1,
            ).also { created ->
                flight = created
                created.result.start()
            }
        }
        try {
            return withTimeoutOrNull(timeout) { request.result.await() }
                ?: throw LocationFailure("timeout", 3, "Lokasi belum ditemukan. Coba lagi.")
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    request.waiters--
                    if (request.waiters == 0) {
                        if (!request.result.isCompleted) request.result.cancel()
                        if (flight === request) flight = null
                    } else if (request.result.isCompleted && flight === request) {
                        flight = null
                    }
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        unregisterNetwork()
        synchronized(networkLock) { cached = null; latest = null }
        device.clear()
        scope.cancel()
    }

    private suspend fun fetch(timeoutMs: Long): LocationFix {
        val started = SystemClock.elapsedRealtime()
        while (true) {
            val epoch = networkEpoch.get()
            val remaining = (timeoutMs - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0L)
            if (remaining <= 0) throw LocationFailure("timeout", 3, "Lokasi belum ditemukan. Coba lagi.")
            val fix = fetchAttempt(remaining)
            if (fix.source == LocationSource.DEVICE) {
                rememberDevice(fix, epoch)
                return fix
            }
            if (remember(fix, epoch)) return fix
        }
    }

    private suspend fun fetchAttempt(timeoutMs: Long): LocationFix {
        var fallbackReason = "not_eligible"
        val deviceReason = deviceUnavailableReason()
        if (deviceReason == null) {
            val ipReserve = minOf(IP_TIMEOUT_MS, timeoutMs)
            val deviceBudget = minOf(DEVICE_TIMEOUT_MS, (timeoutMs - ipReserve).coerceAtLeast(0L))
            if (deviceBudget > 0) {
                try {
                    val location = device.current(deviceBudget)
                    if (!DeviceLocation.valid(location)) throw LocationFailure("unavailable", 2, "Lokasi tidak tersedia.")
                    val fix = LocationFix(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy.toDouble(),
                        obtainedAtMs = location.time,
                        elapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000L,
                        source = LocationSource.DEVICE,
                    )
                    return fix
                } catch (failure: LocationFailure) {
                    fallbackReason = failure.reason
                } catch (_: SecurityException) {
                    fallbackReason = "permission"
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    fallbackReason = "unavailable"
                }
            } else {
                fallbackReason = "timeout"
            }
        } else {
            fallbackReason = deviceReason
        }

        val ipBudget = minOf(IP_TIMEOUT_MS, timeoutMs)
        if (ipBudget <= 0) throw LocationFailure("ip_timeout", 3, "Lokasi belum ditemukan. Coba lagi.")
        val coordinates = ip.locate(ipBudget)
        if (!IpLocationClient.valid(coordinates.latitude, coordinates.longitude)) {
            throw LocationFailure("ip_invalid_coordinates", 2, "Lokasi jaringan tidak tersedia.")
        }
        return LocationFix(
            latitude = coordinates.latitude,
            longitude = coordinates.longitude,
            accuracy = null,
            obtainedAtMs = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            source = LocationSource.IP,
            fallbackReason = fallbackReason,
        )
    }

    private fun deviceUnavailableReason(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "permission"
        }
        val manager = context.getSystemService(LocationManager::class.java) ?: return "unavailable"
        if (!LocationManagerCompat.isLocationEnabled(manager)) return "disabled"
        val allowed = runCatching(deviceAllowed).getOrDefault(false)
        return if (allowed) null else "not_eligible"
    }

    private fun fresh(fix: LocationFix, maxAgeMs: Long): Boolean {
        if (maxAgeMs <= 0) return false
        val age = SystemClock.elapsedRealtime() - fix.elapsedRealtimeMs
        return age in 0..minOf(maxAgeMs, MAX_CACHE_AGE_MS)
    }

    private fun remember(fix: LocationFix, epoch: Long): Boolean {
        synchronized(networkLock) {
            if (epoch != networkEpoch.get()) return false
            latest = fix
            cached = fix
            cachedEpoch = epoch
        }
        return true
    }

    private fun rememberDevice(fix: LocationFix, epoch: Long) {
        synchronized(networkLock) {
            latest = fix
            if (epoch == networkEpoch.get()) {
                cached = fix
                cachedEpoch = epoch
            }
        }
    }

    private fun refreshNetwork() {
        val current = network.activeNetwork()
        synchronized(networkLock) {
            if (current != observedNetwork) {
                observedNetwork = current
                networkEpoch.incrementAndGet()
                cached = null
                latest = null
                device.clear()
            }
        }
    }

    private fun networkChanged() {
        refreshNetwork()
    }

    companion object {
        const val MAX_CACHE_AGE_MS = 60_000L
        const val DEVICE_TIMEOUT_MS = 10_000L
        const val IP_TIMEOUT_MS = 8_000L
        private const val MAX_REQUEST_TIMEOUT_MS = 30_000L
    }
}

private class AndroidLocationNetworkMonitor(context: Context) : LocationNetworkMonitor {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun activeNetwork(): Any? = manager?.activeNetwork

    override fun observe(onChange: () -> Unit): () -> Unit {
        val connectivity = manager ?: return {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            return {}
        }
        return { try { connectivity.unregisterNetworkCallback(callback) } catch (_: Exception) { } }
    }
}

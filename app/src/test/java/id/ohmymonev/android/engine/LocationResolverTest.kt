package id.ohmymonev.android.engine

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocationResolverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun eligibleDeviceFixIsPreferred() = runBlocking {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Shadows.shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        val device = FakeDevice { fix() }
        val ip = FakeIp()
        val resolver = resolver(device = device, ip = ip, allowed = { true })

        val result = resolver.resolve(0)

        assertEquals(LocationSource.DEVICE, result.source)
        assertEquals(8.0, result.accuracy!!, 0.0)
        assertNull(result.fallbackReason)
        assertEquals(0, ip.calls.get())
        resolver.close()
    }

    @Test fun missingFinePermissionSkipsDeviceAndUsesIp() = runBlocking {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val device = FakeDevice { error("device should be skipped") }
        val ip = FakeIp()
        val resolver = resolver(device = device, ip = ip)

        val result = resolver.resolve(0)

        assertEquals(LocationSource.IP, result.source)
        assertNull(result.accuracy)
        assertEquals("permission", result.fallbackReason)
        assertEquals(1, ip.calls.get())
        resolver.close()
    }

    @Test fun zeroAgeConcurrentRequestsShareOneLookup() = runBlocking {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<IpCoordinates>()
        val ip = FakeIp {
            started.complete(Unit)
            finish.await()
        }
        val resolver = resolver(ip = ip)

        val first = async { resolver.resolve(0, 2_000) }
        started.await()
        val second = async { resolver.resolve(0, 2_000) }
        delay(20)
        finish.complete(IpCoordinates(-6.2, 106.8))
        val results = awaitAll(first, second)

        assertEquals(1, ip.calls.get())
        assertEquals(results[0], results[1])
        resolver.close()
    }

    @Test fun networkChangeInvalidatesCachedIpFix() = runBlocking {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val monitor = FakeNetwork()
        val ip = FakeIp()
        val resolver = resolver(ip = ip, network = monitor)

        val first = resolver.resolve(60_000)
        val cached = resolver.resolve(60_000)
        monitor.change("cellular")
        val afterChange = resolver.resolve(60_000)

        assertEquals(first, cached)
        assertEquals(2, ip.calls.get())
        assertEquals(LocationSource.IP, afterChange.source)
        resolver.close()
    }

    @Test fun networkChangeDuringIpLookupRetriesOnCurrentNetwork() = runBlocking {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val monitor = FakeNetwork()
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<IpCoordinates>()
        val calls = AtomicInteger()
        val ip = FakeIp {
            if (calls.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                finishFirst.await()
            } else IpCoordinates(-6.2, 106.8)
        }
        val resolver = resolver(ip = ip, network = monitor)
        val result = async { resolver.resolve(0, 4_000) }

        firstStarted.await()
        monitor.change("cellular")
        finishFirst.complete(IpCoordinates(-20.0, 20.0))
        val fix = result.await()

        assertEquals(2, ip.calls.get())
        assertEquals(-6.2, fix.latitude, 0.0)
        assertEquals(106.8, fix.longitude, 0.0)
        resolver.close()
    }

    @Test fun ineligibleDeviceCacheIsNotReturned() = runBlocking {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Shadows.shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        var allowed = true
        val device = FakeDevice { fix() }
        val ip = FakeIp()
        val resolver = resolver(device = device, ip = ip, allowed = { allowed })

        val first = resolver.resolve(60_000)
        allowed = false
        val second = resolver.resolve(60_000)

        assertEquals(LocationSource.DEVICE, first.source)
        assertEquals(LocationSource.IP, second.source)
        assertEquals("not_eligible", second.fallbackReason)
        assertEquals(1, device.calls.get())
        assertEquals(1, ip.calls.get())
        resolver.close()
    }

    @Test fun cachedIpFromIneligibleStateIsSkippedWhenDeviceBecomesEligible() = runBlocking {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Shadows.shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        var allowed = false
        val device = FakeDevice { fix() }
        val ip = FakeIp()
        val resolver = resolver(device = device, ip = ip, allowed = { allowed })

        val first = resolver.resolve(60_000)
        allowed = true
        val second = resolver.resolve(60_000)

        assertEquals(LocationSource.IP, first.source)
        assertEquals(LocationSource.DEVICE, second.source)
        assertEquals(1, ip.calls.get())
        assertEquals(1, device.calls.get())
        resolver.close()
    }

    @Test fun cachedIpAfterNativeTimeoutIsReusedWhileEligibilityIsUnchanged() = runBlocking {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Shadows.shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        val device = FakeDevice { throw LocationFailure("timeout", 3, "Lokasi belum ditemukan.") }
        val ip = FakeIp()
        val resolver = resolver(device = device, ip = ip, allowed = { true })

        val first = resolver.resolve(0, 30_000)
        val cached = resolver.resolve(60_000, 30_000)

        assertEquals(LocationSource.IP, first.source)
        assertEquals(first, cached)
        assertEquals("timeout", cached.fallbackReason)
        assertEquals(1, device.calls.get())
        assertEquals(1, ip.calls.get())
        resolver.close()
    }

    @Test fun callerCancellationIsNotConvertedToLocationTimeout() = runBlocking {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<IpCoordinates>()
        val resolver = resolver(ip = FakeIp {
            entered.complete(Unit)
            blocked.await()
        })

        val failure = runCatching { withTimeout(50) { resolver.resolve(0, 5_000) } }.exceptionOrNull()
        entered.await()

        assertTrue(failure is TimeoutCancellationException)
        resolver.close()
    }

    @Test fun shortTimeoutCallerDoesNotAbortLongerConcurrentLookup() = runBlocking {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val resolver = resolver(ip = FakeIp {
            if (calls.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                firstGate.await()
            }
            IpCoordinates(-6.2, 106.8)
        })
        val shortCaller = async { runCatching { resolver.resolve(0, 100) } }
        firstStarted.await()
        val longCaller = async { resolver.resolve(0, 5_000) }
        delay(20)
        assertTrue(shortCaller.await().isFailure)
        firstGate.complete(Unit)
        val fix = longCaller.await()
        assertEquals(-6.2, fix.latitude, 0.0)
        resolver.close()
    }

    private fun resolver(
        device: NativeLocationSource = FakeDevice { error("device should be skipped") },
        ip: IpLocationSource = FakeIp(),
        network: LocationNetworkMonitor = FakeNetwork(),
        allowed: () -> Boolean = { true },
    ) = LocationResolver(app, allowed, device, ip, network)

    private fun fix() = Location("test").apply {
        latitude = -6.2
        longitude = 106.8
        accuracy = 8f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private class FakeDevice(private val provide: suspend (Long) -> Location) : NativeLocationSource {
        val calls = AtomicInteger()
        override suspend fun current(timeoutMs: Long): Location {
            calls.incrementAndGet()
            return provide(timeoutMs)
        }
        override fun clear() = Unit
    }

    private class FakeIp(
        private val provide: suspend (Long) -> IpCoordinates = { IpCoordinates(-6.2, 106.8) },
    ) : IpLocationSource {
        val calls = AtomicInteger()
        override suspend fun locate(timeoutMs: Long): IpCoordinates {
            calls.incrementAndGet()
            return provide(timeoutMs)
        }
    }

    private class FakeNetwork : LocationNetworkMonitor {
        private var current: Any? = "wifi"
        private var listener: (() -> Unit)? = null
        override fun activeNetwork(): Any? = current
        override fun observe(onChange: () -> Unit): () -> Unit {
            listener = onChange
            return { listener = null }
        }
        fun change(value: Any?) {
            current = value
            listener?.invoke()
        }
    }
}

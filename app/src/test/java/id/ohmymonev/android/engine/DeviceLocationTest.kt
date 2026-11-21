package id.ohmymonev.android.engine

import android.app.Application
import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceLocationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val main get() = Shadows.shadowOf(android.os.Looper.getMainLooper())
    private fun fix(age: Long = 0) = Location("gps").apply {
        latitude = -6.2; longitude = 106.8; accuracy = 10f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - age * 1_000_000
    }

    @Test fun cacheRejectsOldFutureAndZeroAgeRequests() {
        main.idleFor(Duration.ofMinutes(2))
        assertTrue(DeviceLocation.fresh(fix(500), 1000))
        assertFalse(DeviceLocation.fresh(fix(1001), 1000))
        assertFalse(DeviceLocation.fresh(fix(-500), 1000))
        assertFalse(DeviceLocation.fresh(fix(), 0))
    }

    @Test fun bridgeAcceptsOnlyTrustedTopFrame() {
        assertTrue(LocationBridge.accepts("https://monev.maganghub.kemnaker.go.id", true))
        assertFalse(LocationBridge.accepts("https://monev.maganghub.kemnaker.go.id", false))
        assertFalse(LocationBridge.accepts("https://monev.maganghub.kemnaker.go.id.evil.test", true))
        assertFalse(LocationBridge.accepts("http://monev.maganghub.kemnaker.go.id", true))
    }

    @Test fun deniedPermissionHasDistinctFailure() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        var reason: String? = null
        CoroutineScope(Dispatchers.Main).launch {
            try { DeviceLocation(app).current(0, 1000) } catch (e: LocationFailure) { reason = e.reason }
        }
        main.idle()
        assertEquals("permission", reason)
    }

    @Test fun locationDisabledHasDistinctFailure() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        Shadows.shadowOf(manager).setLocationEnabled(false)
        var reason: String? = null
        CoroutineScope(Dispatchers.Main).launch {
            try { DeviceLocation(app).current(0, 1000) } catch (e: LocationFailure) { reason = e.reason }
        }
        main.idle()
        assertEquals("disabled", reason)
    }

    @Test fun freshFixWithoutCacheCompletesAndRemovesListeners() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled("gps", true)
        var received: Location? = null
        val device = DeviceLocation(app)
        CoroutineScope(Dispatchers.Main).launch { received = device.current(0, 1000) }
        main.idle()
        shadow.simulateLocation(fix())
        main.idle()
        assertNotNull(received)
        assertTrue(shadow.getLocationUpdateListeners().isEmpty())
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        var revoked: String? = null
        CoroutineScope(Dispatchers.Main).launch {
            try { device.current(60_000, 1000) } catch (e: LocationFailure) { revoked = e.reason }
        }
        main.idle()
        assertEquals("permission", revoked)
    }

    @Test fun timeoutAndCancellationRemoveListenersAndIgnoreLateFix() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val shadow = Shadows.shadowOf(app.getSystemService(LocationManager::class.java))
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled("gps", true)
        var reason: String? = null
        var successes = 0
        CoroutineScope(Dispatchers.Main).launch {
            try { DeviceLocation(app).current(0, 1000); successes++ } catch (e: LocationFailure) { reason = e.reason }
        }
        main.idleFor(Duration.ofSeconds(2))
        assertEquals("timeout", reason)
        assertTrue(shadow.getLocationUpdateListeners().isEmpty())
        val job = CoroutineScope(Dispatchers.Main).launch { DeviceLocation(app).current(0, 1000); successes++ }
        main.idle()
        job.cancel()
        main.idle()
        shadow.simulateLocation(fix())
        main.idle()
        assertEquals(0, successes)
        assertTrue(shadow.getLocationUpdateListeners().isEmpty())
    }

    @Test fun locationBridgeSanitizesNullAndInvalidTimeouts() {
        assertTrue(LocationBridge.SCRIPT.contains("isFinite(rawTimeout)"))
        assertTrue(LocationBridge.SCRIPT.contains("options.timeout==null?30000"))
    }
}

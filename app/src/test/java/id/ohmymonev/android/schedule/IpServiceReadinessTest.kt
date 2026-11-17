package id.ohmymonev.android.schedule

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IpServiceReadinessTest {
    @Test fun scheduledServiceCanRunAsDataSyncWithoutLocationPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        var calls = 0
        val previous = AutoFillDelegateProvider.delegate
        val controller = Robolectric.buildService(AutoFillService::class.java).create()
        try {
            AutoFillDelegateProvider.delegate = object : AutoFillDelegate {
                override fun startAutoFill(context: Context) { calls++ }
                override fun startManual(request: ManualReportRequest) = Unit
            }
            controller.get().onStartCommand(Intent(app, AutoFillService::class.java), 0, 1)
            assertEquals(1, calls)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, controller.get().foregroundServiceType)
            assertFalse(AutoFillService.nativeLocationAllowed)
        } finally { controller.destroy(); AutoFillDelegateProvider.delegate = previous }
    }
}

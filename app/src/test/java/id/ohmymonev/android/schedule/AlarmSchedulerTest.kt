package id.ohmymonev.android.schedule

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.ohmymonev.core.schedule.ScheduleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AlarmSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarms = context.getSystemService(AlarmManager::class.java)

    @Test fun pausingAutomationRemovesPreviouslyScheduledAlarms() {
        AlarmScheduler.armAll(context, ScheduleSettings())
        assertEquals(3, shadowOf(alarms).scheduledAlarms.size)
        AlarmScheduler.armAll(context, ScheduleSettings(masterEnabled = false))
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test fun disablingOneAlarmKeepsTheOtherTwo() {
        AlarmScheduler.armAll(context, ScheduleSettings())
        AlarmScheduler.armAll(context, ScheduleSettings(autofillEnabled = false))
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
    }

    @Test fun foreignAlarmIsNeverUsedAsTheFeatureSchedule() {
        val foreign = android.app.PendingIntent.getBroadcast(context, 777,
            android.content.Intent("other.app.ALARM"), android.app.PendingIntent.FLAG_IMMUTABLE)
        val now = java.time.ZonedDateTime.parse("2026-11-07T20:36:00+07:00[Asia/Jakarta]")
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(now.plusMinutes(15).toInstant().toEpochMilli(), foreign), foreign)
        val state = AlarmScheduler.readArmedState(context, ScheduleSettings(), now)
        assertEquals("2026-11-07T14:00:00Z", state.nextFireISO)
        assertEquals("Pengisian", state.source)
        org.junit.Assert.assertFalse(state.armed)
        val off = AlarmScheduler.ensureArmed(context, ScheduleSettings(masterEnabled = false))
        org.junit.Assert.assertNull(off.nextFireISO)
        assertEquals("Semua fitur nonaktif", off.source)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    }

    @Test fun masterOffCancelsNormalRetryAndTestAlarmsOnly() {
        AlarmScheduler.armAll(context, ScheduleSettings())
        AlarmScheduler.scheduleRetry(context, java.time.ZonedDateTime.now().plusMinutes(5))
        AlarmScheduler.quickFireForTest(context)
        assertEquals(5, shadowOf(alarms).scheduledAlarms.size)
        AlarmScheduler.ensureArmed(context, ScheduleSettings(masterEnabled = false))
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test fun clockDetailsOpenTheAppInsteadOfRunningAReceiver() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AlarmScheduler.armAll(context, ScheduleSettings())
        val show = requireNotNull(alarms.nextAlarmClock).showIntent
        assertEquals(id.ohmymonev.android.MainActivity::class.java.name,
            shadowOf(show).savedIntent.component?.className)
        assertTrue(show.isActivity)
    }

    @Test fun restoredExactPermissionClearsTheOldInstallationWarning() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        org.junit.Assert.assertNotNull(AlarmScheduler.ensureArmed(context, ScheduleSettings()).lastError)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        org.junit.Assert.assertNull(AlarmScheduler.ensureArmed(context, ScheduleSettings()).lastError)
    }
    @Test fun configuredTimeUsesJakartaEvenWhenTheDeviceClockUsesAnotherZone() {
        val state = AlarmScheduler.readArmedState(context, ScheduleSettings(), java.time.ZonedDateTime.parse("2026-11-07T13:36:00Z"))
        assertEquals("2026-11-07T14:00:00Z", state.nextFireISO)
    }
}

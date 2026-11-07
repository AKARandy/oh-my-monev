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
}

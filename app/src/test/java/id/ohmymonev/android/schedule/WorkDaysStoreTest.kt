package id.ohmymonev.android.schedule

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.ohmymonev.core.schedule.ScheduleSettings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkDaysStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val calendar = WorkDaysStore(context)
    private fun profile(vararg off: String) = JSONObject().put("id", "participant").put("off_days", JSONArray(off.toList()))
    @Test fun saturdayIsAWorkdayWhenOnlySundayIsOff() {
        calendar.save(profile("SUNDAY"))
        assertTrue(calendar.isWorkday(LocalDate.parse("2026-11-07")))
        assertFalse(calendar.isWorkday(LocalDate.parse("2026-11-08")))
    }
    @Test fun customProfileCanWorkSundayAndHaveFridayOff() {
        calendar.save(profile("FRIDAY"))
        assertTrue(calendar.isWorkday(LocalDate.parse("2026-11-08")))
        assertFalse(calendar.isWorkday(LocalDate.parse("2026-11-06")))
    }
    @Test fun sundayAlarmMovesToMondayForAWeekdayProfile() {
        calendar.save(profile("SATURDAY", "SUNDAY"))
        val state = AlarmScheduler.readArmedState(context, ScheduleSettings(), ZonedDateTime.parse("2026-11-08T12:20:00+07:00[Asia/Jakarta]"))
        assertEquals("2026-11-09T13:00:00Z", state.nextFireISO)
        assertEquals("Pengingat", state.source)
    }
    @Test fun featureDaysRemainAnAdditionalLimitAndAreNotOverwritten() {
        calendar.save(profile("SUNDAY"))
        val settings = ScheduleSettings(reminderDays = setOf(DayOfWeek.SUNDAY, DayOfWeek.SATURDAY))
        assertEquals(setOf(DayOfWeek.SATURDAY), calendar.restrict(settings).reminderDays)
        assertEquals(setOf(DayOfWeek.SUNDAY, DayOfWeek.SATURDAY), settings.reminderDays)
    }
    @Test fun missingOrInvalidProfileNeverDefaultsToWeekendsOrEveryDay() {
        calendar.clear(); assertNull(calendar.days())
        calendar.save(JSONObject().put("id", "participant")); assertNull(calendar.days())
        calendar.save(profile("INVALID")); assertNull(calendar.days())
        assertFalse(calendar.isWorkday(LocalDate.parse("2026-11-09")))
    }

    @Test fun receiversStaySilentOnTheProfilesOffDay() = kotlinx.coroutines.runBlocking {
        readySession(context)
        val app = context as Application
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        TimeSettingsStore(context).save(ScheduleSettings())
        val today = LocalDate.now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta)
        calendar.save(profile(today.dayOfWeek.name))
        ReminderReceiver().onReceive(context, android.content.Intent(ReminderReceiver.ACTION_REMINDER))
        AutoFillReceiver().onReceive(context, android.content.Intent(AutoFillReceiver.ACTION_AUTOFILL))
        EscalationReceiver().onReceive(context, android.content.Intent(EscalationReceiver.ACTION_ESCALATION))
        val notifications = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
        assertTrue(notifications.allNotifications.isEmpty())
        assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedService)
        calendar.save(profile())
        ReminderReceiver().onReceive(context, android.content.Intent(ReminderReceiver.ACTION_REMINDER))
        assertTrue(notifications.allNotifications.isEmpty())
        assertTrue(ReminderController.hasPending(context))
        assertEquals(GuardService::class.java.name,
            org.robolectric.Shadows.shadowOf(app).nextStartedService.component?.className)
        ReminderController.clear(context)
    }

    @Test fun legacyWorkdayQueuesOriginalDateWithoutAnAccount() = kotlinx.coroutines.runBlocking {
        readySession(context)
        val settings = ScheduleSettings()
        TimeSettingsStore(context).save(settings)
        val date = LocalDate.now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta).minusDays(1)
        val profileCache = context.getSharedPreferences("work_days", Context.MODE_PRIVATE)
        profileCache.edit().putString("days", DayOfWeek.entries.joinToString(",") { it.name })
            .remove("account").commit()
        assertNull(calendar.lastAccount())

        ReminderReceiver().onReceive(context, android.content.Intent(ReminderReceiver.ACTION_REMINDER)
            .putExtra("date", date.toString()))

        val queued = context.getSharedPreferences("reminder_checks", Context.MODE_PRIVATE)
        assertTrue(ReminderController.hasPending(context))
        assertEquals(date.toString(), queued.all.keys.single().substringAfterLast('/'))
        val alarms = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        val next = requireNotNull(alarms.peekNextScheduledAlarm())
        assertTrue(next.triggerAtTime > System.currentTimeMillis())
        ReminderController.clear(context)
    }

}

package id.ohmymonev.android.ui

import id.ohmymonev.core.schedule.ScheduleSettings
import java.time.DayOfWeek
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSummaryTest {
    private val now = ZonedDateTime.parse("2026-11-09T19:00:00+07:00[Asia/Jakarta]")

    @Test fun pausedAndSkippedSchedulesDoNotPromiseAJob() {
        assertTrue(automationSummary(ScheduleSettings(masterEnabled = false), now, false).contains("dijeda"))
        assertTrue(automationSummary(ScheduleSettings(), now, true).contains("malam ini dijeda"))
    }

    @Test fun reminderOnlyCanStillHaveAnIndependentLastChanceFill() {
        val settings = ScheduleSettings(autofillPolicy = ScheduleSettings.Policy.REMIND_ONLY)
        assertTrue(automationSummary(settings, now, false).contains("22:30 WIB"))
        assertEquals(
            "Tidak ada pengisian otomatis terjadwal. Isi laporan lewat situs.",
            automationSummary(settings.copy(escalationAction = ScheduleSettings.Policy.REMIND_ONLY), now, false),
        )
    }

    @Test fun disabledMainFillDoesNotHideEnabledLastChance() {
        assertTrue(automationSummary(ScheduleSettings(autofillEnabled = false), now, false).contains("22:30 WIB"))
    }

    @Test fun inactiveTodayShowsTheDateOfTheNextActiveDay() {
        val settings = ScheduleSettings(autofillDays = setOf(DayOfWeek.TUESDAY), escalationDays = setOf(DayOfWeek.TUESDAY))
        assertTrue(automationSummary(settings, now, false).contains("10 Nov 21:00 WIB"))
    }

    @Test fun passedTimesAreNeverPresentedAsUpcomingToday() {
        assertTrue(automationSummary(ScheduleSettings(), now.withHour(23), false).contains("10 Nov 21:00 WIB"))
    }
}

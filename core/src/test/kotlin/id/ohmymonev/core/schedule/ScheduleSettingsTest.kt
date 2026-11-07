package id.ohmymonev.core.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class ScheduleSettingsTest {

    @Test
    fun `automatic sending belongs to the originating feature`() {
        val s = ScheduleSettings(autofillPolicy = ScheduleSettings.Policy.FILL_AND_SUBMIT)
        assertEquals(ScheduleSettings.Policy.FILL_AND_SUBMIT, s.policyFor(lastChance = false))
        assertEquals(ScheduleSettings.Policy.FILL_ONLY, s.policyFor(lastChance = true))
        val changed = s.copy(autofillPolicy = ScheduleSettings.Policy.REMIND_ONLY, escalationAction = ScheduleSettings.Policy.FILL_AND_SUBMIT)
        assertEquals(ScheduleSettings.Policy.REMIND_ONLY, changed.policyFor(lastChance = false))
        assertEquals(ScheduleSettings.Policy.FILL_AND_SUBMIT, changed.policyFor(lastChance = true))
    }

    @Test
    fun `defaults match the locked night ladder D7`() {
        val s = ScheduleSettings()
        assertEquals(LocalTime.of(20, 0), s.reminderTime)
        assertEquals(LocalTime.of(21, 0), s.autofillTime)
        assertEquals(LocalTime.of(22, 30), s.escalationTime)
        assertEquals(30L, s.cancelWindowMinutes)
    }

    @Test
    fun `default active days are all seven days`() {
        val s = ScheduleSettings()
        assertEquals(DayOfWeek.entries.toSet(), s.reminderDays)
        assertEquals(DayOfWeek.entries.toSet(), s.autofillDays)
        assertEquals(DayOfWeek.entries.toSet(), s.escalationDays)
    }

    @Test
    fun `default policies start in Mode Uji with last chance escalation D8`() {
        val s = ScheduleSettings()
        assertEquals("Mode Uji (D8): the app must never auto-submit before explicit opt-in — money-critical blast radius",
            ScheduleSettings.Policy.FILL_ONLY, s.autofillPolicy)
        assertEquals(ScheduleSettings.Policy.FILL_ONLY, s.escalationAction)
    }
}

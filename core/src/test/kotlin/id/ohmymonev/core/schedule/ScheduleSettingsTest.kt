package id.ohmymonev.core.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class ScheduleSettingsTest {

    @Test
    fun `defaults match the locked night ladder D7`() {
        val s = ScheduleSettings()
        assertEquals(LocalTime.of(20, 0), s.reminderTime)
        assertEquals(LocalTime.of(22, 30), s.escalationTime)
        assertEquals(30L, s.cancelWindowMinutes)
    }

    @Test
    fun `default active days are all seven days`() {
        val s = ScheduleSettings()
        assertEquals(DayOfWeek.entries.toSet(), s.reminderDays)
        assertEquals(DayOfWeek.entries.toSet(), s.escalationDays)
    }

    @Test
    fun `default source is the user template, never the risky automatic`() {
        assertEquals(ScheduleSettings.ContentSource.TEMPLATE, ScheduleSettings().escalationSource)
    }
}

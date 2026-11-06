package id.ohmymonev.core.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleCalculatorTest {

    private val wib: ZoneId = ScheduleCalculator.asiaJakarta

    /** Senin 2026-11-02, hari biasa. */
    private val normalDay: LocalDate = LocalDate.of(2026, 11, 2)

    private fun at(day: LocalDate, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(day, LocalTime.of(hour, minute), wib)

    private fun settings(
        reminder: LocalTime = LocalTime.of(20, 0),
        autofill: LocalTime = LocalTime.of(21, 0),
        escalation: LocalTime = LocalTime.of(22, 30),
        activeDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    ) = ScheduleSettings(
        reminderTime = reminder,
        autofillTime = autofill,
        escalationTime = escalation,
        activeDays = activeDays,
    )

    private fun wallClock(fire: ZonedDateTime): LocalDateTime =
        LocalDateTime.ofInstant(fire.toInstant(), wib)

    // ---------------------------------------------------------------- threeFires

    @Test
    fun `threeFires returns the three ladders on a normal day`() {
        val fires = ScheduleCalculator.threeFires(normalDay, settings())
        assertEquals(3, fires.size)
        assertEquals(at(normalDay, 20, 0), fires[0])
        assertEquals(at(normalDay, 21, 0), fires[1])
        assertEquals(at(normalDay, 22, 30), fires[2])
    }

    @Test
    fun `threeFires returns empty on an inactive day`() {
        val mondayOnly = settings(activeDays = setOf(DayOfWeek.MONDAY))
        assertTrue(ScheduleCalculator.threeFires(LocalDate.of(2026, 11, 3), mondayOnly).isEmpty())
        assertEquals(3, ScheduleCalculator.threeFires(normalDay, mondayOnly).size)
    }

    // ------------------------------------------------------------------ nextFires

    @Test
    fun `nextFires before reminder on the same day`() {
        val now = at(normalDay, 8, 15)
        val fires = ScheduleCalculator.nextFires(now, settings())
        assertEquals(at(normalDay, 20, 0), fires.getValue(ScheduleCalculator.KEY_REMINDER))
        assertEquals(at(normalDay, 21, 0), fires.getValue(ScheduleCalculator.KEY_AUTOFILL))
        assertEquals(at(normalDay, 22, 30), fires.getValue(ScheduleCalculator.KEY_ESCALATION))
    }

    @Test
    fun `nextFires between reminder and autofill keeps same night tail`() {
        val now = at(normalDay, 20, 30)
        val fires = ScheduleCalculator.nextFires(now, settings())
        // Pengingat 20:00 sudah terlewat -> berikutnya besok.
        assertEquals(
            at(normalDay.plusDays(1), 20, 0),
            fires.getValue(ScheduleCalculator.KEY_REMINDER),
        )
        assertEquals(at(normalDay, 21, 0), fires.getValue(ScheduleCalculator.KEY_AUTOFILL))
        assertEquals(at(normalDay, 22, 30), fires.getValue(ScheduleCalculator.KEY_ESCALATION))
    }

    @Test
    fun `nextFires past all fires rolls to the next day`() {
        val now = at(normalDay, 23, 45)
        val fires = ScheduleCalculator.nextFires(now, settings())
        val tomorrow = normalDay.plusDays(1)
        assertEquals(at(tomorrow, 20, 0), fires.getValue(ScheduleCalculator.KEY_REMINDER))
        assertEquals(at(tomorrow, 21, 0), fires.getValue(ScheduleCalculator.KEY_AUTOFILL))
        assertEquals(at(tomorrow, 22, 30), fires.getValue(ScheduleCalculator.KEY_ESCALATION))
    }

    @Test
    fun `nextFires skips inactive days`() {
        // Senin-Sabtu aktif, Minggu non-aktif. Sabtu 2026-11-07 23:00 ->
        // aktivasi berikutnya Senin 2026-11-09.
        val monToSat = settings(activeDays = DayOfWeek.entries.filter { it.value <= 6 }.toSet())
        val now = at(LocalDate.of(2026, 11, 7), 23, 0)
        val fires = ScheduleCalculator.nextFires(now, monToSat)
        assertEquals(LocalDate.of(2026, 11, 9), fires.getValue(ScheduleCalculator.KEY_REMINDER).toLocalDate())
        assertEquals(20, fires.getValue(ScheduleCalculator.KEY_REMINDER).hour)
    }

    @Test
    fun `nextFires with a late autofill keeps escalation same night unchanged policy`() {
        // Dokumentasi kebijakan (D4 tidak berubah): meski autofill digeser ke 23:40,
        // eskalasi TETAP 22:30 malam yang sama — kalkulator tidak menyusun ulang alarm.
        val late = settings(autofill = LocalTime.of(23, 40))
        val now = at(normalDay, 22, 0)
        val fires = ScheduleCalculator.nextFires(now, late)
        assertEquals(at(normalDay, 22, 30), fires.getValue(ScheduleCalculator.KEY_ESCALATION))
        assertEquals(at(normalDay, 23, 40), fires.getValue(ScheduleCalculator.KEY_AUTOFILL))
    }

    @Test
    fun `nextFires key set is the three documented keys`() {
        val fires = ScheduleCalculator.nextFires(at(normalDay, 8, 0), settings())
        assertEquals(
            setOf(
                ScheduleCalculator.KEY_REMINDER,
                ScheduleCalculator.KEY_AUTOFILL,
                ScheduleCalculator.KEY_ESCALATION,
            ),
            fires.keys,
        )
    }

    // ---------------------------------------------------------------- retryTimes

    @Test
    fun `retry ladder is plus 5 20 60 minutes with no clamping`() {
        val failedAt = at(normalDay, 21, 5)
        val deadline = at(normalDay, 22, 30)
        val retries = ScheduleCalculator.retryTimes(failedAt, deadline)
        assertEquals(3, retries.size)
        assertEquals("2026-11-02T21:10", wallClock(retries[0]).toString())
        assertEquals("2026-11-02T21:25", wallClock(retries[1]).toString())
        assertEquals("2026-11-02T22:05", wallClock(retries[2]).toString())
    }

    @Test
    fun `retry ladder clamps to just before the deadline`() {
        val failedAt = at(normalDay, 22, 0)
        val deadline = at(normalDay, 22, 30)
        val retries = ScheduleCalculator.retryTimes(failedAt, deadline)
        assertEquals(3, retries.size)
        assertEquals("2026-11-02T22:05", wallClock(retries[0]).toString())
        assertEquals("2026-11-02T22:20", wallClock(retries[1]).toString())
        // +60 menit (23:00) dijepit ke 22:29:59 — ketat sebelum deadline.
        assertEquals("2026-11-02T22:29:59", wallClock(retries[2]).toString())
    }

    @Test
    fun `retry ladder collapses duplicates when the window is tight`() {
        val failedAt = at(normalDay, 22, 29)
        val deadline = at(normalDay, 22, 30)
        val retries = ScheduleCalculator.retryTimes(failedAt, deadline)
        // Semua kandidat dijepit ke 22:29:59 -> digabung menjadi satu.
        assertEquals(1, retries.size)
        assertEquals("2026-11-02T22:29:59", wallClock(retries[0]).toString())
    }

    @Test
    fun `retry ladder returns empty when the deadline already passed`() {
        val failedAt = at(normalDay, 21, 0)
        assertTrue(ScheduleCalculator.retryTimes(failedAt, at(normalDay, 20, 30)).isEmpty())
    }

    @Test
    fun `retry ladder returns empty when there is no room before the deadline`() {
        val failedAt = at(normalDay, 22, 30)
        assertTrue(ScheduleCalculator.retryTimes(failedAt, failedAt).isEmpty())
    }

    // ----------------------------------------------------------------------- DST

    @Test
    fun `asiaJakarta never changes offset across the whole horizon`() {
        // Asia/Jakarta tidak punya DST: cek offset +07:00 di seluruh horizon 7 hari.
        val start = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, wib)
        val fires = ScheduleCalculator.nextFires(start, settings(), horizonDays = 7)
        assertEquals(3, fires.size)
        for ((_, fire) in fires) {
            assertEquals(ZoneId.of("Asia/Jakarta"), fire.zone)
            assertEquals("+07:00", fire.offset.id)
            assertEquals(fire.offset, wib.rules.getOffset(fire.toInstant()))
        }
    }
}

package id.ohmymonev.core.schedule

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Kalkulator murni untuk tangga jadwal malam (D4/D7). Tidak ada dependensi Android.
 * Zona waktu WIB (Asia/Jakarta) tidak memiliki DST — offset selalu +07:00.
 */
object ScheduleCalculator {

    /** Zona waktu pengguna; +07:00 tetap, tanpa DST sepanjang tahun. */
    val asiaJakarta: ZoneId = ZoneId.of("Asia/Jakarta")

    /** Kunci stabil untuk [nextFires]. */
    const val KEY_REMINDER = "reminder"
    const val KEY_ESCALATION = "escalation"

    private val allKeys = listOf(KEY_REMINDER, KEY_ESCALATION)

    /**
     * Aktivasi berikutnya untuk tiap kunci, ketat setelah [now], melewati hari non-aktif,
     * dibatasi [horizonDays] hari ke depan (hari ini tetap hitung). Kunci tanpa aktivasi
     * dalam horizon tidak ikut peta.
     */
    fun nextFires(
        now: ZonedDateTime,
        s: ScheduleSettings,
        horizonDays: Int = 7,
    ): Map<String, ZonedDateTime> {
        require(horizonDays >= 0) { "horizonDays tidak boleh negatif" }
        val start = now.toInstant()
        val startDate = now.toLocalDate()
        val times = mapOf(
            KEY_REMINDER to s.reminderTime,
            KEY_ESCALATION to s.escalationTime,
        )
        val days = mapOf(
            KEY_REMINDER to s.reminderDays,
            KEY_ESCALATION to s.escalationDays,
        )
        val result = LinkedHashMap<String, ZonedDateTime>()
        if (!s.masterEnabled) return result
        val enabled = buildSet {
            if (s.reminderEnabled) add(KEY_REMINDER)
            if (s.escalationEnabled) add(KEY_ESCALATION)
        }
        for (dayOffset in 0..horizonDays) {
            val day = startDate.plusDays(dayOffset.toLong())
            for (key in allKeys) {
                if (key !in enabled) continue
                if (day.dayOfWeek !in days.getValue(key)) continue
                if (result.containsKey(key)) continue
                val candidate = fire(day, times.getValue(key))
                if (candidate.toInstant().isAfter(start)) result[key] = candidate
            }
            if (result.size == enabled.size) break
        }
        return result
    }

    /**
     * Tangga coba ulang setelah kegagalan pengisian di [failedAt]: +5, +20, +60 menit.
     * Setiap momen dijepit agar tetap ketat sebelum [deadline] (maksimum: 1 detik
     * sebelum deadline); nilai yang identik setelah penjepitan digabung (dedup).
     * Kosong berarti mustahil (deadline sudah lewat/terlalu rapat) — pemanggil
     * wajib menangani kasus ini (jangan coba ulang, langsung eskalasi).
     */
    fun retryTimes(failedAt: ZonedDateTime, deadline: ZonedDateTime): List<ZonedDateTime> {
        if (!deadline.toInstant().isAfter(failedAt.toInstant())) return emptyList()
        val clampLimit = deadline.minus(1, ChronoUnit.SECONDS)
        if (!clampLimit.toInstant().isAfter(failedAt.toInstant())) return emptyList()

        val failedInstant = failedAt.toInstant()
        val limitInstant = clampLimit.toInstant()
        val out = ArrayList<ZonedDateTime>(3)
        var previous = failedInstant
        for (minutes in longArrayOf(5, 20, 60)) {
            val candidate = failedInstant.plus(Duration.ofMinutes(minutes))
            val clamped = if (candidate.isAfter(limitInstant)) limitInstant else candidate
            if (clamped.isAfter(previous)) {
                out.add(clamped.atZone(asiaJakarta))
                previous = clamped
            }
        }
        return out
    }

    private fun fire(day: LocalDate, time: LocalTime): ZonedDateTime =
        ZonedDateTime.of(day, time, asiaJakarta)
}

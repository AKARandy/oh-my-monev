package id.ohmymonev.core.schedule

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Konfigurasi tangga jadwal malam. Harga kegagalannya permanen:
 * laporan harian yang terlewat = stipend hari itu hilang, titik.
 * Semua waktu adalah jam dinding lokal WIB (Asia/Jakarta, tanpa DST).
 */
data class ScheduleSettings(
    /** Alarm pengingat "laporan belum diisi". Default 20:00. */
    val reminderTime: LocalTime = LocalTime.of(20, 0),

    /** Alarm mulai pengisian otomatis. Default 21:00. */
    val autofillTime: LocalTime = LocalTime.of(21, 0),

    /** Alarm eskalasi "malam terakhir". Default 22:30. */
    val escalationTime: LocalTime = LocalTime.of(22, 30),

    /** Jendela batal sebelum auto-submit (D9). Default 30 menit. */
    val cancelWindowMinutes: Long = 30,

    val reminderDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val autofillDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val escalationDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),

    /** Pengaturan otomatis: kalau mati, tidak ada alarm otomatis sama sekali. */
    val masterEnabled: Boolean = true,

    /** Pengingat malam boleh menyala? */
    val reminderEnabled: Boolean = true,

    /** Pengisian otomatis boleh jalan? */
    val autofillEnabled: Boolean = true,

    /** Peringatan terakhir boleh menyala? */
    val escalationEnabled: Boolean = true,

    /** Kebijakan pengisian otomatis malam ini. Default FILL_ONLY = Mode Uji (D8): isi laporan, KOTAK KONFIRMASI & Kirim tidak disentuh sampai pengguna menyetujuinya. */
    val autofillPolicy: Policy = Policy.FILL_ONLY,

    /** Tindakan peringatan terakhir, terpisah dari tindakan pengisian. */
    val escalationAction: Policy = Policy.FILL_ONLY,
) {
    enum class Policy { REMIND_ONLY, FILL_ONLY, FILL_AND_SUBMIT }

    fun policyFor(lastChance: Boolean): Policy = if (lastChance) escalationAction else autofillPolicy
}

package id.ohmymonev.core.schedule

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Konfigurasi tangga jadwal malam. Harga kegagalannya permanen:
 * laporan harian yang terlewat = stipend hari itu hilang, titik.
 * Semua waktu adalah jam dinding lokal WIB (Asia/Jakarta, tanpa DST).
 *
 * Dua fitur saja: Pengingat, dan Pengisian darurat — satu-satunya fitur isi
 * otomatis, dan selalu mengisi lalu mengirim.
 */
data class ScheduleSettings(
    /** Alarm pengingat "laporan belum diisi". Default 20:00. */
    val reminderTime: LocalTime = LocalTime.of(20, 0),

    /** Alarm pengisian darurat. Default 22:30. */
    val escalationTime: LocalTime = LocalTime.of(22, 30),

    /** Jendela batal sebelum auto-submit (D9). Default 30 menit. */
    val cancelWindowMinutes: Long = 30,

    val reminderDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val escalationDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),

    /** Pengaturan otomatis: kalau mati, tidak ada alarm otomatis sama sekali. */
    val masterEnabled: Boolean = true,

    /** Pengingat malam boleh menyala? */
    val reminderEnabled: Boolean = true,

    /** Pengisian darurat boleh jalan? Selalu isi lalu kirim. */
    val escalationEnabled: Boolean = false,

    /** Sumber isian pengisian darurat: templat pengguna atau lanjutan otomatis (eksperimen). Wajib pilih satu. */
    val escalationSource: ContentSource = ContentSource.TEMPLATE,
) {
    enum class ContentSource { TEMPLATE, AUTOMATIC }
}

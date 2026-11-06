package id.ohmymonev.android.ui

import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Klaim "sesi aktif" punya masa berlaku. Setelah ini, UI wajib minta periksa ulang,
 * bukan terus-terusan menjamin sesuatu yang belum dipastikan lagi.
 */
object SessionFreshness {
    const val FRESH_MINUTES = 90L

    fun isStale(checkedAtISO: String): Boolean = try {
        Duration.between(
            OffsetDateTime.parse(checkedAtISO),
            OffsetDateTime.now(ZoneOffset.ofHours(7)),
        ).toMinutes() > FRESH_MINUTES
    } catch (_: Exception) {
        true
    }
}

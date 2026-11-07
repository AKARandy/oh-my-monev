package id.ohmymonev.android.ui

import id.ohmymonev.core.schedule.ScheduleCalculator
import id.ohmymonev.core.schedule.ScheduleSettings
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun automationSummary(settings: ScheduleSettings, now: ZonedDateTime, skipped: Boolean): String {
    if (skipped) return "Otomatis malam ini dijeda. Isi laporan lewat situs."
    if (!settings.masterEnabled) return "Otomatis dijeda. Aktifkan kembali di Jadwal."

    val fires = ScheduleCalculator.nextFires(now, settings)
    val candidates = buildList {
        if (settings.autofillPolicy != ScheduleSettings.Policy.REMIND_ONLY) {
            fires[ScheduleCalculator.KEY_AUTOFILL]?.let { add(it) }
        }
        if (settings.escalationAction != ScheduleSettings.Policy.REMIND_ONLY) {
            fires[ScheduleCalculator.KEY_ESCALATION]?.let { add(it) }
        }
    }
    val next = candidates.minOrNull()
        ?: return "Tidak ada pengisian otomatis terjadwal. Isi laporan lewat situs."
    val format = if (next.toLocalDate() == now.toLocalDate()) "HH:mm" else "EEE, d MMM HH:mm"
    val time = next.format(DateTimeFormatter.ofPattern(format, Locale("id", "ID")))
    return "Pengisian berikutnya dijadwalkan $time WIB."
}

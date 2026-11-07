package id.ohmymonev.android.engine

import android.content.Context
import id.ohmymonev.core.enginecore.SessionState
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.schedule.ScheduleCalculator
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray

/** Read real report text using the participant UUID and an exact report date. */
class PastReportReader(private val context: Context) {
    data class PastReportResult(
        val sessionState: SessionState,
        val date: LocalDate,
        val draft: ReportDraft?,
        val source: String,
    )

    suspend fun read(): PastReportResult {
        val today = LocalDate.now(ScheduleCalculator.asiaJakarta)
        val api = MonevApiClient(context)
        val profile = api.objectData("/users/me")
            ?: return PastReportResult(api.failureState, today.minusDays(1), null, api.message)
        val participantId = profile.optString("id")
        if (profile.isNull("id") || participantId.isBlank()) {
            api.failed("Profil peserta belum dapat dibaca.")
            return PastReportResult(SessionState.UNKNOWN, today.minusDays(1), null, api.message)
        }
        api.confirmActive()
        val offDays = profile.optJSONArray("off_days")?.let { rows ->
            (0 until rows.length()).mapNotNull { runCatching { DayOfWeek.valueOf(rows.getString(it)) }.getOrNull() }.toSet()
        }.orEmpty()
        val date = previousWorkday(today, offDays)
            ?: return PastReportResult(SessionState.ACTIVE, today.minusDays(1), null, "Profil tidak memiliki hari kerja.")
        val month = date.withDayOfMonth(1)
        val id = URLEncoder.encode(participantId, "UTF-8")
        val logs = api.arrayData("/daily-logs?participant_id=$id&date=$month&limit=100")
            ?: return PastReportResult(api.failureState, date, null, api.message)
        api.confirmActive()
        val draft = exactReport(logs, date)
        val label = date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("id", "ID")))
        return PastReportResult(SessionState.ACTIVE, date, draft,
            if (draft == null) "Situs tidak memiliki laporan lengkap untuk $label." else "Dari laporan situs tanggal $label.")
    }

    companion object {
        internal fun previousWorkday(today: LocalDate, offDays: Set<DayOfWeek>): LocalDate? =
            (1L..7L).map { today.minusDays(it) }.firstOrNull { it.dayOfWeek !in offDays }

        internal fun exactReport(logs: JSONArray, date: LocalDate): ReportDraft? {
            for (i in 0 until logs.length()) {
                val row = logs.optJSONObject(i) ?: continue
                if (row.optString("date").take(10) != date.toString() || row.optString("state") == "DRAFT") continue
                fun text(key: String) = if (row.isNull(key)) "" else row.optString(key).trim()
                val activity = text("activity_log")
                val lesson = text("lesson_learned")
                val obstacle = text("obstacles")
                if (activity.isNotBlank() && lesson.isNotBlank() && obstacle.isNotBlank()) {
                    return ReportDraft(activity, lesson, obstacle)
                }
            }
            return null
        }
    }
}

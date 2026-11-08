package id.ohmymonev.android.engine

import android.content.Context
import id.ohmymonev.core.enginecore.SessionState
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.schedule.ScheduleCalculator
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

@Serializable
internal data class SourceReport(
    val participantId: String,
    val date: String,
    val attendanceStatus: String,
    val reportStatus: String,
    val aktivitas: String,
    val pelajaran: String,
    val kendala: String,
) {
    fun report() = ReportDraft(aktivitas, pelajaran, kendala)
    fun label(): String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID")))
}

/** Join attendance and submitted work reports by participant and date, newest month first. */
internal class PastReportReader(private val context: Context) {
    data class PastReportResult(
        val sessionState: SessionState,
        val material: SourceReport?,
        val message: String,
        val unavailable: Boolean = false,
    )

    suspend fun read(): PastReportResult {
        val api = MonevApiClient(context)
        val profile = api.objectData("/users/me") ?: return PastReportResult(api.failureState, null, api.message, true)
        return read(LocalDate.now(ScheduleCalculator.asiaJakarta), api, profile)
    }

    internal suspend fun read(today: LocalDate, api: MonevApiClient, profile: JSONObject): PastReportResult {
        suspend fun unavailable(reason: String): PastReportResult {
            api.failed(reason)
            return PastReportResult(api.failureState, null, reason, true)
        }
        val id = profile.optString("id").takeUnless { profile.isNull("id") }.orEmpty()
        val start = runCatching { LocalDate.parse(profile.getString("internship_start_date").take(10)) }.getOrNull()
        if (id.isBlank() || start == null) return unavailable("Periode magang belum dapat dibaca.")
        api.confirmActive()
        val encodedId = URLEncoder.encode(id, "UTF-8")
        var end = today.minusDays(1)
        while (!end.isBefore(start)) {
            val month = end.withDayOfMonth(1)
            val from = maxOf(start, month)
            val attendances = api.allPages("/attendances?participant_id=$encodedId&start_date=$from&end_date=$end&limit=100")
                ?: return PastReportResult(api.failureState, null, api.message, true)
            if ((0 until attendances.length()).any { parseDate(attendances.getJSONObject(it)) == null })
                return unavailable("Tanggal kehadiran belum dapat dibaca.")
            val present = (0 until attendances.length()).map { attendances.getJSONObject(it) }
                .filter { belongsTo(it, id) && it.optString("status") == "PRESENT" }
                .mapNotNull { row -> parseDate(row)?.takeIf { it >= from && it <= end } }.toSet()
            if (present.isNotEmpty()) {
                val logs = api.allPages("/daily-logs?participant_id=$encodedId&date=$month&limit=100")
                    ?: return PastReportResult(api.failureState, null, api.message, true)
                if ((0 until logs.length()).any { parseDate(logs.getJSONObject(it)) == null })
                    return unavailable("Tanggal laporan belum dapat dibaca.")
                val source = latestReport(logs, present, id)
                if (source != null) {
                    api.confirmActive()
                    return PastReportResult(SessionState.ACTIVE, source, "Hadir · ${source.label()}")
                }
            }
            end = month.minusDays(1)
        }
        return PastReportResult(SessionState.ACTIVE, null, "Belum ada laporan Hadir yang dapat digunakan")
    }

    companion object {
        internal val submittedStates = setOf("SUBMITTED", "REVISION_SUBMITTED", "APPROVED", "COMPLETED")
        private fun parseDate(row: JSONObject): LocalDate? = runCatching { LocalDate.parse(row.getString("date").take(10)) }.getOrNull()
        private fun belongsTo(row: JSONObject, id: String) = !row.has("participant_id") || row.optString("participant_id") == id

        internal fun latestReport(logs: JSONArray, present: Set<LocalDate>, id: String): SourceReport? =
            (0 until logs.length()).map { logs.getJSONObject(it) }
                .filter { belongsTo(it, id) && parseDate(it) in present && it.optString("state") in submittedStates }
                .sortedByDescending { parseDate(it) }
                .firstNotNullOfOrNull { row ->
                    fun text(key: String) = if (row.isNull(key)) "" else row.optString(key)
                    val activity = text("activity_log")
                    val lesson = text("lesson_learned")
                    val obstacle = text("obstacles")
                    if (activity.isBlank() || lesson.isBlank() || obstacle.isBlank()) null
                    else SourceReport(id, requireNotNull(parseDate(row)).toString(), "PRESENT", row.getString("state"), activity, lesson, obstacle)
                }
    }
}

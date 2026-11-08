package id.ohmymonev.android.engine

import android.content.Context
import id.ohmymonev.core.enginecore.DayEntry
import id.ohmymonev.core.enginecore.SessionState
import id.ohmymonev.core.enginecore.TodayState
import id.ohmymonev.core.schedule.ScheduleCalculator
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

data class SiteStatus(
    val sessionActive: Boolean?,
    val today: TodayState,
    val deadline: String?,
    val days: List<DayEntry>,
    val error: String? = null,
)

/** The same authenticated data that powers Monev's own Beranda. No DOM verdicts. */
class SiteStatusProbe(private val context: Context) {
    suspend fun check(): SiteStatus {
        val api = MonevApiClient(context)
        fun unavailable() = SiteStatus(if (api.failureState == SessionState.EXPIRED) false else null,
            TodayState.UNKNOWN, null, emptyList(), api.message)
        val profile = api.objectData("/users/me") ?: return unavailable()
        val id = profile.optString("id")
        if (id.isBlank()) { api.failed("Profil belum dapat dibaca."); return unavailable() }
        api.confirmActive()
        val home = api.objectData("/users/me/home") ?: return unavailable()
        if (home.opt("is_scheduled_off_day") !is Boolean) {
            api.failed("Kondisi hari ini belum dapat dibaca.")
            return unavailable()
        }
        val today = LocalDate.now(ScheduleCalculator.asiaJakarta)
        val encodedId = URLEncoder.encode(id, "UTF-8")
        val attendances = api.arrayData("/attendances?participant_id=$encodedId&start_date=${today.withDayOfMonth(1)}&end_date=$today&limit=100")
        if (api.failureState == SessionState.EXPIRED) return unavailable()
        if (attendances == null) { api.failed("Kehadiran hari ini belum dapat dibaca."); return unavailable() }
        api.confirmActive()
        val logs = api.allPages("/daily-logs?participant_id=$encodedId&date=${today.withDayOfMonth(1)}&limit=100")
            ?: return unavailable()
        if (api.failureState == SessionState.EXPIRED) return unavailable()
        api.confirmActive()
        val format = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale("id", "ID"))
        val days = (0 until attendances.length()).mapNotNull { attendances.optJSONObject(it) }.sortedBy { it.optString("date") }.mapNotNull { row ->
            val date = runCatching { LocalDate.parse(row.optString("date").take(10)) }.getOrNull() ?: return@mapNotNull null
            val state = attendanceLabel(row)
            DayEntry(date.format(format), state)
        }
        val attendanceToday = (0 until attendances.length()).map { attendances.optJSONObject(it) }
            .firstOrNull { row -> row != null && belongsTo(row, id) && rowDate(row) == today }?.optString("status")
        val reportStateToday = (0 until logs.length()).map { logs.optJSONObject(it) }
            .firstOrNull { row -> row != null && belongsTo(row, id) && rowDate(row) == today }?.optString("state")
        val state = todayState(home, attendanceToday, reportStateToday)
        return SiteStatus(true, state, if (state == TodayState.LIBUR || state == TodayState.LEAVE) null else "23:59", days)
    }

    companion object {
        /** Status kehadiran yang tidak menuntut laporan kerja: cuti, alpa, dan libur. */
        private val nonFillableAttendance = setOf("ON_LEAVE", "ABSENT", "DAYOFF")

        private fun rowDate(row: JSONObject): LocalDate? =
            runCatching { LocalDate.parse(row.optString("date").take(10)) }.getOrNull()

        private fun belongsTo(row: JSONObject, id: String) =
            !row.has("participant_id") || row.optString("participant_id") == id

        internal fun attendanceLabel(row: JSONObject): String {
            val kind = when (row.optString("status")) {
                "PRESENT" -> "Hadir"
                "ON_LEAVE" -> "Tidak hadir dengan keterangan"
                "ABSENT" -> "Tidak hadir tanpa keterangan"
                "DAYOFF" -> return "Hari libur"
                else -> return "Status belum dikenali"
            }
            val approval = when (row.optString("approval_status")) {
                "APPROVED" -> "disetujui"
                "SUBMITTED" -> "menunggu persetujuan"
                "REJECTED" -> "ditolak"
                else -> "status persetujuan belum diketahui"
            }
            return "$kind · $approval"
        }

        internal fun todayState(home: JSONObject, attendanceToday: String?, reportStateToday: String?): TodayState = when {
            home.optBoolean("is_holiday") || home.optBoolean("is_scheduled_off_day") -> TodayState.LIBUR
            attendanceToday != null && attendanceToday in nonFillableAttendance -> TodayState.LEAVE
            reportStateToday != null && reportStateToday in PastReportReader.submittedStates -> TodayState.FILLED
            else -> TodayState.UNFILLED
        }
    }
}

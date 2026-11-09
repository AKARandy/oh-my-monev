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
    val account: String? = null,
    val credentialVersion: Long? = null,
)

/** The same authenticated data that powers Monev's own Beranda. No DOM verdicts. */
class SiteStatusProbe(private val context: Context) {
    suspend fun check(): SiteStatus = read(null, null)

    internal suspend fun checkReminder(
        date: LocalDate,
        account: String,
        api: MonevApiClient = MonevApiClient(context),
    ): SiteStatus = read(date, account, api)

    private suspend fun read(
        date: LocalDate?,
        expectedAccount: String?,
        api: MonevApiClient = MonevApiClient(context),
    ): SiteStatus {
        fun unavailable() = SiteStatus(if (api.failureState == SessionState.EXPIRED) false else null,
            TodayState.UNKNOWN, null, emptyList(), api.message)
        val profile = api.objectData("/users/me") ?: return unavailable()
        val id = profile.optString("id")
        if (id.isBlank()) { api.failed("Profil belum dapat dibaca."); return unavailable() }
        api.confirmActive()
        if (expectedAccount != null && expectedAccount != id) {
            return SiteStatus(true, TodayState.UNKNOWN, null, emptyList(), account = id, credentialVersion = api.credentialVersion)
        }
        val currentDate = LocalDate.now(ScheduleCalculator.asiaJakarta)
        val today = date ?: currentDate
        val home = if (today == currentDate) api.objectData("/users/me/home") ?: return unavailable() else JSONObject()
        if (today == currentDate && home.opt("is_scheduled_off_day") !is Boolean) {
            api.failed("Kondisi hari ini belum dapat dibaca.")
            return unavailable()
        }
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
        if (date != null) {
            fun validRows(rows: org.json.JSONArray, validStates: Set<String>, field: String): Boolean =
                (0 until rows.length()).all { index ->
                    val row = rows.optJSONObject(index) ?: return@all false
                    val rowDay = rowDate(row) ?: return@all false
                    !belongsTo(row, id) || rowDay != date || row.optString(field) in validStates
                }
            if (attendances.length() > today.dayOfMonth || !api.isCurrent() ||
                !validRows(attendances, nonFillableAttendance + "PRESENT", "status") ||
                !validRows(logs, PastReportReader.submittedStates + setOf("DRAFT", "NEEDS_REVISION"), "state")
            ) {
                api.failed("Data laporan belum dapat diperiksa.")
                return unavailable()
            }
        }
        val state = todayState(home, attendanceToday, reportStateToday)
        return SiteStatus(true, state, if (state == TodayState.LIBUR || state == TodayState.LEAVE) null else "23:59", days, account = id, credentialVersion = api.credentialVersion)
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

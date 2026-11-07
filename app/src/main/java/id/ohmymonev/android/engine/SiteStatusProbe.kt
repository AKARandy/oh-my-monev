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
        if (home.opt("has_attendance") !is Boolean || home.opt("is_scheduled_off_day") !is Boolean) {
            api.failed("Kondisi hari ini belum dapat dibaca.")
            return unavailable()
        }
        val today = LocalDate.now(ScheduleCalculator.asiaJakarta)
        val attendances = api.arrayData("/attendances?participant_id=${URLEncoder.encode(id, "UTF-8")}&start_date=${today.withDayOfMonth(1)}&end_date=$today&limit=100")
        if (api.failureState == SessionState.EXPIRED) return unavailable()
        api.confirmActive()
        val format = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale("id", "ID"))
        val days = attendances?.let { rows ->
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.sortedBy { it.optString("date") }.mapNotNull { row ->
                val date = runCatching { LocalDate.parse(row.optString("date").take(10)) }.getOrNull() ?: return@mapNotNull null
                val state = when {
                    row.optString("status") == "DAYOFF" -> "Jadwal libur posisi"
                    row.optString("approval_status") == "APPROVED" -> if (row.optString("status") == "ON_LEAVE") "Izin disetujui" else "Hadir disetujui"
                    row.optString("approval_status") == "SUBMITTED" -> "Menunggu Persetujuan"
                    else -> "Belum Diisi"
                }
                DayEntry(date.format(format), state)
            }
        }.orEmpty()
        val state = todayState(home)
        return SiteStatus(true, state, if (state == TodayState.LIBUR) null else "23:59", days,
            if (attendances == null) "Riwayat belum dapat dibaca. Coba Periksa situs." else null)
    }

    companion object {
        internal fun todayState(home: JSONObject): TodayState = when {
            home.optBoolean("is_holiday") || home.optBoolean("is_scheduled_off_day") -> TodayState.LIBUR
            home.optBoolean("has_attendance") -> TodayState.FILLED
            else -> TodayState.UNFILLED
        }
    }
}

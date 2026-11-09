package id.ohmymonev.android.engine

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.android.data.SessionTokenStore
import id.ohmymonev.core.enginecore.SessionState
import id.ohmymonev.core.enginecore.TodayState
import java.io.File
import java.net.URI
import java.time.LocalDate
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PastReportSearchTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(base.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir() = directory
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$name-${directory.name}", mode)
    }
    private val profile = JSONObject().put("id", "participant").put("internship_start_date", "2026-09-01")
    private val calls = mutableListOf<String>()
    private var attendances = emptyList<JSONObject>()
    private var logs = emptyList<JSONObject>()
    private var pageSize = 100
    private var failPage: String? = null
    private var repeatedPage = false
    private var malformed = false
    private var http204 = false
    private var expired = false
    private var invalidPagination = false

    private fun attendance(date: String, status: String = "PRESENT", approval: String = "APPROVED") = JSONObject()
        .put("date", date).put("participant_id", "participant").put("status", status).put("approval_status", approval)
    private fun log(date: String, state: String = "SUBMITTED") = JSONObject().put("date", date).put("state", state)
        .put("participant_id", "participant").put("activity_log", "- Memeriksa data pertama\n- Menguji data kedua\n- Menyusun data ketiga")
        .put("lesson_learned", "Pelajaran asli $date").put("obstacles", "Kendala asli $date")

    private suspend fun api(): MonevApiClient {
        val vault = SessionTokenStore(context) { SecretKeySpec(ByteArray(16) { 1 }, "AES") }
        vault.save("synthetic-token")
        val session = SessionRepository(context, vault)
        session.credentials()
        return MonevApiClient(context, session) { _, path, _ ->
            calls += path
            val uri = URI("https://fixture.test$path")
            val query = uri.query.orEmpty().split('&').filter { it.isNotBlank() }.associate { it.substringBefore('=') to it.substringAfter('=') }
            if (expired) ApiResponse(401, "{}")
            else if (failPage?.let { path.contains(it) } == true) ApiResponse(503, "{}")
            else if (http204) ApiResponse(204, "")
            else if (malformed) ApiResponse(200, """{"data":{}}""")
            else if (uri.path == "/users/me") ApiResponse(200, profile.toString())
            else if (uri.path == "/users/me/home") ApiResponse(200, """{"is_scheduled_off_day":true}""")
            else if (uri.path == "/attendances" && "page" !in query) {
                ApiResponse(200, JSONArray(attendances).toString())
            } else {
                val data = if (uri.path == "/attendances") attendances.filter {
                    it.getString("date") >= query.getValue("start_date") && it.getString("date") <= query.getValue("end_date")
                } else logs.filter { it.getString("date") == "broken-date" || it.getString("date").take(7) == query.getValue("date").take(7) }
                val page = query.getValue("page").toInt()
                val offset = if (repeatedPage) 0 else (page - 1) * pageSize
                val rows = data.drop(offset).take(pageSize)
                ApiResponse(200, JSONObject().put("data", JSONArray(rows)).put("page", page)
                    .put("page_size", if (invalidPagination) JSONObject.NULL else pageSize).put("total", data.size).toString())
            }
        }
    }
    private suspend fun read() = PastReportReader(context).read(LocalDate.parse("2026-11-08"), api(), profile)

    @Test fun reminderUsesOriginalDateAndRejectsUnknownReportState() = runBlocking {
        val date = LocalDate.parse("2026-11-06")
        val probe = SiteStatusProbe(context)
        attendances = listOf(attendance(date.toString()))
        suspend fun state(reportState: String): TodayState {
            logs = listOf(log(date.toString(), reportState))
            return probe.checkReminder(date, "participant", api()).today
        }
        assertEquals(TodayState.FILLED, state("SUBMITTED"))
        assertEquals(TodayState.UNFILLED, state("DRAFT"))
        assertEquals(TodayState.UNKNOWN, state("unexpected"))
        assertFalse(calls.contains("/users/me/home"))
        assertEquals("participant", probe.checkReminder(date, "another-account", api()).account)
        expired = true
        assertEquals(false, probe.checkReminder(date, "participant", api()).sessionActive)
    }

    @Test fun sundayUsesFridayWhenPresent() = runBlocking {
        attendances = listOf(attendance("2026-11-06")); logs = listOf(log("2026-11-06"))
        assertEquals("2026-11-06", read().material?.date)
    }
    @Test fun leaveAbsentHolidayAndEmptyDaysAreSkippedEvenWhenApproved() = runBlocking {
        attendances = listOf(attendance("2026-11-07", "DAYOFF"), attendance("2026-11-06", "ON_LEAVE").put("leave_reason", "Alasan izin"),
            attendance("2026-11-05", "ABSENT"), attendance("2026-11-03"))
        logs = listOf(log("2026-11-05"), log("2026-11-04"), log("2026-11-03"))
        assertEquals("2026-11-03", read().material?.date)
    }
    @Test fun draftIncompleteAndUnknownStatesAreSkipped() = runBlocking {
        attendances = (3..6).map { attendance("2026-11-0$it") }
        logs = listOf(log("2026-11-06", "DRAFT"), log("2026-11-05").put("obstacles", JSONObject.NULL),
            log("2026-11-04", "UNKNOWN"), log("2026-11-03"))
        assertEquals("2026-11-03", read().material?.date)
    }
    @Test fun searchCrossesMonthsAndPagesWithoutPickingAnotherParticipant() = runBlocking {
        pageSize = 1
        attendances = listOf(attendance("2026-10-29"), attendance("2026-10-30"))
        logs = listOf(log("2026-10-31").put("participant_id", "someone-else"), log("2026-10-30"), log("2026-10-29"))
        assertEquals("2026-10-30", read().material?.date)
        assertTrue(calls.any { it.startsWith("/attendances?") && it.endsWith("page=2") })
        assertTrue(calls.any { it.startsWith("/daily-logs?") && it.endsWith("page=3") })
    }
    @Test fun emptyHistoryIsCompleteOnlyAfterReachingTheInternshipStart() = runBlocking {
        val result = read()
        assertNull(result.material); assertFalse(result.unavailable)
        assertEquals("Belum ada laporan Hadir yang dapat digunakan", result.message)
        assertEquals(3, calls.size)
        assertTrue(calls.last().contains("start_date=2026-09-01"))
    }
    @Test fun networkFailureMidSearchIsNotAnEmptyHistory() = runBlocking {
        failPage = "start_date=2026-10-01"
        val result = read()
        assertTrue(result.unavailable); assertNull(result.material)
        assertNotEquals("Belum ada laporan Hadir yang dapat digunakan", result.message)
    }
    @Test fun failureOnALaterPageNeverReturnsAnOlderPartialResult() = runBlocking {
        pageSize = 1; attendances = listOf(attendance("2026-11-05"), attendance("2026-11-06")); logs = listOf(log("2026-11-05"))
        failPage = "page=2"
        assertTrue(read().unavailable)
    }
    @Test fun unknownAttendanceCannotBecomePresent() = runBlocking {
        attendances = listOf(attendance("2026-11-06", "UNKNOWN")); logs = listOf(log("2026-11-06"))
        assertNull(read().material)
        assertFalse(calls.any { it.startsWith("/daily-logs?") })
    }
    @Test fun missingInternshipStartIsAReadFailure() = runBlocking {
        profile.remove("internship_start_date")
        assertTrue(read().unavailable); assertTrue(calls.isEmpty())
    }
    @Test fun repeatedPaginationIsRejected() = runBlocking {
        pageSize = 1; repeatedPage = true
        attendances = listOf(attendance("2026-11-05"), attendance("2026-11-06"))
        assertTrue(read().unavailable)
    }
    @Test fun malformedAndNoContentResponsesAreDistinct() = runBlocking {
        malformed = true
        assertTrue(read().unavailable)
        malformed = false; http204 = true
        assertFalse(read().unavailable)
    }
    @Test fun approvalDoesNotChangeTheAttendanceKind() {
        assertEquals("Tidak hadir tanpa keterangan · disetujui", SiteStatusProbe.attendanceLabel(attendance("2026-11-06", "ABSENT")))
        assertEquals("Tidak hadir dengan keterangan · disetujui", SiteStatusProbe.attendanceLabel(attendance("2026-11-06", "ON_LEAVE")))
        assertEquals("Hadir · menunggu persetujuan", SiteStatusProbe.attendanceLabel(attendance("2026-11-06", "PRESENT", "SUBMITTED")))
        assertEquals("Status belum dikenali", SiteStatusProbe.attendanceLabel(attendance("2026-11-06", "UNKNOWN")))
    }

    @Test fun expiredSessionIsNotReportedAsNoHistory() = runBlocking {
        expired = true
        val result = read()
        assertTrue(result.unavailable)
        assertEquals(SessionState.EXPIRED, result.sessionState)
        assertNotEquals("Belum ada laporan Hadir yang dapat digunakan", result.message)
    }
    @Test fun futureAndCurrentDayCannotBeSelected() = runBlocking {
        attendances = listOf(attendance("2026-11-08"), attendance("2026-11-09"), attendance("2026-11-05"))
        logs = listOf(log("2026-11-08"), log("2026-11-09"), log("2026-11-05"))
        assertEquals("2026-11-05", read().material?.date)
    }
    @Test fun malformedDatesAreReadFailures() = runBlocking {
        attendances = listOf(attendance("2026-11-06"))
        logs = listOf(log("2026-11-06").put("date", "broken-date"))
        assertTrue(read().unavailable)
    }

    @Test fun malformedPaginationCannotBeMistakenForTheCompleteHistory() = runBlocking {
        invalidPagination = true
        attendances = listOf(attendance("2026-11-06")); logs = listOf(log("2026-11-06"))
        assertTrue(read().unavailable)
    }
}

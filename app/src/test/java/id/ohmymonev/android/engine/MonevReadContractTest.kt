package id.ohmymonev.android.engine

import android.app.Application
import id.ohmymonev.core.enginecore.TodayState
import id.ohmymonev.core.generator.DatedReport
import id.ohmymonev.core.generator.GenerationOutcome
import id.ohmymonev.core.generator.GeneratorPipeline
import id.ohmymonev.core.generator.PoolBundle
import id.ohmymonev.core.generator.ReportHistory
import java.time.DayOfWeek
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MonevReadContractTest {
    private val date = LocalDate.parse("2026-11-06")
    private fun row(day: String) = JSONObject().put("date", day).put("state", "SUBMITTED")
        .put("activity_log", "- Melakukan pemeriksaan data pertama\n- Melakukan pemeriksaan data kedua\n- Melakukan pemeriksaan data ketiga")
        .put("lesson_learned", "Materi dari laporan asli")
        .put("obstacles", "Kendala dari laporan asli")

    @Test fun previewUsesOnlyTheExactRequestedDate() {
        val rows = JSONArray().put(row("2026-11-05").put("lesson_learned", "wrong row")).put(row("2026-11-06"))
        assertEquals("Materi dari laporan asli", PastReportReader.exactReport(rows, date)?.pelajaran)
        assertNull(PastReportReader.exactReport(JSONArray().put(row("2026-11-05")), date))
    }

    @Test fun draftOrNullMaterialCannotBecomePreviewSource() {
        assertNull(PastReportReader.exactReport(JSONArray().put(row(date.toString()).put("state", "DRAFT")), date))
        assertNull(PastReportReader.exactReport(JSONArray().put(row(date.toString()).put("activity_log", JSONObject.NULL)), date))
    }

    @Test fun mondayUsesFridayAcrossTheMonthBoundary() {
        assertEquals(LocalDate.parse("2026-10-30"), PastReportReader.previousWorkday(
            LocalDate.parse("2026-11-02"), setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)))
    }

    @Test fun validApiMaterialCanGenerateAPreview() {
        val material = requireNotNull(PastReportReader.exactReport(JSONArray().put(row(date.toString())), date))
        val pipeline = GeneratorPipeline(history = ReportHistory { DatedReport(date, material) },
            poolBundle = PoolBundle(listOf("Materi untuk hari ini"), listOf("Kendala untuk hari ini")))
        assertTrue(pipeline.generate(date.plusDays(1)) is GenerationOutcome.Generated)
    }

    @Test fun siteStatusUsesApiFlagsInsteadOfRenderedTabText() {
        assertEquals(TodayState.LIBUR, SiteStatusProbe.todayState(JSONObject().put("is_scheduled_off_day", true).put("has_attendance", false)))
        assertEquals(TodayState.FILLED, SiteStatusProbe.todayState(JSONObject().put("is_holiday", false).put("has_attendance", true)))
        assertEquals(TodayState.UNFILLED, SiteStatusProbe.todayState(JSONObject().put("is_scheduled_off_day", false).put("has_attendance", false)))
    }

    @Test fun restorationNeverSeedsCredentialsIntoAnotherOrigin() {
        assertFalse(SessionWebView.trusted("https://monev.maganghub.kemnaker.go.id.evil.example/"))
        assertFalse(SessionWebView.trusted("http://monev.maganghub.kemnaker.go.id/"))
        val html = SessionWebView.restoreHtml("synthetic</script>token", "https://monev.maganghub.kemnaker.go.id/dashboard")
        assertFalse(html.contains("synthetic</script>token"))
        assertTrue(html.contains("\\u003c"))
        assertEquals(1, Regex("</script>").findAll(html).count())
    }
}

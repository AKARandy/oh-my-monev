package id.ohmymonev.android.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import id.ohmymonev.core.enginecore.EngineOutcome
import id.ohmymonev.core.enginecore.RunRecord
import id.ohmymonev.core.model.ReportDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EngineFailureTest {
    @Test fun unexpectedRunExceptionFinishesOnceAndCancelsWatchdog() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val records = mutableListOf<RunRecord>()
        val engine = WebViewEngine(app, BenchSite.profile, object : WebViewEngine.Callbacks {
            override fun onEvent(message: String) = Unit
            override fun onRunFinished(record: RunRecord) { records += record }
        })
        engine.runAutomation("2026-11-12", ReportDraft("A", "B", "C"), 1)
        val main = Shadows.shadowOf(android.os.Looper.getMainLooper())
        main.idle()
        val state = WebViewEngine::class.java.getDeclaredField("active").apply { isAccessible = true }.get(engine)
        val scope = state.javaClass.getDeclaredMethod("getScope").apply { isAccessible = true }.invoke(state) as CoroutineScope
        scope.launch { throw IllegalStateException("fixture") }
        main.idle()
        assertEquals(listOf(EngineOutcome.RETRYABLE_ERROR), records.map { it.outcome })
        main.idleFor(Duration.ofSeconds(95))
        assertEquals(1, records.size)
    }

    @Test fun emergencyRetryLadderStepsAndStopsAtMaxAttempts() {
        val date = "2026-11-12"
        val now = java.time.ZonedDateTime.parse("2026-11-12T22:30:00+07:00[Asia/Jakarta]")
        fun rec(trigger: String) = RunRecord(date, "2026-11-12T22:30:00+07:00", EngineOutcome.RETRYABLE_ERROR, 1, trigger = trigger)
        val first = nextEmergencyRetryTime(listOf(rec("EMERGENCY")), date, now)
        assertEquals(now.plusMinutes(5).toInstant(), first?.toInstant())
        val second = nextEmergencyRetryTime(listOf(rec("EMERGENCY"), rec("EMERGENCY"), rec("MANUAL")), date, now)
        assertEquals(now.plusMinutes(20).toInstant(), second?.toInstant())
        val capped = nextEmergencyRetryTime(listOf(rec("EMERGENCY"), rec("EMERGENCY"), rec("EMERGENCY")), date, now)
        assertNull(capped)
        val late = java.time.ZonedDateTime.parse("2026-11-12T23:57:00+07:00[Asia/Jakarta]")
        val clamped = nextEmergencyRetryTime(listOf(rec("EMERGENCY")), date, late)
        assertEquals(java.time.ZonedDateTime.parse("2026-11-12T23:58:59+07:00[Asia/Jakarta]").toInstant(), clamped?.toInstant())
    }

    @Test fun cancelBeforeMainStartPreventsRunExecution() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val records = mutableListOf<RunRecord>()
        val engine = WebViewEngine(app, BenchSite.profile, object : WebViewEngine.Callbacks {
            override fun onEvent(message: String) = Unit
            override fun onRunFinished(record: RunRecord) { records += record }
        })
        engine.runAutomation("2026-11-12", ReportDraft("A", "B", "C"), 1)
        engine.cancel()
        val main = Shadows.shadowOf(android.os.Looper.getMainLooper())
        main.idleFor(Duration.ofSeconds(95))
        assertTrue(records.isEmpty())
        val state = WebViewEngine::class.java.getDeclaredField("active").apply { isAccessible = true }.get(engine)
        assertNull(state)
    }

    @Test fun waitTextareasClassifiesHttpOrLoadErrorsAsRetryable() {
        val httpError = WebViewEngine.classifyTextareaTimeout(
            httpStatusCode = 503,
            mainFrameError = null,
            finalUrl = "https://monev.maganghub.kemnaker.go.id/dashboard/riwayat?date=2026-11-12&view=edit",
            dateISO = "2026-11-12",
            profile = BenchSite.profile,
        )
        assertEquals(EngineOutcome.RETRYABLE_ERROR, httpError.first)

        val loadError = WebViewEngine.classifyTextareaTimeout(
            httpStatusCode = 200,
            mainFrameError = "gagal memuat (kesalahan -2)",
            finalUrl = "https://monev.maganghub.kemnaker.go.id/dashboard/riwayat?date=2026-11-12&view=edit",
            dateISO = "2026-11-12",
            profile = BenchSite.profile,
        )
        assertEquals(EngineOutcome.RETRYABLE_ERROR, loadError.first)

        val loginRedirect = WebViewEngine.classifyTextareaTimeout(
            httpStatusCode = 200,
            mainFrameError = null,
            finalUrl = "https://account.kemnaker.go.id/auth/login",
            dateISO = "2026-11-12",
            profile = BenchSite.profile,
        )
        assertEquals(EngineOutcome.LOGIN_REQUIRED, loginRedirect.first)

        val missingForm = WebViewEngine.classifyTextareaTimeout(
            httpStatusCode = 200,
            mainFrameError = null,
            finalUrl = "https://monev.maganghub.kemnaker.go.id/dashboard/riwayat?date=2026-11-12&view=edit",
            dateISO = "2026-11-12",
            profile = BenchSite.profile,
        )
        assertEquals(EngineOutcome.DAY_NOT_FILLABLE, missingForm.first)
    }
}

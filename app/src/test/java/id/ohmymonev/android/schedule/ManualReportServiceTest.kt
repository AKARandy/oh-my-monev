package id.ohmymonev.android.schedule

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.StoredDraft
import id.ohmymonev.android.engine.SiteStatus
import id.ohmymonev.android.engine.manualPreflightIssue
import id.ohmymonev.core.enginecore.TodayState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManualReportServiceTest {
    @Test fun manualSendUsesSavedRequestAndExcludesScheduledRun() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val draft = StoredDraft("2026-11-09", "Aktivitas", "Pelajaran", "Kendala", "participant")
        AppStore(app).saveTodayDraft(draft)
        val shadow = Shadows.shadowOf(app)
        assertNull(shadow.nextStartedService)
        val request = ManualReportRequest(draft, "participant", 7)
        val calls = mutableListOf<ManualReportRequest>()
        var scheduled = 0
        val previous = AutoFillDelegateProvider.delegate
        val controller = Robolectric.buildService(AutoFillService::class.java).create()
        try {
            AutoFillDelegateProvider.delegate = object : AutoFillDelegate {
                override fun startAutoFill(context: Context) { scheduled++ }
                override fun startManual(request: ManualReportRequest) { calls += request }
            }
            AutoFillService.startManual(app, request)
            AutoFillService.startManual(app, request)
            val intent = requireNotNull(shadow.nextStartedService)
            assertNull(shadow.nextStartedService)
            controller.get().onStartCommand(intent, 0, 1)
            controller.get().onStartCommand(Intent(app, AutoFillService::class.java), 0, 2)
            controller.get().onStartCommand(intent, 0, 3)
            assertEquals(listOf(request), calls)
            assertEquals(0, scheduled)
        } finally {
            controller.destroy()
            AutoFillDelegateProvider.delegate = previous
        }
    }

    @Test fun manualPreflightRequiresFreshMatchingUnfilledStatus() {
        val request = ManualReportRequest(
            StoredDraft("2026-11-09", "Aktivitas", "Pelajaran", "Kendala", "participant"), "participant", 7,
        )
        val ready = SiteStatus(true, TodayState.UNFILLED, null, emptyList(),
            account = request.account, credentialVersion = request.sessionVersion)
        fun issue(status: SiteStatus?, current: Boolean = true, version: Long = 7) =
            manualPreflightIssue(status, request, current, version)
        assertNull(issue(ready))
        assertNotNull(issue(null))
        assertNotNull(issue(ready.copy(today = TodayState.UNKNOWN)))
        assertNotNull(issue(ready.copy(sessionActive = false)))
        assertNotNull(issue(ready.copy(today = TodayState.FILLED)))
        assertNotNull(issue(ready.copy(today = TodayState.LIBUR)))
        assertNotNull(issue(ready.copy(account = "other")))
        assertNotNull(issue(ready, current = false))
        assertNotNull(issue(ready, version = 8))
    }


    @Test fun stalledWebViewFinishesOnce() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val outcomes = mutableListOf<id.ohmymonev.core.enginecore.EngineOutcome>()
        val engine = id.ohmymonev.android.engine.WebViewEngine(
            app, id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE,
            object : id.ohmymonev.android.engine.WebViewEngine.Callbacks {
                override fun onEvent(message: String) = Unit
                override fun onRunFinished(record: id.ohmymonev.core.enginecore.RunRecord) {
                    outcomes += record.outcome
                }
            },
        )
        engine.runAutomation("2026-11-09", id.ohmymonev.core.model.ReportDraft("A", "B", "C"), attempt = 1)
        val main = Shadows.shadowOf(android.os.Looper.getMainLooper())
        main.idle()
        assertTrue(outcomes.isEmpty())
        main.idleFor(java.time.Duration.ofSeconds(89))
        assertTrue(outcomes.isEmpty())
        main.idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(1, outcomes.size)
        main.idleFor(java.time.Duration.ofSeconds(5))
        assertEquals(listOf(id.ohmymonev.core.enginecore.EngineOutcome.LOADING_TIMEOUT), outcomes)
    }

}

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
}

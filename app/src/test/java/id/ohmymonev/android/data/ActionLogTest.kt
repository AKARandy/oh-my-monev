package id.ohmymonev.android.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActionLogTest {
    @Test fun exportContainsOrderedPersistedActionsWithoutReportContent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ActionLog.record(app, "test.first", "location=false")
        ActionLog.record(app, "test.second", "outcome=LOCATION_CHECK_FAILS")
        val snapshot = ActionLog.snapshot(app).lines().filter { it.isNotBlank() }.map(::JSONObject)
        assertEquals("oh-my-monev-diagnostics", snapshot.first().getString("type"))
        assertEquals(listOf("test.first", "test.second"), snapshot.drop(1).map { it.getString("event") })
        assertEquals("location=false", snapshot[1].getString("detail"))
        assertTrue(File(app.filesDir, "diagnostic-actions.jsonl").isFile)
        assertEquals(snapshot.drop(1).map { it.toString() },
            ActionLog.snapshot(app).lines().filter { it.isNotBlank() }.drop(1).map { JSONObject(it).toString() })
    }
}

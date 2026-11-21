package id.ohmymonev.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.ModelTransferPhase
import id.ohmymonev.android.data.StoredDraft
import id.ohmymonev.android.engine.SiteStatus
import id.ohmymonev.android.engine.t5.T5Tokenizer
import id.ohmymonev.android.schedule.ManualReportRequest
import id.ohmymonev.android.schedule.ManualReportState
import id.ohmymonev.android.ui.canSendManualReport
import id.ohmymonev.core.enginecore.EngineOutcome
import id.ohmymonev.core.enginecore.RunRecord
import id.ohmymonev.core.enginecore.TodayState
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HardeningStateAndPerfTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()

    private fun isolatedContext(): ContextWrapper {
        val dir = File(base.cacheDir, "hardening-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$name-${dir.name}", mode)
        }
    }

    @Test
    fun appStoreStreamsRecentAndRotatesOvergrownRecordsFile() {
        val ctx = isolatedContext()
        val store = AppStore(ctx)
        assertNull(store.lastRecord())

        // Write enough large records (~800 bytes each) to cross the 512 KB compaction threshold (~700 records).
        val longDetail = "x".repeat(750)
        for (i in 1..720) {
            store.appendRecord(
                RunRecord(
                    date = "2026-11-21",
                    attemptedAtISO = "2026-11-21T20:00:00+07:00",
                    outcome = EngineOutcome.SUBMITTED,
                    attempt = i,
                    detail = longDetail,
                ),
            )
        }
        val last = store.lastRecord()
        assertEquals(720, last?.attempt)
        val recent = store.recentRecords(5)
        assertEquals(listOf(716, 717, 718, 719, 720), recent.map { it.attempt })
        val all = store.records()
        val recordsFile = File(ctx.filesDir, "records.jsonl")
        assertTrue("Expected compacted record count in 500..600, got ${all.size}", all.size in 500 until 720)
        assertTrue("Expected compacted file size <= 512KB, got ${recordsFile.length()}", recordsFile.length() <= 512_000L)
        assertEquals(720, all.last().attempt)
    }

    @Test
    fun t5TokenizerRoundTripsMultiByteUtf8FallbackWithPrimitiveBacktracking() {
        val vocabJson = buildString {
            append("""{"added_tokens":[{"id":0,"content":"<pad>"},{"id":1,"content":"</s>"}],"model":{"vocab":[""")
            append("""["<pad>",0.0],["</s>",0.0],["▁",-1.0],["A",-2.0],["B",-2.0]""")
            // Include full byte table <0x00>..<0xFF>
            for (b in 0..255) {
                val hex = b.toString(16).uppercase().padStart(2, '0')
                append(""",["<0x$hex>",-10.0]""")
            }
            append("""]}}""")
        }
        val tokenizer = T5Tokenizer.fromJson(vocabJson)
        val input = "A • B 🙂"
        val ids = tokenizer.encode(input)
        assertEquals(1, ids.last())
        assertEquals(input, tokenizer.decode(ids))
    }

    @Test
    fun modelTransferPhaseEnforcesExclusiveFlags() {
        val idle: ModelTransferPhase = ModelTransferPhase.Idle
        assertFalse(idle.busy)
        assertNull(idle.progress)
        assertNull(idle.error)
        assertNull(idle.deleteError)

        val downloading = ModelTransferPhase.Downloading(42)
        assertTrue(downloading.busy)
        assertEquals(42, downloading.progress)
        assertNull(downloading.error)
        assertNull(downloading.deleteError)

        val deleting = ModelTransferPhase.Deleting
        assertTrue(deleting.busy)
        assertNull(deleting.progress)
        assertNull(deleting.error)

        val dlFail = ModelTransferPhase.Failed("network", isDelete = false)
        assertFalse(dlFail.busy)
        assertEquals("network", dlFail.error)
        assertNull(dlFail.deleteError)

        val delFail = ModelTransferPhase.Failed("locked", isDelete = true)
        assertFalse(delFail.busy)
        assertNull(delFail.error)
        assertEquals("locked", delFail.deleteError)
    }

    @Test
    fun canSendManualReportCoversRetryAndAccountGuards() {
        val draft1 = StoredDraft("2026-11-21", "a", "b", "c", "acc1")
        val draft2 = StoredDraft("2026-11-21", "updated", "b", "c", "acc1")
        val status = SiteStatus(
            sessionActive = true,
            today = TodayState.UNFILLED,
            deadline = null,
            days = emptyList(),
            account = "acc1",
            credentialVersion = 3L,
        )
        val reqAcc1 = ManualReportRequest(draft1, "acc1", 3L)
        val reqAcc2 = ManualReportRequest(draft1, "acc2", 3L)

        // Fresh state -> allowed
        assertTrue(canSendManualReport(true, status, 3L, false, false, draft1, null, null, null, null))
        // Session version mismatch -> blocked
        assertFalse(canSendManualReport(true, status, 4L, false, false, draft1, null, null, null, null))
        // Another account already sent identical draft -> blocked
        assertFalse(canSendManualReport(true, status, 3L, false, false, draft1, reqAcc2, null, null, null))
        // Completed SUBMITTED -> blocked
        val submitted = ManualReportState.Completed(reqAcc1, "ok", EngineOutcome.SUBMITTED)
        assertFalse(canSendManualReport(true, status, 3L, false, false, draft1, reqAcc1, submitted, null, null))
        // LOCATION_CHECK_FAILS after site status check completed -> allowed
        val locFail = ManualReportState.Completed(reqAcc1, "loc", EngineOutcome.LOCATION_CHECK_FAILS, recheckRequired = false)
        assertTrue(canSendManualReport(true, status, 3L, false, false, draft1, reqAcc1, locFail, null, reqAcc1))
        // Modified draft without recheck required -> allowed
        val retryErr = ManualReportState.Completed(reqAcc1, "err", EngineOutcome.RETRYABLE_ERROR, recheckRequired = false)
        assertTrue(canSendManualReport(true, status, 3L, false, false, draft2, reqAcc1, retryErr, null, null))
        // Recheck required -> blocked until manual recheck completes on same request
        val needRecheck = ManualReportState.Completed(reqAcc1, "err", EngineOutcome.RETRYABLE_ERROR, recheckRequired = true)
        assertFalse(canSendManualReport(true, status, 3L, false, false, draft2, reqAcc1, needRecheck, null, null))
        assertTrue(canSendManualReport(true, status, 3L, false, false, draft1, reqAcc1, needRecheck, reqAcc1, reqAcc1))
    }
}

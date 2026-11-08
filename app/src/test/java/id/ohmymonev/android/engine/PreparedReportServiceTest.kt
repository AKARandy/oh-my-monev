package id.ohmymonev.android.engine

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import id.ohmymonev.android.data.*
import id.ohmymonev.android.engine.t5.NeuralParaphraser
import id.ohmymonev.android.schedule.TimeSettingsStore
import id.ohmymonev.core.generator.GenerationOutcome
import id.ohmymonev.core.model.GenerationTier
import id.ohmymonev.core.schedule.ScheduleSettings
import java.io.File
import java.time.LocalDate
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PreparedReportServiceTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(base.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir() = directory
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$name-${directory.name}", mode)
    }
    private val store = AppStore(context)
    private var date = LocalDate.parse("2026-11-09")
    private var participant = "participant-a"
    private var logCalls = 0
    private var unavailable = false
    private var afterLogs: (suspend () -> Unit)? = null
    private var paraphraseCalls = 0
    private var useFakeParaphraser = true
    private val fakeParaphraser = object : NeuralParaphraser {
        override suspend fun paraphrase(sentences: List<String>): List<String> =
            sentences.map { sentence -> paraphraseCalls++.let { "Melanjutkan $sentence" } }
        override fun close() {}
    }
    private var activity = "- Memeriksa data penerbangan 123 dengan hasil pemeriksaan awal.\n- Menyusun laporan hasil pemeriksaan untuk tim pengolah data.\n- Menguji hasil pemeriksaan data sebelum pekerjaan dilanjutkan."
    private var lesson = "Memahami urutan pemeriksaan kelengkapan data dan cara mencatat hasil pemeriksaan agar setiap perubahan dapat ditelusuri kembali."
    private val obstacle = "Sebagian data masih memerlukan pemeriksaan lanjutan karena rincian sumber belum lengkap dan perlu dibandingkan dengan dokumen pendukung."

    /** Jalur lanjutan otomatis hanya berlaku di mode AUTOMATIC; mode bawaan adalah templat. */
    @Before fun automaticMode() = runBlocking {
        TimeSettingsStore(context).save(ScheduleSettings(escalationSource = ScheduleSettings.ContentSource.AUTOMATIC))
    }

    private fun sourceDate(): String = (1L..7L).map { date.minusDays(it) }
        .first { it.dayOfWeek !in setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY) }.toString()

    private suspend fun session(): SessionRepository {
        val vault = SessionTokenStore(context) { SecretKeySpec(ByteArray(16) { 1 }, "AES") }
        vault.save("synthetic-token")
        return SessionRepository(context, vault).also { it.credentials() }
    }
    private fun service(session: SessionRepository) = PreparedReportService(context, {
        MonevApiClient(context, session) { _, path, _ ->
            when {
                unavailable -> ApiResponse(503, "{}")
                path == "/users/me" -> ApiResponse(200, JSONObject().put("data", JSONObject()
                    .put("id", participant).put("internship_start_date", "2026-11-01").put("off_days", JSONArray().put("SATURDAY").put("SUNDAY"))).toString())
                path.startsWith("/attendances?") -> ApiResponse(200, JSONObject().put("data", JSONArray().put(JSONObject()
                    .put("date", sourceDate()).put("status", "PRESENT").put("approval_status", "SUBMITTED"))).put("page", 1).put("page_size", 100).toString())
                path.startsWith("/daily-logs?") -> {
                    logCalls++
                    afterLogs?.invoke()
                    val source = sourceDate()
                    ApiResponse(200, JSONObject().put("data", JSONArray().put(JSONObject()
                        .put("date", source.toString()).put("state", "SUBMITTED").put("activity_log", activity)
                        .put("lesson_learned", lesson).put("obstacles", obstacle))).put("page", 1).put("page_size", 100).toString())
                }
                else -> error("Unexpected API path: $path")
            }
        }
    }, { date }, {
        if (useFakeParaphraser) fakeParaphraser else null
    })

    @Test fun workerReusesPersistedPreviewTextUntilExplicitRefresh() = runBlocking {
        val session = session()
        val preview = service(session).prepare()
        assertTrue(preview.outcome() is GenerationOutcome.Generated)
        assertEquals("2026-11-06", preview.sourceDate)
        lesson = lesson.replace("Memahami", "Mempelajari")
        val worker = service(session).prepare()
        assertEquals(preview.draft, worker.draft)
        assertEquals(1, logCalls)
        val refreshed = service(session).prepare(force = true)
        assertNotEquals(preview.draft?.pelajaran, refreshed.draft?.pelajaran)
        assertEquals(lesson, refreshed.draft?.pelajaran)
        assertEquals(obstacle, refreshed.draft?.kendala)
    }
    @Test fun authoredTodayHasPriorityAndIsNotRewritten() = runBlocking {
        val session = session()
        val authored = StoredDraft(date.toString(), activity, lesson, obstacle)
        store.saveTodayDraft(authored)
        val result = service(session).prepare()
        assertTrue(result.outcome() is GenerationOutcome.Generated)
        assertEquals(activity, result.draft?.aktivitas)
        assertEquals(0, logCalls)
        assertEquals(participant, store.todayDraft(date.toString())?.participantId)
    }
    @Test fun changedAuthoredTextReplacesThePreparedDraft() = runBlocking {
        val session = session()
        store.saveTodayDraft(StoredDraft(date.toString(), activity, lesson, obstacle))
        service(session).prepare()
        val changed = activity.replace("123", "456")
        store.saveTodayDraft(StoredDraft(date.toString(), changed, lesson, obstacle))
        assertEquals(changed, service(session).prepare().draft?.aktivitas)
    }
    @Test fun participantAndDateChangesNeverReuseAnOldDraft() = runBlocking {
        val session = session()
        store.saveTodayDraft(StoredDraft(date.toString(), activity, lesson, obstacle))
        service(session).prepare()
        participant = "participant-b"
        val changed = service(session).prepare()
        assertEquals(participant, changed.participantId)
        assertNull(changed.written)
        assertTrue(changed.draft!!.aktivitas.startsWith("- Melanjutkan"))
        date = date.plusDays(1)
        val tomorrow = service(session).prepare()
        assertEquals(date.toString(), tomorrow.date)
        assertEquals(date.minusDays(1).toString(), tomorrow.sourceDate)
        assertEquals(2, logCalls)
    }
    @Test fun shortMaterialFailsValidationWithoutInventingText() = runBlocking {
        val session = session()
        lesson = "Terlalu pendek."
        val result = service(session).prepare()
        assertTrue(result.outcome() is GenerationOutcome.Failed)
        assertEquals(lesson, result.draft?.pelajaran)
        assertTrue(result.issues.any { it.contains("100") })
        activity = "- Memeriksa data penerbangan."
        val thin = service(session).prepare(force = true)
        assertTrue(thin.outcome() is GenerationOutcome.Failed)
        assertTrue(thin.issues.any { it.contains("3 sampai 5") })
    }
    @Test fun failedRefreshDoesNotRestoreAnOlderPreparedDraft() = runBlocking {
        val session = session()
        service(session).prepare()
        unavailable = true
        assertTrue(service(session).prepare(force = true).outcome() is GenerationOutcome.Failed)
        unavailable = false
        lesson = lesson.replace("Memahami", "Mempelajari")
        assertEquals(lesson, service(session).prepare().draft?.pelajaran)
        assertEquals(2, logCalls)
    }
    @Test fun offDayCanPrepareAReferenceWithoutWritingToTheSite() = runBlocking {
        val session = session()
        date = LocalDate.parse("2026-11-08")
        val result = service(session).prepare()
        assertTrue(result.outcome() is GenerationOutcome.Generated)
        assertEquals("2026-11-06", result.sourceDate)
        // The fake transport above accepts only read requests; no form or submission runs.
    }
    @Test fun changedLoginDuringReadCannotSaveOrUseTheOldParticipantsReport() = runBlocking {
        val session = session()
        afterLogs = { session.capture("replacement-token", session.state.value.version) }
        assertTrue(service(session).prepare().outcome() is GenerationOutcome.Failed)
        assertNull(context.getSharedPreferences("prepared_report", Context.MODE_PRIVATE).getString("draft", null))
    }

    @Test fun unknownVerbsAreParaphrasedByTheModelAndTheSourceStaysCached() = runBlocking {
        val session = session()
        activity = activity.replace("Memeriksa", "Membikin")
        val result = service(session).prepare()
        val outcome = result.outcome()
        assertTrue(outcome is GenerationOutcome.Generated)
        assertEquals(GenerationTier.NEURAL, (outcome as GenerationOutcome.Generated).tier)
        assertEquals(activity, result.sourceReport?.aktivitas)
        assertEquals(result.sourceReport, service(session).prepare().sourceReport)
        assertEquals(1, logCalls)
        assertEquals(1, paraphraseCalls)
    }
    @Test fun oldPreparedCacheWithoutSourceMetadataIsRebuilt() = runBlocking {
        val session = session()
        context.getSharedPreferences("prepared_report", Context.MODE_PRIVATE).edit().putString("draft",
            """{"date":"2026-11-09","participantId":"participant-a","sourceDate":"2026-11-06","source":"legacy","draft":null,"issues":[]}""").commit()
        assertNotNull(service(session).prepare().sourceReport)
        assertEquals(1, logCalls)
    }

    @Test fun neuralModeParaphrasesUnknownVerbsAndMarksTheDayDone() = runBlocking {
        val session = session()
        activity = "Mengikuti apel akhir minggu dan menandati daftar hadir peserta magang dengan lengkap. " +
            "Menjadwalkan pengolahan otomatis pada Windows agar pemeriksaan bulanan berjalan sesuai jadwal. " +
            "Memperbarui panduan operasi agar sesuai kondisi terbaru."
        val result = service(session).prepare()
        val outcome = result.outcome()
        assertTrue(outcome is GenerationOutcome.Generated)
        assertEquals(GenerationTier.NEURAL, (outcome as GenerationOutcome.Generated).tier)
        assertTrue(result.draft!!.aktivitas.startsWith("- Melanjutkan Mengikuti"))
        assertEquals(3, paraphraseCalls)
        // Perbarui lagi di hari yang sama tidak menjalankan model kedua kali.
        val second = service(session).prepare(force = true)
        assertEquals(result.draft, second.draft)
        assertEquals(3, paraphraseCalls)
    }
    @Test fun previewWithoutModelRunReturnsSentinelAndNeverTouchesTheModel() = runBlocking {
        val session = session()
        val sentinel = service(session).prepare(allowModelRun = false)
        assertNull(sentinel.draft)
        assertTrue(sentinel.issues.single().contains("Pratinjau otomatis belum dibuat hari ini"))
        // Sentinel tidak ter-cache: panggilan penuh berikutnya benar-benar membuat draf.
        val full = service(session).prepare()
        assertTrue(full.outcome() is GenerationOutcome.Generated)
    }
    @Test fun automaticModeWithoutTheModelFailsHonestly() = runBlocking {
        val session = session()
        useFakeParaphraser = false
        val result = service(session).prepare()
        assertTrue(result.outcome() is GenerationOutcome.Failed)
        assertTrue(result.issues.single().contains("Model otomatis belum diunduh"))
        assertEquals(0, logCalls)
    }

    @Test fun templateModeUsesTheStoredTemplateWithoutReadingHistory() = runBlocking {
        val session = session()
        TimeSettingsStore(context).save(ScheduleSettings(escalationSource = ScheduleSettings.ContentSource.TEMPLATE))
        store.saveTemplate(ReportTemplate(activity, lesson, obstacle))
        val result = service(session).prepare()
        assertTrue(result.outcome() is GenerationOutcome.Generated)
        assertEquals(activity, result.draft?.aktivitas)
        assertEquals(lesson, result.draft?.pelajaran)
        assertEquals("Dari templat Anda.", result.source)
        assertEquals(0, logCalls)
    }
    @Test fun templatePlaceholderTanggalIsSubstituted() = runBlocking {
        val session = session()
        TimeSettingsStore(context).save(ScheduleSettings(escalationSource = ScheduleSettings.ContentSource.TEMPLATE))
        val templated = "- Melanjutkan pekerjaan tanggal {tanggal} dengan pemeriksaan data terjadwal.\n" +
            "- Menyusun catatan hasil pemeriksaan untuk tim pengolah data yang membutuhkan.\n" +
            "- Menguji hasil pemeriksaan data sebelum pekerjaan dilanjutkan ke tahap berikutnya."
        store.saveTemplate(ReportTemplate(templated, lesson, obstacle))
        val result = service(session).prepare()
        assertTrue(result.outcome() is GenerationOutcome.Generated)
        assertEquals(false, result.draft?.aktivitas?.contains("{tanggal}"))
        assertTrue(result.draft?.aktivitas?.contains("9 November 2026") == true)
    }
    @Test fun templateModeWithoutTemplateFailsWithoutFallback() = runBlocking {
        val session = session()
        TimeSettingsStore(context).save(ScheduleSettings(escalationSource = ScheduleSettings.ContentSource.TEMPLATE))
        val result = service(session).prepare()
        assertTrue(result.outcome() is GenerationOutcome.Failed)
        assertTrue(result.issues.single().contains("Templat"))
        assertEquals(0, logCalls)
    }
    @Test fun changingTemplateInvalidatesThePreparedCache() = runBlocking {
        val session = session()
        TimeSettingsStore(context).save(ScheduleSettings(escalationSource = ScheduleSettings.ContentSource.TEMPLATE))
        store.saveTemplate(ReportTemplate(activity, lesson, obstacle))
        val first = service(session).prepare()
        assertEquals(0, logCalls)
        store.saveTemplate(ReportTemplate(activity, lesson.replace("Memahami", "Mempelajari"), obstacle))
        val second = service(session).prepare()
        assertNotEquals(first.draft?.pelajaran, second.draft?.pelajaran)
    }
}

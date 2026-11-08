package id.ohmymonev.android.engine

import android.content.Context
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.StoredDraft
import id.ohmymonev.core.generator.DatedReport
import id.ohmymonev.core.generator.FailureReason
import id.ohmymonev.core.generator.GenerationOutcome
import id.ohmymonev.core.generator.GeneratorPipeline
import id.ohmymonev.core.generator.ReportHistory
import id.ohmymonev.core.model.GenerationTier
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.schedule.ScheduleCalculator
import id.ohmymonev.core.schedule.ScheduleSettings
import id.ohmymonev.core.validator.DeliveryGate
import id.ohmymonev.android.schedule.TimeSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate

@Serializable
internal data class PreparedReport(
    val date: String,
    val participantId: String,
    val sourceDate: String,
    val source: String,
    val draft: StoredDraft?,
    val issues: List<String>,
    val written: StoredDraft? = null,
    val sourceReport: SourceReport? = null,
    val sourceReadComplete: Boolean = false,
    val sourceUnavailable: Boolean = false,
    /** Mode + isi templat saat draf disiapkan; berubah berarti pratinjau wajib dihitung ulang. */
    val templateKey: String? = null,
    @kotlinx.serialization.Transient val sessionVersion: Long? = null,
) {
    fun outcome(): GenerationOutcome {
        val text = draft
        if (text == null || issues.isNotEmpty()) return GenerationOutcome.Failed(
            FailureReason.INVALID_REPORT, issues.joinToString("\n"),
        )
        return GenerationOutcome.Generated(text.report(),
            if (written != null) GenerationTier.TEMPLATE else GenerationTier.CONTINUATION_RULES, listOf(source))
    }
}

/** Both preview and worker read this persisted, participant-scoped draft. */
internal class PreparedReportService(
    private val context: Context,
    private val apiFactory: () -> MonevApiClient = { MonevApiClient(context) },
    private val today: () -> LocalDate = { LocalDate.now(ScheduleCalculator.asiaJakarta) },
) {
    private val store = AppStore(context)
    private val preferences = context.getSharedPreferences("prepared_report", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(force: Boolean = false): PreparedReport = mutex.withLock {
        try { prepareLocked(force) }
        catch (_: IOException) { failed(today(), "Draf tidak dapat disimpan. Coba Perbarui kembali.") }
    }

    private suspend fun prepareLocked(force: Boolean): PreparedReport {
        val date = today()
        if (force) withContext(Dispatchers.IO) { save(null) }
        val api = apiFactory()
        val profile = api.objectData("/users/me") ?: return failed(date, api.message)
        val id = profile.optString("id").takeUnless { profile.isNull("id") }.orEmpty()
        if (id.isBlank()) return failed(date, "Profil peserta belum dapat dibaca.")
        api.confirmActive()
        val writtenSnapshot = withContext(Dispatchers.IO) { store.todayDraft(date.toString()) }
        val written = writtenSnapshot?.takeIf { it.participantId == null || it.participantId == id }
        val fillMode = TimeSettingsStore(context).settingsOnce().escalationSource
        val template = if (fillMode == ScheduleSettings.ContentSource.TEMPLATE) withContext(Dispatchers.IO) { store.template() } else null
        val templateKey = when (fillMode) {
            ScheduleSettings.ContentSource.TEMPLATE ->
                "TEMPLATE:" + (template?.let { "${it.aktivitas}\u0000${it.pelajaran}\u0000${it.kendala}".hashCode() } ?: "none")
            ScheduleSettings.ContentSource.AUTOMATIC -> "AUTOMATIC"
        }
        val cached = withContext(Dispatchers.IO) { runCatching {
            preferences.getString("draft", null)?.let { json.decodeFromString<PreparedReport>(it) }
        }.getOrNull() }
        if (!force && today() == date && cached?.date == date.toString() && cached.participantId == id && cached.written == written && cached.templateKey == templateKey && cached.sourceReadComplete && api.isCurrent()) {
            return cached.copy(sessionVersion = api.credentialVersion)
        }
        // A failed refresh must not leave an older preview available to the worker.
        withContext(Dispatchers.IO) { save(null) }
        val sourceDate: LocalDate
        val source: String
        val outcome: GenerationOutcome
        var material: SourceReport? = null
        var sourceUnavailable = false
        if (written != null) {
            sourceDate = date
            source = "Dari tulisan Anda untuk hari ini."
            outcome = GenerationOutcome.Generated(written.report(), GenerationTier.TEMPLATE, emptyList())
        } else if (fillMode == ScheduleSettings.ContentSource.TEMPLATE) {
            // Mode templat: tanpa fallback. Templat kosong gagal jelas, bukan melompat ke lanjutan otomatis.
            sourceDate = date
            source = if (template == null) "Templat belum dibuat." else "Dari templat Anda."
            outcome = if (template == null) GenerationOutcome.Failed(FailureReason.NO_SOURCE_MATERIAL, "Templat belum dibuat. Buka tab Jadwal, kartu Templat laporan.")
            else GeneratorPipeline(
                templates = { ReportDraft(template.aktivitas, template.pelajaran, template.kendala) },
                history = ReportHistory { null },
            ).generate(date)
        } else {
            val previous = PastReportReader(context).read(date, api, profile)
            material = previous.material
            sourceDate = material?.date?.let(LocalDate::parse) ?: date
            source = previous.message
            sourceUnavailable = previous.unavailable
            outcome = material?.let { report ->
                GeneratorPipeline(history = ReportHistory { DatedReport(LocalDate.parse(report.date), report.report()) }).generate(date)
            } ?: GenerationOutcome.Failed(FailureReason.NO_SOURCE_MATERIAL, previous.message)
        }
        if (today() != date || !api.isCurrent() || withContext(Dispatchers.IO) { store.todayDraft(date.toString()) } != writtenSnapshot) return failed(today(), "Tanggal atau peserta berubah. Perbarui pratinjau.")
        val draft = (outcome as? GenerationOutcome.Generated)?.draft
        val issues = if (outcome is GenerationOutcome.Failed) listOf(outcome.detail) else validationIssues(requireNotNull(draft))
        val prepared = PreparedReport(date.toString(), id, sourceDate.toString(), source,
            draft?.let { StoredDraft(date.toString(), it.aktivitas, it.pelajaran, it.kendala) }, issues, written, material, !sourceUnavailable, sourceUnavailable, templateKey)
        // Transient API failures are retryable; only material-backed results are cached.
        if (!sourceUnavailable) withContext(Dispatchers.IO) {
            val owned = written?.copy(participantId = id)
            if (owned != null) store.saveTodayDraft(owned)
            save(prepared.copy(written = owned))
        }
        return prepared.copy(sessionVersion = api.credentialVersion)
    }

    private fun failed(date: LocalDate, message: String) = PreparedReport(date.toString(), "", "", message, null, listOf(message), sourceUnavailable = true)

    private fun save(report: PreparedReport?) {
        val edit = preferences.edit()
        if (report == null) edit.remove("draft") else edit.putString("draft", json.encodeToString(report))
        if (!edit.commit()) throw IOException("Draf tidak dapat disimpan.")
    }

    companion object {
        private val mutex = Mutex()

        internal fun validationIssues(draft: ReportDraft): List<String> = DeliveryGate().validate(draft).violations.map { violation ->
            val part = violation.message.substringBefore(':').replace("aktivitas", "Aktivitas").replace("pelajaran", "Pelajaran").replace("kendala", "Kendala")
            val instruction = when (violation.id) {
                "G-1" -> "Gunakan 100 sampai 5000 karakter."
                "G-2" -> "Gunakan kalimat tanpa kata ganti orang."
                "G-3" -> "Hapus catatan dan penanda yang belum diisi."
                "G-4" -> "Ganti tanda pisah panjang dengan tanda baca biasa."
                "G-5" -> "Tulis 3 sampai 5 butir. Awali setiap butir dengan tanda - dan spasi."
                "G-6" -> "Tulis satu paragraf tanpa daftar."
                "G-7" -> "Tulis rincian kegiatan yang nyata."
                "G-8" -> "Awali setiap butir dengan kata kerja aktif."
                else -> "Hapus informasi pribadi dan kredensial."
            }
            "$part: $instruction"
        }.distinct()
    }
}

private fun StoredDraft.report() = ReportDraft(aktivitas, pelajaran, kendala)

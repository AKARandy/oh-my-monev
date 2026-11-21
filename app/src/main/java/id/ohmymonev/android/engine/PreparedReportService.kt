package id.ohmymonev.android.engine

import android.content.Context
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.ModelStore
import id.ohmymonev.android.data.StoredDraft
import id.ohmymonev.android.engine.t5.NeuralParaphraser
import id.ohmymonev.android.engine.t5.OrtNeuralParaphraser
import id.ohmymonev.android.schedule.TimeSettingsStore
import id.ohmymonev.core.generator.DatedReport
import id.ohmymonev.core.generator.FailureReason
import id.ohmymonev.core.generator.GenerationOutcome
import id.ohmymonev.core.generator.GeneratorPipeline
import id.ohmymonev.core.generator.ReportHistory
import id.ohmymonev.core.generator.VerbMapper
import id.ohmymonev.core.model.GenerationTier
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.schedule.ScheduleCalculator
import id.ohmymonev.core.schedule.ScheduleSettings
import id.ohmymonev.core.validator.DeliveryGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
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
    /** Draf dibuat dengan bantuan model neural (tier NEURAL saat outcome() dipanggil). */
    val neural: Boolean = false,
    val reusedPreview: Boolean = false,
    @kotlinx.serialization.Transient val sessionVersion: Long? = null,
) {
    fun outcome(): GenerationOutcome {
        val text = draft
        if (text == null || issues.isNotEmpty()) return GenerationOutcome.Failed(
            FailureReason.INVALID_REPORT, issues.joinToString("\n"),
        )
        val tier = when {
            written != null || templateKey?.startsWith("TEMPLATE:") == true -> GenerationTier.TEMPLATE
            neural -> GenerationTier.NEURAL
            else -> GenerationTier.CONTINUATION_RULES
        }
        return GenerationOutcome.Generated(text.report(), tier, listOf(source))
    }
}

/** Both preview and worker read this persisted, participant-scoped draft. */
internal class PreparedReportService(
    private val context: Context,
    private val apiFactory: () -> MonevApiClient = { MonevApiClient(context) },
    private val today: () -> LocalDate = { LocalDate.now(ScheduleCalculator.asiaJakarta) },
    private val paraphraserFactory: () -> NeuralParaphraser? = {
        OrtNeuralParaphraser.createOrNull(File(context.filesDir, "model"))
    },
) {
    private val store = AppStore(context)
    private val preferences = context.getSharedPreferences("prepared_report", Context.MODE_PRIVATE)

    suspend fun prepare(force: Boolean = false, allowModelRun: Boolean = true): PreparedReport = mutex.withLock {
        try { prepareLocked(force, allowModelRun) }
        catch (_: IOException) { failed(today(), "Draf tidak dapat disimpan. Coba Perbarui kembali.") }
    }

    internal suspend fun deleteAutomaticModel(beforeDelete: suspend () -> Unit) {
        check(mutex.tryLock()) { "Tunggu pembuatan pratinjau selesai." }
        try {
            beforeDelete()
            withContext(Dispatchers.IO) {
                val cached = runCatching {
                    preferences.getString("draft", null)?.let { json.decodeFromString<PreparedReport>(it) }
                }.getOrNull()
                if (cached?.templateKey?.startsWith("AUTOMATIC:") == true) save(null)
                preferences.edit().remove(NEURAL_DONE_KEY).commit()
            }
            ModelStore(context).delete()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun prepareLocked(force: Boolean, allowModelRun: Boolean): PreparedReport {
        val date = today()
        val fillMode = TimeSettingsStore(context).settingsOnce().escalationSource
        val neuralMarker = neuralDoneDate()
        // Mode Otomatis: jika draf belum dibuat hari ini (atau mode bukan Otomatis), hapus cache lama sebelum membaca API.
        if (force && (fillMode != ScheduleSettings.ContentSource.AUTOMATIC || neuralMarker?.startsWith("$date|") != true)) {
            withContext(Dispatchers.IO) { save(null) }
        }
        val api = apiFactory()
        val profile = api.objectData("/users/me") ?: return failed(date, api.message)
        val id = profile.optString("id").takeUnless { profile.isNull("id") }.orEmpty()
        if (id.isBlank()) return failed(date, "Profil peserta belum dapat dibaca.")
        api.confirmActive()
        val writtenSnapshot = withContext(Dispatchers.IO) { store.todayDraft(date.toString()) }
        val written = writtenSnapshot?.takeIf { it.participantId == null || it.participantId == id }
        val template = if (fillMode == ScheduleSettings.ContentSource.TEMPLATE) withContext(Dispatchers.IO) { store.template() } else null
        val templateKey = when (fillMode) {
            ScheduleSettings.ContentSource.TEMPLATE ->
                "TEMPLATE:" + (template?.let { "${it.aktivitas}\u0000${it.pelajaran}\u0000${it.kendala}".hashCode() } ?: "none")
            ScheduleSettings.ContentSource.AUTOMATIC -> "AUTOMATIC:" + ModelStore.VERSION
        }
        val neuralDone = fillMode == ScheduleSettings.ContentSource.AUTOMATIC &&
            written == null &&
            neuralMarker == "$date|$id"
        val cached = withContext(Dispatchers.IO) { runCatching {
            preferences.getString("draft", null)?.let { json.decodeFromString<PreparedReport>(it) }
        }.getOrNull() }
        if (force && neuralDone && cached?.let { it.participantId == id && it.templateKey == templateKey && it.written == null } != true) {
            withContext(Dispatchers.IO) { save(null) }
        }
        if (!force && today() == date && cached?.date == date.toString() && cached.participantId == id && cached.written == written && cached.templateKey == templateKey && cached.sourceReadComplete && api.isCurrent()) {
            return cached.copy(sessionVersion = api.credentialVersion, reusedPreview = true)
        }
        // Mode Otomatis: draf hari ini sudah pernah dibuat — Perbarui tidak menjalankan model lagi.
        if (neuralDone) {
            val fresh = cached?.takeIf {
                it.date == date.toString() &&
                    it.participantId == id &&
                    it.written == written &&
                    it.templateKey == templateKey &&
                    it.draft != null &&
                    !it.sourceUnavailable &&
                    api.isCurrent()
            }
            if (fresh != null) return fresh.copy(sessionVersion = api.credentialVersion, reusedPreview = true)
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
        } else if (fillMode == ScheduleSettings.ContentSource.AUTOMATIC && !allowModelRun) {
            // Pemicu otomatis tidak boleh menjalankan model: pratinjau dibuat lewat tombol Perbarui.
            return PreparedReport(date.toString(), id, date.toString(),
                "Pratinjau otomatis belum dibuat hari ini.", null,
                listOf("Pratinjau otomatis belum dibuat hari ini. Tekan Perbarui."),
                sourceReadComplete = false)
        } else if (fillMode == ScheduleSettings.ContentSource.TEMPLATE) {
            // Mode templat: tanpa fallback. Templat kosong gagal jelas, bukan melompat ke lanjutan otomatis.
            sourceDate = date
            source = if (template == null) "Templat belum dibuat." else "Dari templat Anda."
            outcome = if (template == null) GenerationOutcome.Failed(FailureReason.NO_SOURCE_MATERIAL, "Templat belum dibuat. Buka tab Jadwal, kartu Templat laporan.")
            else GeneratorPipeline(
                templates = { ReportDraft(template.aktivitas, template.pelajaran, template.kendala) },
                history = ReportHistory { null },
            ).generate(date)
        } else if (fillMode == ScheduleSettings.ContentSource.AUTOMATIC) {
            var created: NeuralParaphraser? = null
            val paraphraser = try {
                withContext(Dispatchers.IO) { paraphraserFactory().also { created = it } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { created?.close() }
                throw e
            } catch (_: Exception) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { created?.close() }
                return failed(date, "Model otomatis rusak atau tidak dapat dimuat.")
            }
            if (paraphraser == null) {
                sourceDate = date
                source = "Model otomatis belum diunduh."
                outcome = GenerationOutcome.Failed(
                    FailureReason.NO_SOURCE_MATERIAL,
                    "Model otomatis belum diunduh. Buka tab Jadwal, Pengisian darurat.")
            } else {
                try {
                val previous = PastReportReader(context).read(date, api, profile)
                material = previous.material
                sourceDate = material?.date?.let(LocalDate::parse) ?: date
                source = previous.message
                sourceUnavailable = previous.unavailable
                outcome = material?.let { report -> neuralOutcome(report, paraphraser) }
                    ?: GenerationOutcome.Failed(FailureReason.NO_SOURCE_MATERIAL, previous.message)
                } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { paraphraser.close() } }
            }
        } else {
            return failed(date, "Mode isian tidak dikenal.")
        }
        if (today() != date || !api.isCurrent() || withContext(Dispatchers.IO) { store.todayDraft(date.toString()) } != writtenSnapshot) return failed(today(), "Tanggal atau peserta berubah. Perbarui pratinjau.")
        val draft = (outcome as? GenerationOutcome.Generated)?.draft
        val issues = if (outcome is GenerationOutcome.Failed) listOf(outcome.detail) else validationIssues(requireNotNull(draft))
        val prepared = PreparedReport(date.toString(), id, sourceDate.toString(), source,
            draft?.let { StoredDraft(date.toString(), it.aktivitas, it.pelajaran, it.kendala) }, issues, written, material, !sourceUnavailable, sourceUnavailable, templateKey,
            neural = (outcome as? GenerationOutcome.Generated)?.tier == GenerationTier.NEURAL)
        // Transient API failures are retryable; only material-backed results are cached.
        if (!sourceUnavailable) withContext(Dispatchers.IO) {
            val owned = written?.copy(participantId = id)
            if (owned != null) store.saveTodayDraft(owned)
            save(prepared.copy(written = owned))
            if (prepared.neural && issues.isEmpty()) {
                preferences.edit().putString(NEURAL_DONE_KEY, "$date|$id").commit()
            }
        }
        return prepared.copy(sessionVersion = api.credentialVersion)
    }

    private fun failed(date: LocalDate, message: String) = PreparedReport(date.toString(), "", "", message, null, listOf(message), sourceUnavailable = true)

    /** Lanjutan laporan: kalimat yang dikenali peta aturan ditangani lokal; sisanya diparafrasakan model. */
    private suspend fun neuralOutcome(
        material: SourceReport,
        paraphraser: NeuralParaphraser,
    ): GenerationOutcome {
        val parsed = parseActivityLines(material.aktivitas)
        val sentences = parsed.flatMap { it.sentences }
        if (sentences.isEmpty()) {
            return GenerationOutcome.Failed(FailureReason.INVALID_REPORT, "Aktivitas sumber kosong.")
        }
        val ruled = sentences.map { VerbMapper.continuation(it) }
        val unknownIndexes = ruled.indices.filter { ruled[it] == null }
        if (unknownIndexes.isEmpty()) {
            val formatted = formatActivities(parsed, ruled.map { requireNotNull(it) })
            return GenerationOutcome.Generated(
                ReportDraft(formatted, material.pelajaran, material.kendala),
                GenerationTier.CONTINUATION_RULES, listOf("dari laporan ${material.date}"))
        }
        return try {
            val neural = paraphraser.paraphrase(unknownIndexes.map { sentences[it] })
            val rewritten = sentences.indices.map { i ->
                (ruled[i] ?: neural[unknownIndexes.indexOf(i)])
                    .trim()
                    .replace(LEADING_MARKER, "")
                    .trim()
            }
            val formatted = formatActivities(parsed, rewritten)
            GenerationOutcome.Generated(
                ReportDraft(formatted, material.pelajaran, material.kendala),
                GenerationTier.NEURAL, listOf("dari laporan ${material.date}"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            GenerationOutcome.Failed(FailureReason.INVALID_REPORT, "Pembuatan draf otomatis gagal. Coba Perbarui kembali.")
        }
    }

    private suspend fun neuralDoneDate(): String? =
        withContext(Dispatchers.IO) { preferences.getString(NEURAL_DONE_KEY, null) }

    private fun save(report: PreparedReport?) {
        val edit = preferences.edit()
        if (report == null) edit.remove("draft") else edit.putString("draft", json.encodeToString(report))
        if (!edit.commit()) throw IOException("Draf tidak dapat disimpan.")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val mutex = Mutex()
        private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")
        private val LEADING_MARKER = Regex("^(?:[-•]\\s*|\\d+[.)]\\s*)")
        private const val NEURAL_DONE_KEY = "neural_done"

        internal data class ActivityLine(val prefix: String, val sentences: List<String>)

        internal fun parseActivityLines(text: String): List<ActivityLine> =
            text.split('\n').mapNotNull { raw ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val match = LEADING_MARKER.find(trimmed)
                val prefix = match?.value?.trim()?.let { "$it " } ?: ""
                val body = trimmed.removePrefix(match?.value.orEmpty()).trim()
                if (body.isEmpty()) return@mapNotNull null
                val sentences = body.split(SENTENCE_SPLIT)
                    .map { it.trim().replace(LEADING_MARKER, "").trim() }
                    .filter { it.isNotBlank() }
                if (sentences.isEmpty()) null else ActivityLine(prefix, sentences)
            }

        internal fun formatActivities(lines: List<ActivityLine>, rewritten: List<String>): String {
            var cursor = 0
            return lines.mapNotNull { line ->
                val count = line.sentences.size
                val joined = rewritten.subList(cursor, (cursor + count).coerceAtMost(rewritten.size))
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                cursor += count
                if (joined.isBlank()) null else "${line.prefix}$joined"
            }.joinToString("\n")
        }

        internal fun splitSentences(text: String): List<String> =
            parseActivityLines(text).flatMap { it.sentences }

        internal fun validationIssues(draft: ReportDraft): List<String> = DeliveryGate().validate(draft).violations.map { violation ->
            val part = violation.message.substringBefore(':').replace("aktivitas", "Aktivitas").replace("pelajaran", "Pelajaran").replace("kendala", "Kendala")
            "$part: Gunakan 100 sampai 5000 karakter."
        }.distinct()
    }
}

private fun StoredDraft.report() = ReportDraft(aktivitas, pelajaran, kendala)

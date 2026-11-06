package id.ohmymonev.android.data

import android.content.Context
import id.ohmymonev.core.enginecore.RunRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SeedPack(
    val pelajaran: List<String> = emptyList(),
    val kendala: List<String> = emptyList(),
    /** Optional template parts; null/blank on any part means "no template" (continuation mode). */
    val aktivitasTemplate: String? = null,
    val pelajaranTemplate: String? = null,
    val kendalaTemplate: String? = null,
)

@Serializable
data class StoredDraft(val date: String, val aktivitas: String, val pelajaran: String, val kendala: String)

/** text -> ISO date of last use; persisted so the rotating pools keep their cooldown across restarts. */
@Serializable
data class PoolState(val lastUsed: Map<String, String> = emptyMap())

/** Status sesi terakhir yang benar-benar diperiksa, dipakai semua layar. */
@Serializable
data class SessionRecord(val active: Boolean, val checkedAtISO: String)

/** Penanda "jangan ganggu malam ini": alarm otomatis dilokapi untuk tanggal tertentu. */
@Serializable
data class SkipMark(val date: String)

/**
 * JSON-file store in filesDir. Deliberate v0.1 simplification (single account, a handful of rows);
 * Room/RLE arrives in the M5 hardening. Every read is defensive: a corrupted store degrades to
 * "no material" which never fabricates, never crashes the nightly run.
 */
class AppStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadSeed(): SeedPack = runCatching {
        context.assets.open(SEED_FILE).bufferedReader().use { it.readText() }
            .let { json.decodeFromString(SeedPack.serializer(), it) }
    }.getOrElse { SeedPack() }

    fun drafts(): List<StoredDraft> = runCatching {
        historyFile().takeIf { it.exists() }
            ?.readUtf8()
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { line -> runCatching { json.decodeFromString(StoredDraft.serializer(), line.trimStart('\uFEFF')) }.getOrNull() }
            .orEmpty()
    }.getOrElse { emptyList() }

    fun appendDraft(stored: StoredDraft) {
        historyFile().appendText(json.encodeToString(stored) + "\n")
    }

    /** Draft yang ditulis pengguna untuk tanggal tertentu (satu per hari, ditimpa). */
    fun todayDraft(dateIso: String): StoredDraft? = try {
        draftFile().takeIf { it.exists() }
            ?.readUtf8()
            ?.let { json.decodeFromString(StoredDraft.serializer(), it) }
            ?.takeIf { it.date == dateIso }
    } catch (e: Exception) {
        android.util.Log.w("ohmymonev", "todayDraft parse gagal: $e")
        null
    }

    /** File bisa ditulis manusia/alat lain; BOM di awal file tidak boleh menggagalkan parse. */
    private fun File.readUtf8(): String = readText().removePrefix("\uFEFF")

    fun saveTodayDraft(stored: StoredDraft) {
        draftFile().writeText(json.encodeToString(stored))
    }

    fun sessionStatus(): SessionRecord? = runCatching {
        sessionFile().takeIf { it.exists() }
            ?.readUtf8()
            ?.let { json.decodeFromString(SessionRecord.serializer(), it) }
    }.getOrNull()

    fun saveSessionStatus(record: SessionRecord) {
        sessionFile().writeText(json.encodeToString(record))
    }

    /** Benar bila alarm otomatis untuk tanggal ini dibatalkan pengguna. */
    fun isSkipped(dateIso: String): Boolean = runCatching {
        skipFile().takeIf { it.exists() }
            ?.readUtf8()
            ?.let { json.decodeFromString(SkipMark.serializer(), it) }
            ?.date == dateIso
    }.getOrDefault(false)

    fun setTonightSkip(dateIso: String) {
        skipFile().writeText(json.encodeToString(SkipMark(dateIso)))
    }

    fun clearTonightSkip() {
        skipFile().delete()
    }

    fun clearTodayDraft() {
        draftFile().delete()
    }

    fun poolState(): PoolState = runCatching {
        poolFile().takeIf { it.exists() }
            ?.readUtf8()
            ?.let { json.decodeFromString(PoolState.serializer(), it) }
    }.getOrNull() ?: PoolState()

    fun savePoolState(state: PoolState) {
        poolFile().writeText(json.encodeToString(state))
    }

    fun appendRecord(record: RunRecord) {
        recordFile().appendText(json.encodeToString(record) + "\n")
    }

    fun records(): List<RunRecord> = runCatching {
        recordFile().takeIf { it.exists() }
            ?.readUtf8()
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { line -> runCatching { json.decodeFromString(RunRecord.serializer(), line.trimStart('\uFEFF')) }.getOrNull() }
            .orEmpty()
    }.getOrElse { emptyList() }

    private fun historyFile() = File(context.filesDir, HISTORY_FILE)
    private fun poolFile() = File(context.filesDir, POOL_FILE)
    private fun recordFile() = File(context.filesDir, RECORDS_FILE)
    private fun draftFile() = File(context.filesDir, DRAFT_FILE)
    private fun sessionFile() = File(context.filesDir, SESSION_FILE)
    private fun skipFile() = File(context.filesDir, SKIP_FILE)

    private companion object {
        const val SEED_FILE = "generator-seed.json"
        const val HISTORY_FILE = "history.jsonl"
        const val POOL_FILE = "pool-state.json"
        const val RECORDS_FILE = "records.jsonl"
        const val DRAFT_FILE = "today-draft.json"
        const val SESSION_FILE = "session.json"
        const val SKIP_FILE = "tonight-skip.json"
    }
}

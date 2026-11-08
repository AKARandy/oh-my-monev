package id.ohmymonev.android.data

import android.content.Context
import id.ohmymonev.core.enginecore.RunRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredDraft(val date: String, val aktivitas: String, val pelajaran: String, val kendala: String, val participantId: String? = null)

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
        val preferences = context.getSharedPreferences("monev_session", Context.MODE_PRIVATE)
        val encoded = if (preferences.contains("status")) preferences.getString("status", "null")!!
            else sessionFile().takeIf { it.exists() }?.readUtf8() ?: return null
        json.decodeFromString(SessionRecord.serializer().nullable, encoded)
    }.getOrNull()

    fun saveSessionStatus(record: SessionRecord) {
        val preferences = context.getSharedPreferences("monev_session", Context.MODE_PRIVATE)
        if (!preferences.edit().putString("status", json.encodeToString(record)).commit()) throw java.io.IOException("Session storage failed")
    }

    fun clearSessionStatus() {
        val preferences = context.getSharedPreferences("monev_session", Context.MODE_PRIVATE)
        if (!preferences.edit().putString("status", "null").commit()) throw java.io.IOException("Session removal failed")
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
    private fun recordFile() = File(context.filesDir, RECORDS_FILE)
    private fun draftFile() = File(context.filesDir, DRAFT_FILE)
    private fun sessionFile() = File(context.filesDir, SESSION_FILE)
    private fun skipFile() = File(context.filesDir, SKIP_FILE)

    private companion object {
        const val HISTORY_FILE = "history.jsonl"
        const val RECORDS_FILE = "records.jsonl"
        const val DRAFT_FILE = "today-draft.json"
        const val SESSION_FILE = "session.json"
        const val SKIP_FILE = "tonight-skip.json"
    }
}

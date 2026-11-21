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

/** Templat tetap buatan pengguna untuk Pengisian darurat mode templat ({tanggal} diganti saat dipakai). */
@Serializable
data class ReportTemplate(val aktivitas: String, val pelajaran: String, val kendala: String)

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

    fun drafts(): List<StoredDraft> = synchronized(fileLock) {
        runCatching {
            historyFile().takeIf { it.exists() }
                ?.readUtf8()
                ?.lines()
                ?.filter { it.isNotBlank() }
                ?.mapNotNull { line -> runCatching { json.decodeFromString(StoredDraft.serializer(), line.trimStart('\uFEFF')) }.getOrNull() }
                .orEmpty()
        }.getOrElse { emptyList() }
    }

    fun appendDraft(stored: StoredDraft) = synchronized(fileLock) {
        historyFile().appendText(json.encodeToString(stored) + "\n")
    }

    /** Draft yang ditulis pengguna untuk tanggal tertentu (satu per hari, ditimpa). */
    fun todayDraft(dateIso: String): StoredDraft? = synchronized(fileLock) {
        try {
            draftFile().takeIf { it.exists() }
                ?.readUtf8()
                ?.let { json.decodeFromString(StoredDraft.serializer(), it) }
                ?.takeIf { it.date == dateIso }
        } catch (e: Exception) {
            android.util.Log.w("ohmymonev", "todayDraft parse gagal: $e")
            null
        }
    }

    /** File bisa ditulis manusia/alat lain; BOM di awal file tidak boleh menggagalkan parse. */
    private fun File.readUtf8(): String = readText().removePrefix("\uFEFF")

    fun saveTodayDraft(stored: StoredDraft) = synchronized(fileLock) {
        writeAtomically(draftFile(), json.encodeToString(stored))
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
    fun isSkipped(dateIso: String): Boolean = synchronized(fileLock) {
        runCatching {
            skipFile().takeIf { it.exists() }
                ?.readUtf8()
                ?.let { json.decodeFromString(SkipMark.serializer(), it) }
                ?.date == dateIso
        }.getOrDefault(false)
    }

    fun setTonightSkip(dateIso: String) = synchronized(fileLock) {
        writeAtomically(skipFile(), json.encodeToString(SkipMark(dateIso)))
    }

    fun clearTonightSkip() = synchronized(fileLock) {
        skipFile().delete()
    }

    fun clearTodayDraft() = synchronized(fileLock) {
        draftFile().delete()
    }

    /** Templat tetap tanpa tanggal; tidak ada yang menimpa kecuali pengguna. */
    fun template(): ReportTemplate? = synchronized(fileLock) {
        try {
            templateFile().takeIf { it.exists() }
                ?.readUtf8()
                ?.let { json.decodeFromString(ReportTemplate.serializer(), it) }
        } catch (e: Exception) {
            android.util.Log.w("ohmymonev", "template parse gagal: $e")
            null
        }
    }

    fun saveTemplate(template: ReportTemplate) = synchronized(fileLock) {
        writeAtomically(templateFile(), json.encodeToString(template))
    }

    fun clearTemplate() = synchronized(fileLock) {
        templateFile().delete()
    }

    fun appendRecord(record: RunRecord) = synchronized(fileLock) {
        val file = recordFile()
        file.appendText(json.encodeToString(record) + "\n")
        if (file.length() > MAX_RECORDS_BYTES) {
            val keep = ArrayDeque<String>(MAX_STORED_RECORDS + 1)
            file.bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val trimmed = raw.trimStart('\uFEFF')
                    if (trimmed.isNotBlank()) {
                        if (keep.size == MAX_STORED_RECORDS) keep.removeFirst()
                        keep.addLast(trimmed)
                    }
                }
            }
            writeAtomically(file, if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n"))
        }
    }

    fun lastRecord(): RunRecord? = recentRecords(1).lastOrNull()

    fun recentRecords(limit: Int): List<RunRecord> = synchronized(fileLock) {
        if (limit <= 0) return@synchronized emptyList()
        runCatching {
            val file = recordFile().takeIf { it.exists() } ?: return@runCatching emptyList()
            val deque = ArrayDeque<RunRecord>(limit + 1)
            file.bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val trimmed = raw.trimStart('\uFEFF')
                    if (trimmed.isNotBlank()) {
                        runCatching { json.decodeFromString(RunRecord.serializer(), trimmed) }
                            .getOrNull()?.let { record ->
                                if (deque.size == limit) deque.removeFirst()
                                deque.addLast(record)
                            }
                    }
                }
            }
            deque.toList()
        }.getOrElse { emptyList() }
    }

    fun records(): List<RunRecord> = synchronized(fileLock) {
        runCatching {
            val file = recordFile().takeIf { it.exists() } ?: return@runCatching emptyList()
            val list = mutableListOf<RunRecord>()
            file.bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val trimmed = raw.trimStart('\uFEFF')
                    if (trimmed.isNotBlank()) {
                        runCatching { json.decodeFromString(RunRecord.serializer(), trimmed) }
                            .getOrNull()?.let { list.add(it) }
                    }
                }
            }
            list
        }.getOrElse { emptyList() }
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                target.writeText(text)
            }
        }
    }

    private fun historyFile() = File(context.filesDir, HISTORY_FILE)
    private fun recordFile() = File(context.filesDir, RECORDS_FILE)
    private fun draftFile() = File(context.filesDir, DRAFT_FILE)
    private fun templateFile() = File(context.filesDir, TEMPLATE_FILE)
    private fun sessionFile() = File(context.filesDir, SESSION_FILE)
    private fun skipFile() = File(context.filesDir, SKIP_FILE)

    private companion object {
        val fileLock = Any()
        val json = Json { ignoreUnknownKeys = true }
        const val MAX_STORED_RECORDS = 500
        const val MAX_RECORDS_BYTES = 512_000L
        const val HISTORY_FILE = "history.jsonl"
        const val RECORDS_FILE = "records.jsonl"
        const val DRAFT_FILE = "today-draft.json"
        const val TEMPLATE_FILE = "report-template.json"
        const val SESSION_FILE = "session.json"
        const val SKIP_FILE = "tonight-skip.json"
    }
}

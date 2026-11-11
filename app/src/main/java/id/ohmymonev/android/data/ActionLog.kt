package id.ohmymonev.android.data

import android.content.Context
import id.ohmymonev.android.BuildConfig
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

/** Ordered, app-owned action trace. Callers supply event names and safe diagnostic values only. */
internal object ActionLog {
    private val writer = Executors.newSingleThreadExecutor()
    private fun file(context: Context) = File(context.filesDir, "diagnostic-actions.jsonl")

    fun record(context: Context, event: String, detail: String = "") {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext
        val row = JSONObject().put("time", OffsetDateTime.now().toString())
            .put("event", event).put("detail", detail.take(512)).toString()
        writer.execute {
            try {
                val target = file(app)
                if (target.length() > 2_000_000) {
                    val previous = File(app.filesDir, "diagnostic-actions.previous.jsonl")
                    previous.delete()
                    target.renameTo(previous)
                }
                target.appendText(row + "\n")
            } catch (_: java.io.IOException) {
                android.util.Log.e("ActionLog", "Cannot persist diagnostic action")
            }
        }
    }

    suspend fun snapshot(context: Context): String = suspendCancellableCoroutine { continuation ->
        writer.execute {
            try {
                val header = JSONObject().put("type", "oh-my-monev-diagnostics")
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE)
                    .put("android", android.os.Build.VERSION.SDK_INT)
                    .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                    .put("exportedAt", OffsetDateTime.now().toString()).toString()
                val previous = File(context.filesDir, "diagnostic-actions.previous.jsonl")
                val rows = listOf(previous, file(context)).filter { it.isFile }.joinToString("") { it.readText() }
                if (continuation.isActive) continuation.resume(header + "\n" + rows)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    }
}

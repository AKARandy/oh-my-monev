package id.ohmymonev.android.diagnostics

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal class StallDetector(private val thresholdMs: Long = 5_000) {
    private var reported = false
    fun sample(delayMs: Long): String? = when {
        delayMs >= thresholdMs && !reported -> { reported = true; "ui.stall" }
        delayMs < thresholdMs && reported -> { reported = false; "ui.recovered" }
        else -> null
    }
}

internal object StallWatchdog {
    @Volatile var stage = "process.start"
    private val started = AtomicBoolean(false)
    fun start(context: android.content.Context) {
        if (!started.compareAndSet(false, true)) return
        val main = Handler(Looper.getMainLooper())
        val pending = AtomicBoolean(false)
        var postedAt = SystemClock.uptimeMillis()
        val detector = StallDetector()
        val executor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "MonevStallWatchdog").apply { isDaemon = true } }
        executor.scheduleWithFixedDelay({
            if (!android.os.Debug.isDebuggerConnected()) {
                val delay = if (pending.get()) SystemClock.uptimeMillis() - postedAt else 0
                detector.sample(delay)?.let { event ->
                    try {
                        val target = File(context.filesDir, "diagnostic-stalls.jsonl")
                        if (target.length() > 512_000) {
                            val previous = File(context.filesDir, "diagnostic-stalls.previous.jsonl")
                            previous.delete(); target.renameTo(previous)
                        }
                        val stack = if (event == "ui.stall") Looper.getMainLooper().thread.stackTrace.take(80).joinToString("\n") else ""
                        target.appendText(JSONObject().put("time", OffsetDateTime.now().toString()).put("event", event)
                            .put("delayMs", delay).put("stage", stage).put("mainStack", stack).toString() + "\n")
                    } catch (_: Exception) { android.util.Log.w("StallWatchdog", "Cannot save stall evidence") }
                }
            }
            if (pending.compareAndSet(false, true)) {
                postedAt = SystemClock.uptimeMillis()
                main.post { pending.set(false) }
            }
        }, 0, 1, TimeUnit.SECONDS)
    }
}

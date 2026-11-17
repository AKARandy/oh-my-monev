package id.ohmymonev.android.schedule

import android.content.BroadcastReceiver
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A broadcast owns a short local task, never a network request or model inference. */
internal object ReceiverWork {
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val jobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    internal suspend fun awaitIdle() { while (jobs.isNotEmpty()) jobs.toList().joinAll() }

    fun launch(
        receiver: BroadcastReceiver,
        timeoutMillis: Long = 8_000,
        onTimeout: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
        work: suspend CoroutineScope.() -> Unit,
    ) {
        val pending = receiver.goAsync()
        val finished = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + worker)
        fun finishPending() {
            try { pending?.finish() }
            catch (_: Exception) { android.util.Log.w("ReceiverWork", "Could not finish receiver") }
        }
        fun finish(): Boolean {
            if (!finished.compareAndSet(false, true)) return false
            finishPending()
            return true
        }
        val deadline = deadlines.schedule({
            if (finish()) {
                // This deadline finishes the broadcast; cancellation cannot stop a blocking call already in progress.
                scope.cancel()
                recover(onTimeout)
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { ensureActive(); work() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                android.util.Log.w("ReceiverWork", "Local alarm work failed")
                recover(onFailure)
            }
            finally { deadline.cancel(false); finish(); scope.cancel() }
        }
        jobs.add(job)
        job.invokeOnCompletion { jobs.remove(job) }
        job.start()
    }

    private fun recover(callback: (() -> Unit)?) {
        try { callback?.invoke() }
        catch (_: Exception) { android.util.Log.w("ReceiverWork", "Recovery request failed") }
    }
}

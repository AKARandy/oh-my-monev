package id.ohmymonev.android.schedule

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReceiverWorkTest {
    @Test fun slowWorkDoesNotHoldTheReceiverCaller() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var completed = false
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {} }
        ReceiverWork.launch(receiver) { entered.complete(Unit); release.await(); completed = true }
        kotlinx.coroutines.withTimeout(2_000) { entered.await() }
        assertFalse(completed)
        release.complete(Unit)
        kotlinx.coroutines.withTimeout(2_000) { ReceiverWork.awaitIdle() }
        assertTrue(completed)
    }

    @Test fun deadlineRequestsRecoveryForReceiverWork() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val timedOut = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {} }
        ReceiverWork.launch(receiver, timeoutMillis = 50, onTimeout = { timedOut.complete(Unit) }) {
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        kotlinx.coroutines.withTimeout(2_000) { entered.await() }
        kotlinx.coroutines.withTimeout(2_000) { timedOut.await() }
        kotlinx.coroutines.withTimeout(2_000) { ReceiverWork.awaitIdle() }
    }

    @Test fun failedReceiverWorkRequestsRecovery() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {} }
        ReceiverWork.launch(receiver, onFailure = { failed.complete(Unit) }) {
            throw IllegalStateException("secret must not be logged")
        }
        kotlinx.coroutines.withTimeout(2_000) { failed.await() }
        kotlinx.coroutines.withTimeout(2_000) { ReceiverWork.awaitIdle() }
    }
}

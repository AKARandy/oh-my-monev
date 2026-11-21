package id.ohmymonev.android.schedule

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import id.ohmymonev.android.data.ActionLog
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.core.schedule.ScheduleSettings
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class AlarmRearmWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ActionLog.record(applicationContext, "alarm.rearm.started", "attempt=${runAttemptCount + 1}")
        SessionRepository.get(applicationContext).awaitLoaded()
        val result = when (runAlarmRearm(
            attempts = runAttemptCount,
            readSettings = { TimeSettingsStore(applicationContext).settingsOnce() },
            arm = { AlarmScheduler.armAll(applicationContext, it) },
            onFailure = { AlarmScheduler.recordRecoveryFailure(applicationContext) },
        )) {
            RearmOutcome.SUCCESS -> Result.success().also { ActionLog.record(applicationContext, "alarm.rearm.succeeded") }
            RearmOutcome.RETRY -> Result.retry().also {
                ActionLog.record(applicationContext, "alarm.rearm.retry", "attempt=${runAttemptCount + 1}")
            }
            RearmOutcome.FAILURE -> Result.failure().also { ActionLog.record(applicationContext, "alarm.rearm.failed", "attempts=3") }
        }
        return result
    }
}

internal enum class RearmOutcome { SUCCESS, RETRY, FAILURE }

internal suspend fun runAlarmRearm(
    attempts: Int,
    readSettings: suspend () -> ScheduleSettings,
    arm: (ScheduleSettings) -> ArmReport,
    onFailure: () -> Unit,
): RearmOutcome {
    return try {
        val settings = readSettings()
        if (arm(settings).errors.isEmpty()) RearmOutcome.SUCCESS
        else {
            onFailure()
            retryOrFinish(attempts)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        onFailure()
        retryOrFinish(attempts)
    }
}

private fun retryOrFinish(attempts: Int): RearmOutcome =
    if (attempts < MAX_RETRIES) RearmOutcome.RETRY else RearmOutcome.FAILURE

internal object AlarmRearmWork {
    const val UNIQUE_NAME = "alarm-rearm"
    private const val TAG = "AlarmRearmWork"

    fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<AlarmRearmWorker>()
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        .build()

    fun enqueue(context: Context) {
        AlarmScheduler.recordRecoveryFailure(context)
        ActionLog.record(context, "alarm.rearm.queued")
        try {
            enqueueUnique { name, policy, request ->
                WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
            }
        } catch (_: Exception) {
            Log.w(TAG, "Could not queue local alarm recovery")
        }
    }

    internal fun enqueueUnique(enqueue: (String, ExistingWorkPolicy, OneTimeWorkRequest) -> Unit) {
        enqueue(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
    }
}

private const val MAX_RETRIES = 2

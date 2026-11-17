package id.ohmymonev.android.schedule

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import id.ohmymonev.core.schedule.ScheduleSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AlarmRearmWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clearRecoveryStatus() {
        context.getSharedPreferences("alarm_recovery", Context.MODE_PRIVATE).edit().clear().commit()
        WorkDaysStore(context).clear()
    }

    @Test fun missingSettingsCanRetryWithoutCallingAlarmManager() = runBlocking {
        var armed = false
        var failures = 0
        val outcome = runAlarmRearm(
            attempts = 0,
            readSettings = { throw IllegalStateException("missing settings") },
            arm = { armed = true; ArmReport(emptyList(), emptyList()) },
            onFailure = { failures++ },
        )
        assertEquals(RearmOutcome.RETRY, outcome)
        assertFalse(armed)
        assertEquals(1, failures)
    }

    @Test fun workerReadsLatestSettingsAndDoesNotCaptureThemInRequest() = runBlocking {
        val queuedSettings = ScheduleSettings(masterEnabled = true)
        val request = AlarmRearmWork.request()
        val settings = queuedSettings.copy(masterEnabled = false, reminderEnabled = false)
        var armedWith: ScheduleSettings? = null
        val outcome = runAlarmRearm(
            attempts = 0,
            readSettings = { settings },
            arm = { armedWith = it; ArmReport(emptyList(), emptyList()) },
            onFailure = { error("unexpected recovery failure") },
        )
        assertEquals(RearmOutcome.SUCCESS, outcome)
        assertEquals(settings, armedWith)
        assertTrue(request.workSpec.input.keyValueMap.isEmpty())
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(10_000L, request.workSpec.backoffDelayDuration)
    }

    @Test fun retryStopsAfterThreeTotalAttempts() = runBlocking {
        val retry: suspend (Int) -> RearmOutcome = { attempt ->
            runAlarmRearm(
                attempts = attempt,
                readSettings = { ScheduleSettings() },
                arm = { ArmReport(emptyList(), listOf("unsafe details are not persisted")) },
                onFailure = {},
            )
        }
        assertEquals(RearmOutcome.RETRY, retry(0))
        assertEquals(RearmOutcome.RETRY, retry(1))
        assertEquals(RearmOutcome.FAILURE, retry(2))
    }

    @Test fun recoveryRequestsUseOneUniqueKeepWork() {
        val calls = mutableListOf<Pair<String, ExistingWorkPolicy>>()
        val enqueue: (String, ExistingWorkPolicy, androidx.work.OneTimeWorkRequest) -> Unit = { name, policy, _ ->
            calls += name to policy
        }
        AlarmRearmWork.enqueueUnique(enqueue)
        AlarmRearmWork.enqueueUnique(enqueue)
        assertEquals(listOf(AlarmRearmWork.UNIQUE_NAME to ExistingWorkPolicy.KEEP,
            AlarmRearmWork.UNIQUE_NAME to ExistingWorkPolicy.KEEP), calls)
    }

    @Test fun recoveryFailurePersistsAsSafeScheduleStatus() {
        AlarmScheduler.recordRecoveryFailure(context)
        val state = AlarmScheduler.readArmedState(
            context,
            ScheduleSettings(),
            ZonedDateTime.parse("2026-11-07T20:36:00+07:00[Asia/Jakarta]"),
        )
        assertEquals("Alarm belum pulih. Periksa jadwal.", state.lastError)
        assertNull(state.nextFireISO)
    }

    @Test fun successfulAlarmArmingClearsPersistedRecoveryStatus() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        readySession(context)
        WorkDaysStore(context).save(org.json.JSONObject().put("id", "participant").put("off_days", org.json.JSONArray()))
        AlarmScheduler.recordRecoveryFailure(context)
        val report = AlarmScheduler.armAll(context, ScheduleSettings())
        assertTrue(report.errors.isEmpty())
        assertNull(AlarmScheduler.readArmedState(context, ScheduleSettings()).lastError)
    }
}

package id.ohmymonev.android.schedule

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import id.ohmymonev.core.schedule.ScheduleSettings
import java.time.DayOfWeek
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TimeSettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val job = SupervisorJob()
    private val file = File(context.cacheDir, "schedule-${UUID.randomUUID()}.preferences_pb")
    private val preferences = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + job),
        produceFile = { file },
    )
    private val store = TimeSettingsStore(preferences)

    @After fun closeStore() = runBlocking { job.cancelAndJoin() }

    private suspend fun seedLegacy(days: String, filling: String = "FILL_ONLY", lastChance: String = "AUTO_FILL_LAST_CHANCE") {
        preferences.edit {
            it[stringPreferencesKey("active_days")] = days
            it[stringPreferencesKey("autofill_policy")] = filling
            it[stringPreferencesKey("escalation_policy")] = lastChance
        }
    }

    @Test fun legacySharedDaysCarryOverToAllFeatures() = runBlocking {
        seedLegacy("1,3,5")
        val s = store.settingsOnce()
        val expected = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        assertEquals(expected, s.reminderDays)
        assertEquals(expected, s.autofillDays)
        assertEquals(expected, s.escalationDays)
    }

    @Test fun legacySendingOptInIsPreserved() = runBlocking {
        seedLegacy("", "FILL_AND_SUBMIT")
        val s = store.settingsOnce()
        assertEquals(DayOfWeek.entries.toSet(), s.reminderDays)
        assertEquals(ScheduleSettings.Policy.FILL_AND_SUBMIT, s.escalationAction)
    }

    @Test fun legacyNotificationOnlyStaysNotificationOnly() = runBlocking {
        seedLegacy("1", "FILL_AND_SUBMIT", "NOTIFY_ONLY")
        assertEquals(ScheduleSettings.Policy.REMIND_ONLY, store.settingsOnce().escalationAction)
    }

    @Test fun featureDaysAndSendingRoundTripIndependently() = runBlocking {
        val original = ScheduleSettings(
            reminderDays = setOf(DayOfWeek.MONDAY),
            autofillDays = setOf(DayOfWeek.TUESDAY),
            escalationDays = setOf(DayOfWeek.FRIDAY),
            autofillPolicy = ScheduleSettings.Policy.FILL_AND_SUBMIT,
            escalationAction = ScheduleSettings.Policy.FILL_ONLY,
        )
        store.save(original)
        assertEquals(original, store.settingsOnce())
        val changed = store.settingsOnce().copy(reminderDays = setOf(DayOfWeek.SUNDAY))
        store.save(changed)
        assertEquals(changed, store.settingsOnce())
        assertEquals(original.autofillDays, store.settingsOnce().autofillDays)
        assertEquals(original.escalationDays, store.settingsOnce().escalationDays)
    }

    @Test fun emptyFeatureDaysDoNotTurnIntoEveryDay() = runBlocking {
        store.save(ScheduleSettings(autofillDays = emptySet()))
        assertEquals(emptySet<DayOfWeek>(), store.settingsOnce().autofillDays)
        assertEquals(DayOfWeek.entries.toSet(), store.settingsOnce().reminderDays)
    }
}

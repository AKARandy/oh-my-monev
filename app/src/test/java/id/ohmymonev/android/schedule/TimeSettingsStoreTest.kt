package id.ohmymonev.android.schedule

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
import org.junit.Assert.assertTrue
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

    @Test fun freshStoreStartsWithTemplateSourceAndSafeDefaults() = runBlocking {
        val s = store.settingsOnce()
        assertEquals(ScheduleSettings.ContentSource.TEMPLATE, s.escalationSource)
        assertEquals(java.time.LocalTime.of(22, 30), s.escalationTime)
        org.junit.Assert.assertFalse(s.escalationEnabled)
    }

    @Test fun legacySharedDaysCarryOverToBothFeatures() = runBlocking {
        preferences.edit {
            it[stringPreferencesKey("active_days")] = "1,3,5"
        }
        val s = store.settingsOnce()
        val expected = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        assertEquals(expected, s.reminderDays)
        assertEquals(expected, s.escalationDays)
    }

    @Test fun oldAutofillKeysAreIgnoredAndEscalationSettingsSurvive() = runBlocking {
        preferences.edit {
            it[stringPreferencesKey("autofill_policy")] = "FILL_ONLY"
            it[intPreferencesKey("autofill_minutes_of_day")] = 21 * 60
            it[intPreferencesKey("escalation_minutes_of_day")] = 22 * 60 + 45
            it[stringPreferencesKey("escalation_source")] = "AUTOMATIC"
        }
        val s = store.settingsOnce()
        assertEquals(java.time.LocalTime.of(22, 45), s.escalationTime)
        assertEquals(ScheduleSettings.ContentSource.AUTOMATIC, s.escalationSource)
    }

    @Test fun invalidStoredSourceFallsBackToTemplate() = runBlocking {
        preferences.edit {
            it[stringPreferencesKey("escalation_source")] = "FILL_AND_SUBMIT"
        }
        assertEquals(ScheduleSettings.ContentSource.TEMPLATE, store.settingsOnce().escalationSource)
    }

    @Test fun featureDaysAndSourceRoundTripIndependently() = runBlocking {
        val original = ScheduleSettings(
            reminderDays = setOf(DayOfWeek.MONDAY),
            escalationDays = setOf(DayOfWeek.FRIDAY),
            escalationSource = ScheduleSettings.ContentSource.AUTOMATIC,
        )
        store.save(original)
        assertEquals(original, store.settingsOnce())
        val changed = store.settingsOnce().copy(reminderDays = setOf(DayOfWeek.SUNDAY))
        store.save(changed)
        assertEquals(changed, store.settingsOnce())
        assertEquals(original.escalationDays, store.settingsOnce().escalationDays)
    }

    @Test fun emptyFeatureDaysDoNotTurnIntoEveryDay() = runBlocking {
        store.save(ScheduleSettings(escalationDays = emptySet()))
        assertEquals(emptySet<DayOfWeek>(), store.settingsOnce().escalationDays)
        assertEquals(DayOfWeek.entries.toSet(), store.settingsOnce().reminderDays)
    }

    @Test fun pausingAndResumingPreservesEveryFeatureChoice() = runBlocking {
        val original = ScheduleSettings(
            reminderEnabled = false,
            reminderDays = setOf(DayOfWeek.MONDAY),
            escalationDays = setOf(DayOfWeek.FRIDAY),
            escalationTime = java.time.LocalTime.of(22, 45),
            escalationSource = ScheduleSettings.ContentSource.AUTOMATIC,
        )
        store.save(original)
        store.save(store.settingsOnce().copy(masterEnabled = false))
        assertEquals(original.copy(masterEnabled = false), store.settingsOnce())
        store.save(store.settingsOnce().copy(masterEnabled = true))
        assertEquals(original, store.settingsOnce())
    }

    @Test fun stableReleaseIgnoresEnabledEmergencyFromPreviousVersion() = runBlocking {
        preferences.edit {
            it[androidx.datastore.preferences.core.booleanPreferencesKey("escalation_enabled")] = true
        }
        val settings = store.settingsOnce()
        org.junit.Assert.assertFalse(settings.escalationEnabled)
        assertTrue(settings.reminderEnabled)
    }

}

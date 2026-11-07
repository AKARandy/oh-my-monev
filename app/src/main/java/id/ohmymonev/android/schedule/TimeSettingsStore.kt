package id.ohmymonev.android.schedule

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import id.ohmymonev.core.schedule.ScheduleSettings
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/* Versi store terpisah agar kunci tak bentrok kalau lead menambah DataStore lain. */
private val Context.scheduleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "schedule_settings",
)

/**
 * Bungkus DataStore<Preferences> untuk [ScheduleSettings].
 * Waktu disimpan sebagai menit-sejak-tengah-malam (stabil & tahan format salah).
 * Dependensi: androidx.datastore:datastore-preferences:1.1.1 (diminta ke lead).
 */
class TimeSettingsStore internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.scheduleDataStore)

    private object Keys {
        val REMINDER_MINUTES = intPreferencesKey("reminder_minutes_of_day")
        val AUTOFILL_MINUTES = intPreferencesKey("autofill_minutes_of_day")
        val ESCALATION_MINUTES = intPreferencesKey("escalation_minutes_of_day")
        val CANCEL_WINDOW_MINUTES = intPreferencesKey("cancel_window_minutes")
        val ACTIVE_DAYS = stringPreferencesKey("active_days") // Kunci lama untuk migrasi hari bersama.
        val REMINDER_DAYS = stringPreferencesKey("reminder_days")
        val AUTOFILL_DAYS = stringPreferencesKey("autofill_days")
        val ESCALATION_DAYS = stringPreferencesKey("escalation_days")
        val ESCALATION_ACTION = stringPreferencesKey("escalation_action")
        val AUTOFILL_POLICY = stringPreferencesKey("autofill_policy")
        val ESCALATION_POLICY = stringPreferencesKey("escalation_policy")
        val MASTER_ENABLED = booleanPreferencesKey("master_enabled")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val AUTOFILL_ENABLED = booleanPreferencesKey("autofill_enabled")
        val ESCALATION_ENABLED = booleanPreferencesKey("escalation_enabled")
    }

    val settings: Flow<ScheduleSettings> = dataStore.data.map { prefs ->
        val legacyDays = activeDays(prefs[Keys.ACTIVE_DAYS])
        val autofillPolicy = prefs[Keys.AUTOFILL_POLICY]?.toPolicyOrNull() ?: DEFAULT.autofillPolicy
        val legacyEscalation = when {
            prefs[Keys.ESCALATION_POLICY] == "NOTIFY_ONLY" -> ScheduleSettings.Policy.REMIND_ONLY
            autofillPolicy == ScheduleSettings.Policy.FILL_AND_SUBMIT -> ScheduleSettings.Policy.FILL_AND_SUBMIT
            else -> ScheduleSettings.Policy.FILL_ONLY
        }
        ScheduleSettings(
            reminderTime = minutesOfDay(prefs, Keys.REMINDER_MINUTES, DEFAULT.reminderTime),
            autofillTime = minutesOfDay(prefs, Keys.AUTOFILL_MINUTES, DEFAULT.autofillTime),
            escalationTime = minutesOfDay(prefs, Keys.ESCALATION_MINUTES, DEFAULT.escalationTime),
            cancelWindowMinutes = (prefs[Keys.CANCEL_WINDOW_MINUTES] ?: DEFAULT.cancelWindowMinutes.toInt()).toLong(),
            reminderDays = readDays(prefs[Keys.REMINDER_DAYS], legacyDays),
            autofillDays = readDays(prefs[Keys.AUTOFILL_DAYS], legacyDays),
            escalationDays = readDays(prefs[Keys.ESCALATION_DAYS], legacyDays),
            autofillPolicy = autofillPolicy,
            escalationAction = prefs[Keys.ESCALATION_ACTION]?.toPolicyOrNull() ?: legacyEscalation,
            masterEnabled = prefs[Keys.MASTER_ENABLED] ?: DEFAULT.masterEnabled,
            reminderEnabled = prefs[Keys.REMINDER_ENABLED] ?: DEFAULT.reminderEnabled,
            autofillEnabled = prefs[Keys.AUTOFILL_ENABLED] ?: DEFAULT.autofillEnabled,
            escalationEnabled = prefs[Keys.ESCALATION_ENABLED] ?: DEFAULT.escalationEnabled,
        )
    }

    /** Pembacaan sekali pukur untuk dipakai di BroadcastReceiver. */
    suspend fun settingsOnce(): ScheduleSettings = settings.first()

    suspend fun save(settings: ScheduleSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.REMINDER_MINUTES] = settings.reminderTime.toSecondOfDay() / 60
            prefs[Keys.AUTOFILL_MINUTES] = settings.autofillTime.toSecondOfDay() / 60
            prefs[Keys.ESCALATION_MINUTES] = settings.escalationTime.toSecondOfDay() / 60
            prefs[Keys.CANCEL_WINDOW_MINUTES] = settings.cancelWindowMinutes.toInt()
            prefs[Keys.REMINDER_DAYS] = settings.reminderDays.sortedBy { it.value }.joinToString(",") { it.value.toString() }
            prefs[Keys.AUTOFILL_DAYS] = settings.autofillDays.sortedBy { it.value }.joinToString(",") { it.value.toString() }
            prefs[Keys.ESCALATION_DAYS] = settings.escalationDays.sortedBy { it.value }.joinToString(",") { it.value.toString() }
            prefs[Keys.AUTOFILL_POLICY] = settings.autofillPolicy.name
            prefs[Keys.ESCALATION_ACTION] = settings.escalationAction.name
            prefs[Keys.MASTER_ENABLED] = settings.masterEnabled
            prefs[Keys.REMINDER_ENABLED] = settings.reminderEnabled
            prefs[Keys.AUTOFILL_ENABLED] = settings.autofillEnabled
            prefs[Keys.ESCALATION_ENABLED] = settings.escalationEnabled
        }
    }

    private companion object {
        val DEFAULT = ScheduleSettings()

        fun minutesOfDay(prefs: Preferences, key: Preferences.Key<Int>, fallback: LocalTime): LocalTime {
            val minutes = prefs[key] ?: return fallback
            return try {
                LocalTime.ofSecondOfDay(minutes * 60L)
            } catch (_: Exception) {
                fallback
            }
        }

        fun activeDays(raw: String?): Set<DayOfWeek> {
            val text = raw ?: return DayOfWeek.entries.toSet()
            val parsed = text.split(',').mapNotNull { it.trim().toIntOrNull() }
                .filter { it in DayOfWeek.entries.map { d -> d.value } }
                .map { DayOfWeek.of(it) }.toSet()
            return parsed.ifEmpty { DayOfWeek.entries.toSet() }
        }

        fun String.toPolicyOrNull(): ScheduleSettings.Policy? = try {
            ScheduleSettings.Policy.valueOf(this)
        } catch (_: IllegalArgumentException) {
            null
        }

        fun readDays(raw: String?, fallback: Set<DayOfWeek>): Set<DayOfWeek> =
            raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it in 1..7 }?.map { DayOfWeek.of(it) }?.toSet() ?: fallback

    }
}

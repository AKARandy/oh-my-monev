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
class TimeSettingsStore(private val context: Context) {

    private object Keys {
        val REMINDER_MINUTES = intPreferencesKey("reminder_minutes_of_day")
        val AUTOFILL_MINUTES = intPreferencesKey("autofill_minutes_of_day")
        val ESCALATION_MINUTES = intPreferencesKey("escalation_minutes_of_day")
        val CANCEL_WINDOW_MINUTES = intPreferencesKey("cancel_window_minutes")
        val ACTIVE_DAYS = stringPreferencesKey("active_days") // ordinal dipisah koma, kosong = default
        val AUTOFILL_POLICY = stringPreferencesKey("autofill_policy")
        val ESCALATION_POLICY = stringPreferencesKey("escalation_policy")
        val MASTER_ENABLED = booleanPreferencesKey("master_enabled")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val AUTOFILL_ENABLED = booleanPreferencesKey("autofill_enabled")
        val ESCALATION_ENABLED = booleanPreferencesKey("escalation_enabled")
    }

    val settings: Flow<ScheduleSettings> = context.scheduleDataStore.data.map { prefs ->
        ScheduleSettings(
            reminderTime = minutesOfDay(prefs, Keys.REMINDER_MINUTES, DEFAULT.reminderTime),
            autofillTime = minutesOfDay(prefs, Keys.AUTOFILL_MINUTES, DEFAULT.autofillTime),
            escalationTime = minutesOfDay(prefs, Keys.ESCALATION_MINUTES, DEFAULT.escalationTime),
            cancelWindowMinutes = (prefs[Keys.CANCEL_WINDOW_MINUTES] ?: DEFAULT.cancelWindowMinutes.toInt()).toLong(),
            activeDays = activeDays(prefs[Keys.ACTIVE_DAYS]),
            autofillPolicy = prefs[Keys.AUTOFILL_POLICY]?.toPolicyOrNull()
                ?: DEFAULT.autofillPolicy,
            escalationPolicy = prefs[Keys.ESCALATION_POLICY]?.toEscalationOrNull()
                ?: DEFAULT.escalationPolicy,
            masterEnabled = prefs[Keys.MASTER_ENABLED] ?: DEFAULT.masterEnabled,
            reminderEnabled = prefs[Keys.REMINDER_ENABLED] ?: DEFAULT.reminderEnabled,
            autofillEnabled = prefs[Keys.AUTOFILL_ENABLED] ?: DEFAULT.autofillEnabled,
            escalationEnabled = prefs[Keys.ESCALATION_ENABLED] ?: DEFAULT.escalationEnabled,
        )
    }

    /** Pembacaan sekali pukur untuk dipakai di BroadcastReceiver. */
    suspend fun settingsOnce(): ScheduleSettings = settings.first()

    suspend fun save(settings: ScheduleSettings) {
        context.scheduleDataStore.edit { prefs ->
            prefs[Keys.REMINDER_MINUTES] = settings.reminderTime.toSecondOfDay() / 60
            prefs[Keys.AUTOFILL_MINUTES] = settings.autofillTime.toSecondOfDay() / 60
            prefs[Keys.ESCALATION_MINUTES] = settings.escalationTime.toSecondOfDay() / 60
            prefs[Keys.CANCEL_WINDOW_MINUTES] = settings.cancelWindowMinutes.toInt()
            prefs[Keys.ACTIVE_DAYS] = if (settings.activeDays == DayOfWeek.entries.toSet()) {
                ""
            } else {
                settings.activeDays.map { it.value }.sorted().joinToString(",")
            }
            prefs[Keys.AUTOFILL_POLICY] = settings.autofillPolicy.name
            prefs[Keys.ESCALATION_POLICY] = settings.escalationPolicy.name
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

        fun String.toEscalationOrNull(): ScheduleSettings.EscalationPolicy? = try {
            ScheduleSettings.EscalationPolicy.valueOf(this)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

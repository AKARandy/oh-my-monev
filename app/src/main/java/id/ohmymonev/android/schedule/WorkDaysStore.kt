package id.ohmymonev.android.schedule

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import id.ohmymonev.core.schedule.ScheduleSettings

/** Cached profile calendar for alarms that run without a network connection. No weekend defaults. */
internal class WorkDaysStore(context: Context) {
    private val preferences = context.getSharedPreferences("work_days", Context.MODE_PRIVATE)
    fun clear() { preferences.edit().remove("days").commit() }
    fun lastAccount(): String? = preferences.getString("account", null)
    fun save(profile: JSONObject) {
        val rows = profile.optJSONArray("off_days")
        val off = rows?.let { (0 until it.length()).map { index -> runCatching { DayOfWeek.valueOf(it.getString(index)) }.getOrNull() } }
        if (profile.optString("id").isBlank() || off == null || off.any { it == null }) { clear(); return }
        preferences.edit().putString("account", profile.getString("id")).putString("days", DayOfWeek.entries.filter { it !in off }.joinToString(",") { it.name }).commit()
    }
    fun days(): Set<DayOfWeek>? = preferences.getString("days", null)?.let { text ->
        text.split(',').filter { it.isNotBlank() }.map { DayOfWeek.valueOf(it) }.toSet()
    }
    fun isWorkday(date: LocalDate): Boolean = days()?.contains(date.dayOfWeek) == true
    fun restrict(settings: ScheduleSettings): ScheduleSettings {
        val work = days().orEmpty()
        return settings.copy(reminderDays = settings.reminderDays intersect work,
            escalationDays = settings.escalationDays intersect work)
    }
}

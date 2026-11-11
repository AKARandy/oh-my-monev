package id.ohmymonev.android.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import id.ohmymonev.core.schedule.ScheduleCalculator
import java.time.LocalDate
import kotlinx.coroutines.runBlocking

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        id.ohmymonev.android.data.ActionLog.record(context, "alarm.received", "ReminderReceiver")
        if (intent.action != ACTION_REMINDER) return
        val settings = runBlocking { TimeSettingsStore(context).settingsOnce() }
        val date = intent.getStringExtra("date")?.let(LocalDate::parse)
            ?: LocalDate.now(ScheduleCalculator.asiaJakarta)
        val calendar = WorkDaysStore(context)
        if (FeatureReadiness.read(context).ready && settings.masterEnabled && settings.reminderEnabled &&
            date.dayOfWeek in settings.reminderDays && calendar.isWorkday(date)
        ) {
            ReminderController.enqueue(context, calendar.lastAccount(), date)
            GuardService.tryEnsure(context)
        }
        if (settings.masterEnabled) AlarmScheduler.armAll(context, settings)
    }

    companion object {
        const val ACTION_REMINDER = "id.ohmymonev.android.schedule.ACTION_REMINDER"
    }
}

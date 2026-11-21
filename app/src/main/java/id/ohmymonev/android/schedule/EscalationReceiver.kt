package id.ohmymonev.android.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import id.ohmymonev.android.data.SessionRepository
import kotlinx.coroutines.ensureActive

/**
 * Alarm pengisian darurat 22:30 (default) — satu-satunya pengisi otomatis, selalu
 * isi lalu kirim.
 */
class EscalationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReceiverWork.launch(this) { handle(context, intent) }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        id.ohmymonev.android.data.ActionLog.record(context, "alarm.received", "EscalationReceiver")
        val settings = TimeSettingsStore(context).settingsOnce()
        kotlin.coroutines.coroutineContext.ensureActive()
        SessionRepository.get(context).awaitLoaded()
        kotlin.coroutines.coroutineContext.ensureActive()
        try {
            if (!FeatureReadiness.read(context).ready) return
            val today = java.time.LocalDate.now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta)
            if (!WorkDaysStore(context).isWorkday(today)) return
            if (!settings.masterEnabled || !settings.escalationEnabled || today.dayOfWeek !in settings.escalationDays) return
            if (id.ohmymonev.android.data.AppStore(context).isSkipped(today.toString())) return
            kotlin.coroutines.coroutineContext.ensureActive()
            AutoFillService.start(context)
        } finally {
            if (settings.masterEnabled) AlarmScheduler.armAll(context, settings)
        }
    }

    companion object {
        const val ACTION_ESCALATION = "id.ohmymonev.android.schedule.ACTION_ESCALATION"
    }
}

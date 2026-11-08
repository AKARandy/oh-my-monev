package id.ohmymonev.android.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.runBlocking

/**
 * Alarm pengisian darurat 22:30 (default) — satu-satunya pengisi otomatis, selalu
 * isi lalu kirim. Notifikasi kanal "escalation" (full-screen intent + suara;
 * lihat NotificationFactory), lalu picu mesin pengisi.
 */
class EscalationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = runBlocking { TimeSettingsStore(context).settingsOnce() }
        val today = java.time.LocalDate.now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta)
        if (!WorkDaysStore(context).isWorkday(today)) return
        if (!settings.masterEnabled || !settings.escalationEnabled || today.dayOfWeek !in settings.escalationDays) return
        if (id.ohmymonev.android.data.AppStore(context).isSkipped(today.toString())) {
            return
        }
        val factory = NotificationFactory(context)
        factory.ensureChannels()
        if (factory.canPostNotifications()) {
            NotificationManagerCompat.from(context)
                .notify(NotificationFactory.NOTIF_ID_ESCALATION, factory.escalation())
        }
        AutoFillService.start(context)
    }

    companion object {
        const val ACTION_ESCALATION = "id.ohmymonev.android.schedule.ACTION_ESCALATION"
    }
}

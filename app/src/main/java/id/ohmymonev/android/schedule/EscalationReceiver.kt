package id.ohmymonev.android.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import id.ohmymonev.core.schedule.ScheduleSettings
import kotlinx.coroutines.runBlocking

/**
 * Alarm eskalasi 22:30 (default) — malam terakhir sebelum tengah malam.
 * Notifikasi kanal "escalation" (full-screen intent + suara; lihat NotificationFactory),
 * lalu — bila kebijakan AUTO_FILL_LAST_CHANCE — picu mesin autofill yang sama
 * dengan flag `lastChance`.
 */
class EscalationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = runBlocking { TimeSettingsStore(context).settingsOnce() }
        if (!settings.masterEnabled || !settings.escalationEnabled) return
        if (id.ohmymonev.android.data.AppStore(context).isSkipped(
                java.time.LocalDate.now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta).toString(),
            )
        ) {
            return
        }
        val factory = NotificationFactory(context)
        factory.ensureChannels()
        if (factory.canPostNotifications()) {
            NotificationManagerCompat.from(context)
                .notify(NotificationFactory.NOTIF_ID_ESCALATION, factory.escalation())
        }
        if (settings.escalationPolicy == ScheduleSettings.EscalationPolicy.AUTO_FILL_LAST_CHANCE) {
            AutoFillService.start(context, lastChance = true)
        }
    }

    companion object {
        const val ACTION_ESCALATION = "id.ohmymonev.android.schedule.ACTION_ESCALATION"
    }
}

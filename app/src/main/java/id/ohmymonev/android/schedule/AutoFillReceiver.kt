package id.ohmymonev.android.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import id.ohmymonev.core.schedule.ScheduleSettings
import kotlinx.coroutines.runBlocking

/**
 * Alarm pengisian otomatis 21:00 (default). Tipis: cek kebijakan, lalu
 * start AutoFillService (FGS + full-screen intent) yang memanggil AutoFillDelegate.
 * TODO(lead): sambungkan AutoFillDelegateProvider.delegate ke mesin WebView (M2).
 */
class AutoFillReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val lastChance = intent.getBooleanExtra(AutoFillService.EXTRA_LAST_CHANCE, false)
        val settings = runBlocking { TimeSettingsStore(context).settingsOnce() }
        if (!settings.masterEnabled || !settings.autofillEnabled) return
        // Pengguna membatalkan malam ini: hormati, jangan jalankan apa pun.
        if (id.ohmymonev.android.data.AppStore(context).isSkipped(
                java.time.LocalDate.now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta).toString(),
            )
        ) {
            return
        }
        val factory = NotificationFactory(context)
        factory.ensureChannels()

        if (settings.autofillPolicy == ScheduleSettings.Policy.REMIND_ONLY) {
            // Kebijakan hanya-mengingatkan: jangan jalankan mesin sama sekali.
            if (factory.canPostNotifications()) {
                NotificationManagerCompat.from(context).notify(
                    NotificationFactory.NOTIF_ID_REMINDER,
                    factory.info(
                        "oh-my-monev: isi otomatis dimatikan",
                        "Isi manual sebelum 23.59 WIB bila belum terisi.",
                    ),
                )
            }
            return
        }
        AutoFillService.start(context, lastChance)
    }

    companion object {
        const val ACTION_AUTOFILL = "id.ohmymonev.android.schedule.ACTION_AUTOFILL"
    }
}

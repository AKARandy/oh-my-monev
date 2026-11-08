package id.ohmymonev.android.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.runBlocking

/**
 * Alarm 20:00 (default): pengingat aman-izin dengan saran preflight.
 * Tipis satu tugas: tampilkan notifikasi, selesai.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = runBlocking { TimeSettingsStore(context).settingsOnce() }
        val today = java.time.LocalDate.now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta)
        if (!WorkDaysStore(context).isWorkday(today)) return
        if (!settings.masterEnabled || !settings.reminderEnabled || today.dayOfWeek !in settings.reminderDays) return
        val factory = NotificationFactory(context)
        factory.ensureChannels()
        if (!factory.canPostNotifications()) return // POST_NOTIFICATIONS belum diberikan
        // Jangan pernah mengklaim status yang belum diperiksa: pengingat bersifat netral,
        // status sebenarnya dibaca lewat tab Beranda ("Cek situs").
        val detail =
            "Buka aplikasi untuk melihat status laporan hari ini.\n" +
                "1. Tab Beranda: tombol \"Cek situs\" membaca kondisi sebenarnya.\n" +
                "2. Izin lokasi dan GPS sebaiknya menyala.\n" +
                "3. Isi manual lewat tab Tulis, atau biarkan alarm berikutnya bekerja."
        NotificationManagerCompat.from(context)
            .notify(NotificationFactory.NOTIF_ID_REMINDER, factory.neutralReminder(detail))
    }

    companion object {
        const val ACTION_REMINDER = "id.ohmymonev.android.schedule.ACTION_REMINDER"
    }
}

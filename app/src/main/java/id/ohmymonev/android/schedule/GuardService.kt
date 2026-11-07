package id.ohmymonev.android.schedule

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.runBlocking

/**
 * Layanan penjaga: notifikasi permanen yang menjadi BUKTI nyata status otomatisasi.
 *
 * Aturan keberadaannya (disetujui pengguna):
 * - Tampil hanya saat alarm otomatis aktif (pengaturan otomatis menyala dan alarm terpasang).
 * - Tidak bisa dihilangkan dengan swipe; kalau prosesnya dibunuh sistem, START_STICKY
 *   menghidupkan ulang dan notifikasi muncul lagi (sekaligus memperbaiki alarm yang hilang).
 * - Hilang total saat aplikasi di-Force Stop (sistem menghapus semuanya) — memang begitu aturannya.
 * - Hilang saat pengaturan otomatis dimatikan di aplikasi.
 */
class GuardService : Service() {

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val settings = runBlocking { TimeSettingsStore(this@GuardService).settingsOnce() }
        if (!settings.masterEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) {
            // Dihidupkan ulang oleh sistem (START_STICKY): perbaiki alarm yang mungkin hilang.
            AlarmScheduler.armAll(this, settings)
        }
        refreshContent()
        return START_STICKY
    }

    /** Aplikasi di-swipe dari Recents: layanan tetap hidup; pastikan alarm tetap terpasang. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val settings = runBlocking { TimeSettingsStore(this@GuardService).settingsOnce() }
        if (settings.masterEnabled) {
            AlarmScheduler.armAll(this, settings)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val factory = NotificationFactory(this)
        factory.ensureChannels()
        val notification = factory.guard(nextFireISO = null, skipped = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NotificationFactory.NOTIF_ID_GUARD,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationFactory.NOTIF_ID_GUARD,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationFactory.NOTIF_ID_GUARD, notification)
        }
    }

    /** Perbarui isi notifikasi dari keadaan sesungguhnya (alarm OS + pilihan skip malam ini). */
    fun refreshContent() {
        val factory = NotificationFactory(this)
        val next = (getSystemService(android.app.AlarmManager::class.java))?.nextAlarmClock
        val nextISO = next?.triggerTime?.let(java.time.Instant::ofEpochMilli)?.toString()
        val skipped = AppStoreBridge.isTodaySkipped(this)
        val notification: Notification = factory.guard(nextFireISO = nextISO, skipped = skipped)
        androidx.core.app.NotificationManagerCompat.from(this)
            .notify(NotificationFactory.NOTIF_ID_GUARD, notification)
    }

    companion object {
        fun ensure(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}

/** Jembatan kecil agar layanan bisa membaca pilihan "isi sendiri malam ini" tanpa ketergantungan siklus. */
private object AppStoreBridge {
    fun isTodaySkipped(context: Context): Boolean = try {
        val today = java.time.LocalDate
            .now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta).toString()
        id.ohmymonev.android.data.AppStore(context).isSkipped(today)
    } catch (_: Exception) {
        false
    }
}

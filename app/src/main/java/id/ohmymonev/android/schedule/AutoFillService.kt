package id.ohmymonev.android.schedule

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * Layanan layar depan tipe dataSync yang satu-satunya tugasnya menjalankan
 * [AutoFillDelegate] (mesin WebView milik lead) sampai autofill selesai.
 * TODO(lead): panggil AutoFillService.finished(context) saat mesin selesai/gagal.
 */
class AutoFillService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lastChance = intent?.getBooleanExtra(EXTRA_LAST_CHANCE, false) ?: false
        val factory = NotificationFactory(this)
        factory.ensureChannels()
        val notification: Notification = factory.autofillProgress()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationFactory.NOTIF_ID_AUTOFILL,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationFactory.NOTIF_ID_AUTOFILL, notification)
        }

        val delegate = AutoFillDelegateProvider.delegate
        if (delegate == null) {
            // Mesin belum terpasang: jangan diam saja saat malam money-critical.
            val factory = NotificationFactory(this)
            if (factory.canPostNotifications()) {
                NotificationManagerCompat.from(this).notify(
                    NotificationFactory.NOTIF_ID_REMINDER,
                    factory.info(
                        "oh-my-monev: pengisi otomatis belum siap",
                        "Isi manual di situs supaya stipend hari ini aman.",
                    ),
                )
            }
            stopSelf()
        } else {
            delegate.startAutoFill(applicationContext, lastChance)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_LAST_CHANCE = "last_chance"

        fun start(context: Context, lastChance: Boolean) {
            val intent = Intent(context, AutoFillService::class.java)
                .putExtra(EXTRA_LAST_CHANCE, lastChance)
            context.startForegroundService(intent)
        }

        /** Satu putaran uji mesin terhadap halaman tiruan di dalam aplikasi (debug). */
        fun startBench(context: Context) {
            id.ohmymonev.android.engine.DebugBench.pending = true
            start(context, lastChance = false)
        }

        /** Dipanggil mesin saat satu siklus autofill selesai (sukses maupun gagal). */
        fun finished(context: Context) {
            context.stopService(Intent(context, AutoFillService::class.java))
        }
    }
}

package id.ohmymonev.android.schedule

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import id.ohmymonev.android.MainActivity

/**
 * Pabrik notifikasi: 3 kanal + tingkat importance sesuai tangga malam (D4).
 * Semua teks pengguna bahasa Indonesia. Dependensi: androidx.core (lead).
 */
class NotificationFactory(private val context: Context) {

    // ------------------------------------------------------------------ kanal

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val reminder = NotificationChannel(
            CHANNEL_REMINDER,
            "Pengingat laporan",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Pengingat lembut: cek laporan MagangHub hari ini" }

        val autofill = NotificationChannel(
            CHANNEL_AUTOFILL,
            "Pengisian otomatis",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Notifikasi layar depan (foreground service) saat mesin mengisi laporan" }

        val escalation = NotificationChannel(
            CHANNEL_ESCALATION,
            "Eskalasi malam terakhir",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Peringatan keras sebelum tengah malam; laporan terlewat = stipend hilang"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
        }
        manager.createNotificationChannels(listOf(reminder, autofill, escalation))
    }

    // -------------------------------------------------------------- izin aman

    /** POST_NOTIFICATIONS aman: cek runtime (API 33+) sebelum notify. */
    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------- notifikasi

    /** Notifikasi informasi netral: hanya boleh menyatakan fakta yang benar-benar diketahui pemanggil. */
    fun info(title: String, text: String): Notification =
        builder(CHANNEL_REMINDER)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    /** Pengingat netral: tidak mengklaim status yang belum diperiksa. */
    fun neutralReminder(detail: String): Notification =
        builder(CHANNEL_REMINDER)
            .setContentTitle("oh-my-monev: cek laporan malam ini")
            .setContentText("Buka aplikasi untuk melihat status laporan dan menyiapkannya.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    /** Peringatan bersyarat: hanya berlaku kalau laporan memang belum terkirim. */
    fun escalation(): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return builder(CHANNEL_ESCALATION)
            .setContentTitle("Cek: laporan malam ini sudah terkirim?")
            .setContentText("Kalau belum, kirim sekarang sebelum 23.59 WIB. Kalau sudah, abaikan pesan ini.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(openApp, true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
    }

    /** Notifikasi layar depan untuk AutoFillService. */
    fun autofillProgress(): Notification =
        builder(CHANNEL_AUTOFILL)
            .setOngoing(true)
            .setContentTitle("oh-my-monev: mengisi laporan…")
            .setContentText("Mesin sedang mengisi laporan MagangHub. Jangan tutup aplikasi.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    // ------------------------------------------------------------- internal

    private fun builder(channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setAutoCancel(true)
            .setContentIntent(launchApp())

    private fun launchApp(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_REMINDER = "reminder"
        const val CHANNEL_AUTOFILL = "autofill"
        const val CHANNEL_ESCALATION = "escalation"

        const val NOTIF_ID_REMINDER = 2001
        const val NOTIF_ID_AUTOFILL = 2002 // juga ID startForeground layanan
        const val NOTIF_ID_ESCALATION = 2003

        private const val REQUEST_OPEN_APP = 3001
    }
}

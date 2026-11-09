package id.ohmymonev.android.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import id.ohmymonev.android.MainActivity

internal class ReminderNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val compat = NotificationManagerCompat.from(context)

    fun alarmChannel(): NotificationChannel {
        manager.createNotificationChannel(NotificationChannel(
            ALARM, "Alarm laporan", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            enableVibration(true)
        })
        return manager.getNotificationChannel(ALARM)
    }

    fun openSettings() {
        alarmChannel()
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ALARM))
    }

    fun post(key: String, title: String, detail: String, alarm: Boolean = false, normal: Boolean = false): Boolean {
        if (!compat.areNotificationsEnabled()) return false
        val channel = when {
            alarm -> alarmChannel()
            normal -> NotificationChannel(NORMAL, "Status laporan", NotificationManager.IMPORTANCE_DEFAULT)
            else -> NotificationChannel(QUIET, "Pemeriksaan laporan", NotificationManager.IMPORTANCE_LOW)
        }
        manager.createNotificationChannel(channel)
        if (manager.getNotificationChannel(channel.id).importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setData(Uri.fromParts("monev-reminder", key, null))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("reminder_site", key), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title).setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(open).setAutoCancel(!alarm).setOngoing(alarm)
        if (alarm) {
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
                .setTimeoutAfter(300_000)
                .addAction(0, "Hentikan", action(key, "stop"))
                .addAction(0, "Tunda 10 menit", action(key, "snooze"))
                .addAction(0, "Buka situs", open)
                .setDeleteIntent(action(key, "stop"))
        }
        // Cancel the previous quiet notice so Android can use the new channel.
        manager.cancel(key, ID)
        return try {
            compat.notify(key, ID, builder.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel(key: String) = manager.cancel(key, ID)

    private fun action(key: String, action: String): PendingIntent =
        PendingIntent.getService(context, 0, Intent(context, GuardService::class.java)
            .setAction(action).setData(Uri.fromParts("monev-reminder", key, null)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        const val ID = 2010
        private const val ALARM = "report_alarm"
        private const val NORMAL = "report_status"
        private const val QUIET = "report_check"
    }
}

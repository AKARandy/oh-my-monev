package id.ohmymonev.android.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import id.ohmymonev.core.schedule.ScheduleCalculator
import id.ohmymonev.core.schedule.ScheduleSettings
import java.time.ZonedDateTime

/**
 * Menjaga ketiga alarm malam tetap terpasang: aplikasi direstart/berhenti?
 * BootReceiver + PackageReplacedReceiver memanggil [armAll].
 * Menggunakan AlarmManager.setAlarmClock (AlarmClockInfo) — tahan Doze serta
 * bebas izin SCHEDULE_EXACT_ALARM, dan menampilkan ikon alarm di status bar
 * (kejujuran bagi pengguna; malam ini money-critical: D4).
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    /** requestCode tetap tiap alarm supaya setAlarmClock menggantikan alarm lama. */
    fun armAll(context: Context, settings: ScheduleSettings) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = ZonedDateTime.now(ScheduleCalculator.asiaJakarta)
        val fires = ScheduleCalculator.nextFires(now, settings, horizonDays = 7)
        for ((key, fire) in fires) {
            val intent = Intent(context, receiverFor(key)).apply {
                action = actionFor(key)
                putExtra(EXTRA_FIRE_KEY, key)
            }
            val pending = PendingIntent.getBroadcast(
                context,
                requestCodeFor(key),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val info = AlarmManager.AlarmClockInfo(fire.toInstant().toEpochMilli(), pending)
            try {
                am.setAlarmClock(info, pending)
            } catch (e: SecurityException) {
                // OEM tertentu membatasi exact alarm; jangan diam saat money-critical.
                Log.e(TAG, "setAlarmClock($key) ditolak sistem", e)
            }
        }
    }

    /** Alarm uji cepat: autofill penuh menyala [inSeconds] dari sekarang. */
    fun quickFireForTest(context: Context, inSeconds: Int = 90) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, AutoFillReceiver::class.java)
            .setAction(AutoFillReceiver.ACTION_AUTOFILL)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_TEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = System.currentTimeMillis() + inSeconds * 1000L
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pending), pending)
    }

    /**
     * Percobaan ulang berikutnya pada [at] (tangga +5/+20/+60 dari ScheduleCalculator).
     * requestCode tetap: percobaan berikutnya menggantikan yang lama.
     */
    fun scheduleRetry(context: Context, at: ZonedDateTime) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, AutoFillReceiver::class.java)
            .setAction(AutoFillReceiver.ACTION_AUTOFILL)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_RETRY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at.toInstant().toEpochMilli(), pending), pending)
    }

    // ------------------------------------------------------------- internal

    private const val EXTRA_FIRE_KEY = "fire_key"

    private fun receiverFor(key: String): Class<out BroadcastReceiver> = when (key) {
        ScheduleCalculator.KEY_REMINDER -> ReminderReceiver::class.java
        ScheduleCalculator.KEY_AUTOFILL -> AutoFillReceiver::class.java
        ScheduleCalculator.KEY_ESCALATION -> EscalationReceiver::class.java
        else -> throw IllegalArgumentException("kunci alarm tak dikenal: $key")
    }

    private fun actionFor(key: String): String = when (key) {
        ScheduleCalculator.KEY_REMINDER -> ReminderReceiver.ACTION_REMINDER
        ScheduleCalculator.KEY_AUTOFILL -> AutoFillReceiver.ACTION_AUTOFILL
        ScheduleCalculator.KEY_ESCALATION -> EscalationReceiver.ACTION_ESCALATION
        else -> throw IllegalArgumentException("kunci alarm tak dikenal: $key")
    }

    private fun requestCodeFor(key: String): Int = when (key) {
        ScheduleCalculator.KEY_REMINDER -> REQUEST_CODE_REMINDER
        ScheduleCalculator.KEY_AUTOFILL -> REQUEST_CODE_AUTOFILL
        ScheduleCalculator.KEY_ESCALATION -> REQUEST_CODE_ESCALATION
        else -> throw IllegalArgumentException("kunci alarm tak dikenal: $key")
    }

    private const val REQUEST_CODE_REMINDER = 11
    private const val REQUEST_CODE_AUTOFILL = 12
    private const val REQUEST_CODE_ESCALATION = 13
    private const val REQUEST_CODE_RETRY = 21
    private const val REQUEST_CODE_TEST = 99
}

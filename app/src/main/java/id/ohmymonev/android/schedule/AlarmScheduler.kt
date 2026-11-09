package id.ohmymonev.android.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import id.ohmymonev.core.schedule.ScheduleCalculator
import id.ohmymonev.core.schedule.ScheduleSettings
import java.time.ZonedDateTime

/** Hasil pemasangan alarm: berapa yang terpasang, dan kegagalan apa pun dengan alasannya. */
data class ArmReport(val armedKeys: List<String>, val errors: List<String>)

/**
 * Menjaga kedua alarm malam tetap terpasang: aplikasi direstart/berhenti?
 * BootReceiver + PackageReplacedReceiver memanggil [armAll].
 * Menggunakan AlarmManager.setAlarmClock (AlarmClockInfo) — tahan Doze serta
 * bebas izin SCHEDULE_EXACT_ALARM, dan menampilkan ikon alarm di status bar
 * (kejujuran bagi pengguna; malam ini money-critical: D4).
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    /** requestCode tetap tiap alarm supaya setAlarmClock menggantikan alarm lama. */
    @Synchronized
    fun armAll(context: Context, settings: ScheduleSettings): ArmReport {
        lastArmError = null
        val am = context.getSystemService(AlarmManager::class.java)
            ?: return ArmReport(emptyList(), listOf("AlarmManager tidak tersedia"))
        // Android 14+ menolak setAlarmClock tanpa izin exact. Coba exact dulu;
        // kalau ditolak, jatuh ke alarm tidak-exact (masih jalan, hanya bisa telat beberapa menit).
        val exactAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
        val now = ZonedDateTime.now(ScheduleCalculator.asiaJakarta)
        val ready = FeatureReadiness.read(context).ready
        val fires = if (ready) ScheduleCalculator.nextFires(now, WorkDaysStore(context).restrict(settings), horizonDays = 7) else emptyMap()
        // LEGACY_KEY_AUTOFILL tidak pernah dijadwalkan lagi; tetap di daftar agar
        // alarm warisan 21:00 dari versi lama ikut dibatalkan.
        for (key in listOf(ScheduleCalculator.KEY_REMINDER, LEGACY_KEY_AUTOFILL, ScheduleCalculator.KEY_ESCALATION)) {
            if (key in fires) continue
            val intent = Intent(context, receiverFor(key)).apply { action = actionFor(key) }
            PendingIntent.getBroadcast(
                context,
                requestCodeFor(key),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { pending ->
                am.cancel(pending)
                pending.cancel()
            }
        }
        if (!ready || !settings.masterEnabled || !settings.escalationEnabled || !WorkDaysStore(context).isWorkday(now.toLocalDate())) {
            for (code in listOf(REQUEST_CODE_RETRY, REQUEST_CODE_TEST)) {
                val intent = Intent(context, AutoFillReceiver::class.java).setAction(AutoFillReceiver.ACTION_AUTOFILL)
                PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                    am.cancel(it)
                    it.cancel()
                }
            }
        }
        if (!ready) {
            lastArmError = "Periksa sesi dan izin aplikasi."
            return ArmReport(emptyList(), listOfNotNull(lastArmError))
        }
        if (fires.isEmpty()) return ArmReport(emptyList(), emptyList())
        val armedKeys = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for ((key, fire) in fires) {
            try {
                val intent = Intent(context, receiverFor(key)).apply {
                    action = actionFor(key)
                    putExtra(EXTRA_FIRE_KEY, key)
                    if (key == ScheduleCalculator.KEY_REMINDER) putExtra("date", fire.toLocalDate().toString())
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    requestCodeFor(key),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val triggerAt = fire.toInstant().toEpochMilli()
                if (exactAllowed) {
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showApp(context)), pending)
                } else {
                    am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 10 * 60 * 1000L, pending)
                }
                armedKeys.add(key)
            } catch (e: Exception) {
                // OEM tertentu membatasi exact alarm; jangan diam saat money-critical.
                Log.e(TAG, "setAlarmClock($key) ditolak sistem", e)
                lastArmError = "$key: ${e.javaClass.simpleName}: ${e.message ?: "tanpa pesan"}"
                errors.add(lastArmError ?: "kesalahan tak dikenal")
            }
        }
        if (!exactAllowed && errors.isEmpty()) {
            lastArmError = "izin exact alarm belum diberikan; alarm memakai mode toleran (bisa telat beberapa menit). Berikan izin alarm di pengaturan untuk ketepatan penuh."
        }
        return ArmReport(armedKeys, errors)
    }

    /** Alarm uji cepat: autofill penuh menyala [inSeconds] dari sekarang. */
    @Synchronized
    fun quickFireForTest(context: Context, inSeconds: Int = 90) {
        if (!kotlinx.coroutines.runBlocking { TimeSettingsStore(context).settingsOnce() }.masterEnabled) return
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
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showApp(context)), pending)
    }

    /**
     * Percobaan ulang berikutnya pada [at] (tangga +5/+20/+60 dari ScheduleCalculator).
     * requestCode tetap: percobaan berikutnya menggantikan yang lama.
     */
    @Synchronized
    fun scheduleRetry(context: Context, at: ZonedDateTime) {
        val settings = kotlinx.coroutines.runBlocking { TimeSettingsStore(context).settingsOnce() }
        if (!settings.masterEnabled) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, AutoFillReceiver::class.java)
            .setAction(AutoFillReceiver.ACTION_AUTOFILL)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_RETRY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at.toInstant().toEpochMilli(), showApp(context)), pending)
    }

    // ------------------------------------------------------------- internal

    /** The next configured feature is independent of alarms owned by other applications. */
    fun ensureArmed(context: Context, settings: ScheduleSettings): ArmedState {
        val report = armAll(context, settings)
        val state = readArmedState(context, settings)
        return state.copy(
            armed = report.armedKeys.isNotEmpty() && report.errors.isEmpty(),
            lastError = report.errors.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: lastArmError,
        )
    }

    fun readArmedState(
        context: Context,
        settings: ScheduleSettings,
        now: ZonedDateTime = ZonedDateTime.now(ScheduleCalculator.asiaJakarta),
    ): ArmedState {
        if (!settings.masterEnabled) return ArmedState(false, null, "Semua fitur nonaktif")
        if (WorkDaysStore(context).days() == null) return ArmedState(false, null, "Hari kerja belum dimuat. Periksa sesi.")
        val next = ScheduleCalculator.nextFires(now.withZoneSameInstant(ScheduleCalculator.asiaJakarta), WorkDaysStore(context).restrict(settings))
            .entries.minByOrNull { it.value.toInstant() }
            ?: return ArmedState(false, null, "Tidak ada jadwal aktif")
        return ArmedState(false, next.value.toInstant().toString(), featureLabel(next.key))
    }

    private fun featureLabel(key: String): String = when (key) {
        ScheduleCalculator.KEY_REMINDER -> "Pengingat"
        else -> "Pengisian darurat"
    }

    private fun showApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, id.ohmymonev.android.MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    data class ArmedState(
        val armed: Boolean,
        val nextFireISO: String?,
        val source: String,
        val lastError: String? = null,
    )

    @Volatile
    var lastArmError: String? = null
        private set

    private const val EXTRA_FIRE_KEY = "fire_key"

    private fun receiverFor(key: String): Class<out BroadcastReceiver> = when (key) {
        ScheduleCalculator.KEY_REMINDER -> ReminderReceiver::class.java
        LEGACY_KEY_AUTOFILL -> AutoFillReceiver::class.java
        ScheduleCalculator.KEY_ESCALATION -> EscalationReceiver::class.java
        else -> throw IllegalArgumentException("kunci alarm tak dikenal: $key")
    }

    private fun actionFor(key: String): String = when (key) {
        ScheduleCalculator.KEY_REMINDER -> ReminderReceiver.ACTION_REMINDER
        LEGACY_KEY_AUTOFILL -> AutoFillReceiver.ACTION_AUTOFILL
        ScheduleCalculator.KEY_ESCALATION -> EscalationReceiver.ACTION_ESCALATION
        else -> throw IllegalArgumentException("kunci alarm tak dikenal: $key")
    }

    private fun requestCodeFor(key: String): Int = when (key) {
        ScheduleCalculator.KEY_REMINDER -> REQUEST_CODE_REMINDER
        LEGACY_KEY_AUTOFILL -> REQUEST_CODE_AUTOFILL
        ScheduleCalculator.KEY_ESCALATION -> REQUEST_CODE_ESCALATION
        else -> throw IllegalArgumentException("kunci alarm tak dikenal: $key")
    }

    private const val REQUEST_CODE_REMINDER = 11
    private const val REQUEST_CODE_AUTOFILL = 12
    private const val REQUEST_CODE_ESCALATION = 13
    private const val REQUEST_CODE_RETRY = 21
    private const val REQUEST_CODE_TEST = 99

    private const val LEGACY_KEY_AUTOFILL = "autofill"
}

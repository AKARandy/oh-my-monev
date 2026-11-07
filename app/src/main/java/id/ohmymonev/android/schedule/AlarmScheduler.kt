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
 * Menjaga ketiga alarm malam tetap terpasang: aplikasi direstart/berhenti?
 * BootReceiver + PackageReplacedReceiver memanggil [armAll].
 * Menggunakan AlarmManager.setAlarmClock (AlarmClockInfo) — tahan Doze serta
 * bebas izin SCHEDULE_EXACT_ALARM, dan menampilkan ikon alarm di status bar
 * (kejujuran bagi pengguna; malam ini money-critical: D4).
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    /** requestCode tetap tiap alarm supaya setAlarmClock menggantikan alarm lama. */
    fun armAll(context: Context, settings: ScheduleSettings): ArmReport {
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
        val fires = ScheduleCalculator.nextFires(now, settings, horizonDays = 7)
        for (key in listOf(ScheduleCalculator.KEY_REMINDER, ScheduleCalculator.KEY_AUTOFILL, ScheduleCalculator.KEY_ESCALATION)) {
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
        if (fires.isEmpty()) {
            return ArmReport(emptyList(), listOf("tidak ada jadwal aktif (otomatis nonaktif atau tidak ada hari aktif)"))
        }
        val armedKeys = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for ((key, fire) in fires) {
            try {
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
                val triggerAt = fire.toInstant().toEpochMilli()
                if (exactAllowed) {
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pending), pending)
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
    fun scheduleRetry(context: Context, at: ZonedDateTime, lastChance: Boolean = false) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, AutoFillReceiver::class.java)
            .setAction(AutoFillReceiver.ACTION_AUTOFILL)
            .putExtra(AutoFillService.EXTRA_LAST_CHANCE, lastChance)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_RETRY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at.toInstant().toEpochMilli(), pending), pending)
    }

    // ------------------------------------------------------------- internal

    /**
     * Perbaiki lalu buktikan: pasang ulang alarm sesuai [settings], lalu baca keadaan dari OS.
     * Dipakai oleh layar Beranda agar "Periksa" tidak pernah membaca keadaan sebelum perbaikan selesai.
     */
    fun ensureArmed(context: Context, settings: ScheduleSettings): ArmedState {
        val report = if (settings.masterEnabled) armAll(context, settings) else null
        val state = readArmedState(context, settings)
        val errors = report?.errors.orEmpty()
        val emptySchedule = report != null && report.armedKeys.isEmpty() && errors.isEmpty()
        return when {
            state.armed -> state
            errors.isNotEmpty() -> state.copy(lastError = errors.joinToString("; "))
            emptySchedule -> state.copy(lastError = "tidak ada jadwal yang bisa dipasang")
            else -> state
        }
    }

    /**
     * Bukti dari sistem, bukan klaim: baca alarm berikutnya yang benar-benar terdaftar di OS.
     * Null = tidak ada alarm terpasang (berarti otomatis nonaktif / OS menghapusnya).
     */
    fun readArmedState(context: Context, settings: ScheduleSettings): ArmedState {
        val am = context.getSystemService(AlarmManager::class.java)
            ?: return ArmedState(armed = false, nextFireISO = null, source = "AlarmManager tidak tersedia")
        // nextAlarmClock bersifat global: alarm jam bawaan bisa menutupi milik aplikasi.
        // Karena itu verifikasi kedua: cek PendingIntent milik kita masih terdaftar.
        val next = am.nextAlarmClock
        val oursIsNext = next != null && next.showIntent != null && next.triggerTime > 0
        if (next == null) {
            val reason = if (!settings.masterEnabled) "otomatis nonaktif" else "belum terbaca di OS"
            return ArmedState(armed = false, nextFireISO = null, source = reason, lastError = lastArmError)
        }
        val whenISO = java.time.Instant.ofEpochMilli(next.triggerTime).toString()
        val now = ZonedDateTime.now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta)
        val fires = id.ohmymonev.core.schedule.ScheduleCalculator.nextFires(now, settings, horizonDays = 7)
        val matched = fires.entries.minByOrNull { it.value.toInstant().toEpochMilli() }
        val label = if (matched != null &&
            kotlin.math.abs(matched.value.toInstant().toEpochMilli() - next.triggerTime) < 120_000
        ) {
            matched.key
        } else {
            "lain (kemungkinan percobaan ulang)"
        }
        return ArmedState(armed = true, nextFireISO = whenISO, source = label)
    }

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

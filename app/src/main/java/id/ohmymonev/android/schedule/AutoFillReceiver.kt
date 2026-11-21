package id.ohmymonev.android.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.ensureActive

/**
 * Penerima alarm isi: coba ulang (+5/+20/+60) dan alarm uji cepat.
 * Tipis: cek hari kerja, master, dan jeda malam ini, lalu start AutoFillService.
 */
class AutoFillReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReceiverWork.launch(this) { handle(context, intent) }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        id.ohmymonev.android.data.ActionLog.record(context, "alarm.received", "AutoFillReceiver")
        if (intent.action == AutoFillService.ACTION_LOCATION_PROBE && id.ohmymonev.android.BuildConfig.DEBUG) {
            run {
                try { context.startForegroundService(Intent(context, AutoFillService::class.java).setAction(intent.action)) }
                catch (_: RuntimeException) { id.ohmymonev.android.data.ActionLog.record(context, "location.probe.failed", "background start denied") }
            }
            return
        }
        val settings = TimeSettingsStore(context).settingsOnce()
        kotlin.coroutines.coroutineContext.ensureActive()
        id.ohmymonev.android.data.SessionRepository.get(context).awaitLoaded()
        kotlin.coroutines.coroutineContext.ensureActive()
        if (!FeatureReadiness.read(context).ready) {
            AlarmScheduler.armAll(context, settings)
            return
        }
        val today = java.time.LocalDate.now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta)
        if (!WorkDaysStore(context).isWorkday(today)) return
        if (!settings.masterEnabled || !settings.escalationEnabled) return
        // Pengguna membatalkan malam ini: hormati, jangan jalankan apa pun.
        if (id.ohmymonev.android.data.AppStore(context).isSkipped(today.toString())) return
        kotlin.coroutines.coroutineContext.ensureActive()
        AutoFillService.start(context)
    }

    companion object {
        const val ACTION_AUTOFILL = "id.ohmymonev.android.schedule.ACTION_AUTOFILL"
    }
}

package id.ohmymonev.android.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.ensureActive

/**
 * Receiver umum untuk pemicu sistem (boot / paket diganti):
 * baca pengaturan lalu senjatakan ulang ketiga alarm.
 * Keduanya protected system broadcast — aman dengan exported=false.
 */
abstract class ArmAlarmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recover = { AlarmRearmWork.enqueue(context) }
        ReceiverWork.launch(this, onTimeout = recover, onFailure = recover) {
            val settings = TimeSettingsStore(context).settingsOnce()
            ensureActive()
            val report = AlarmScheduler.armAll(context, settings)
            if (report.errors.isNotEmpty()) recover()
            if (settings.masterEnabled && settings.reminderEnabled && ReminderController.hasPending(context)) {
                GuardService.tryEnsure(context)
            }
        }
    }
}

class BootReceiver : ArmAlarmsReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        super.onReceive(context, intent)
    }
}

/** MY_PACKAGE_REPLACED bukan izin — hanya intent-filter setelah update APK. */
class PackageReplacedReceiver : ArmAlarmsReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        super.onReceive(context, intent)
    }
}

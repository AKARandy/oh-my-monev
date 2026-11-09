package id.ohmymonev.android.schedule

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import id.ohmymonev.android.data.SessionRepository

internal data class FeatureReadiness(val session: Boolean, val location: Boolean, val notifications: Boolean) {
    val ready get() = session && location && notifications

    fun missing(): String = listOfNotNull(
        "Login ke Monev".takeUnless { session },
        "Izinkan lokasi akurat".takeUnless { location },
        "Aktifkan notifikasi".takeUnless { notifications },
    ).joinToString(" · ")

    companion object {
        fun read(context: Context, session: Boolean = SessionRepository.get(context).state.value.usable) =
            FeatureReadiness(
                session,
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                NotificationManagerCompat.from(context).areNotificationsEnabled(),
            )

        suspend fun rearm(context: Context) {
            val settings = TimeSettingsStore(context).settingsOnce()
            AlarmScheduler.armAll(context, settings)
            if (read(context).ready) {
                if (settings.masterEnabled) GuardService.tryEnsure(context)
            } else if (!SessionRepository.get(context).state.value.initializing) {
                ReminderController.clear(context)
                context.stopService(android.content.Intent(context, GuardService::class.java))
            }
        }
    }
}

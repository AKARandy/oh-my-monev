package id.ohmymonev.android

import android.app.Application
import id.ohmymonev.android.engine.EngineAutoFillDelegate
import id.ohmymonev.android.schedule.AlarmScheduler
import id.ohmymonev.android.schedule.AutoFillDelegateProvider
import id.ohmymonev.android.schedule.GuardService
import id.ohmymonev.android.schedule.TimeSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Registers the WebView engine as the autofill delegate, re-arms alarms, and starts the guard service. */
class OhMyMonevApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AutoFillDelegateProvider.delegate = EngineAutoFillDelegate(applicationContext)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val settings = TimeSettingsStore(applicationContext).settingsOnce()
            AlarmScheduler.armAll(applicationContext, settings)
            if (settings.masterEnabled) {
                GuardService.ensure(applicationContext)
            }
        }
    }
}

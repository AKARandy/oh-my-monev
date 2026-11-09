package id.ohmymonev.android

import android.app.Application
import id.ohmymonev.android.engine.EngineAutoFillDelegate
import id.ohmymonev.android.schedule.AutoFillDelegateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.android.schedule.FeatureReadiness

/** Registers the WebView engine as the autofill delegate, re-arms alarms, and starts the guard service. */
class OhMyMonevApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AutoFillDelegateProvider.delegate = EngineAutoFillDelegate(applicationContext)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            SessionRepository.get(applicationContext).state.map { it.usable }.distinctUntilChanged().collect {
                FeatureReadiness.rearm(applicationContext)
            }
        }
    }
}

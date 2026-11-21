package id.ohmymonev.android

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import id.ohmymonev.android.engine.EngineAutoFillDelegate
import id.ohmymonev.android.schedule.AutoFillDelegateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.android.schedule.FeatureReadiness

/** Registers the WebView engine as the autofill delegate, re-arms alarms, and starts the guard service. */
class OhMyMonevApp : Application() {
    @Volatile private var automationStarted = false

    override fun onCreate() {
        super.onCreate()
        ensureAutomationStarted()
    }

    internal fun ensureAutomationStarted(fromMainActivity: Boolean = false) {
        if (automationStarted || !ProcessIdentity.isMain(ProcessIdentity.current(this), packageName, fromMainActivity)) return
        synchronized(this) {
            if (automationStarted || !ProcessIdentity.isMain(ProcessIdentity.current(this), packageName, fromMainActivity)) return
            if (!WorkManager.isInitialized()) {
                WorkManager.initialize(this, Configuration.Builder().build())
            }
            if (BuildConfig.DEBUG) id.ohmymonev.android.diagnostics.StallWatchdog.start(this)
            id.ohmymonev.android.data.ActionLog.record(this, "process.start")
            AutoFillDelegateProvider.delegate = EngineAutoFillDelegate(applicationContext)
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                SessionRepository.get(applicationContext).state
                    .filter { !it.initializing }
                    .map { it.usable }
                    .distinctUntilChanged()
                    .collect {
                        id.ohmymonev.android.data.ActionLog.record(applicationContext, "session.readiness", "usable=$it")
                        FeatureReadiness.rearm(applicationContext)
                    }
            }
            automationStarted = true
        }
    }
}

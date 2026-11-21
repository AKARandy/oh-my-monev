package id.ohmymonev.android.data

import android.content.Context
import id.ohmymonev.android.engine.PreparedReportService
import id.ohmymonev.android.schedule.AlarmScheduler
import id.ohmymonev.android.schedule.FeatureReadiness
import id.ohmymonev.android.schedule.TimeSettingsStore
import id.ohmymonev.core.schedule.ScheduleSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ModelTransferState(
    val busy: Boolean = false,
    val progress: Int? = null,
    val error: String? = null,
    val deleteError: String? = null,
)

/** Process-scoped model download/delete coordinator surviving tab switches and rotations. */
object ModelTransferController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ModelTransferState())
    val state: StateFlow<ModelTransferState> = _state.asStateFlow()

    @Volatile
    private var activeJob: Job? = null

    fun startDownload(context: Context, onDone: (Boolean) -> Unit = {}) {
        if (_state.value.busy) return
        val appContext = context.applicationContext
        _state.update { it.copy(busy = true, progress = 0, error = null, deleteError = null) }
        lateinit var job: Job
        job = scope.launch {
            var succeeded = false
            try {
                if (!FeatureReadiness.read(appContext).ready) return@launch
                ModelStore(appContext).download { percent ->
                    _state.update { current ->
                        if (current.busy) current.copy(progress = percent) else current
                    }
                }
                succeeded = true
            } catch (_: CancellationException) {
                // Dibatalkan oleh pengguna.
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Unduhan gagal. Coba lagi.") }
            } finally {
                if (activeJob === job) activeJob = null
                _state.update { it.copy(busy = false, progress = null) }
            }
            if (succeeded) {
                withContext(Dispatchers.Main) { onDone(true) }
            }
        }
        activeJob = job
    }

    fun cancelDownload() {
        val job = activeJob ?: return
        activeJob = null
        job.cancel()
        _state.update { it.copy(busy = false, progress = null, error = null) }
    }

    fun startDelete(
        context: Context,
        saveMutex: Mutex,
        onDone: (present: Boolean, updatedSettings: ScheduleSettings?) -> Unit = { _, _ -> },
    ) {
        if (_state.value.busy) return
        val appContext = context.applicationContext
        _state.update { it.copy(busy = true, deleteError = null) }
        lateinit var job: Job
        job = scope.launch {
            var updatedSettings: ScheduleSettings? = null
            try {
                if (!FeatureReadiness.read(appContext).ready) return@launch
                val store = TimeSettingsStore(appContext)
                PreparedReportService(appContext).deleteAutomaticModel {
                    saveMutex.withLock {
                        val latest = store.settingsOnce()
                        if (latest.escalationSource == ScheduleSettings.ContentSource.AUTOMATIC) {
                            val disabled = latest.copy(escalationEnabled = false)
                            store.save(disabled)
                            AlarmScheduler.armAll(appContext, disabled)
                            updatedSettings = disabled
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(deleteError = e.message ?: "Model tidak dapat dihapus.") }
            } finally {
                if (activeJob === job) activeJob = null
                val present = ModelStore(appContext).present()
                _state.update { it.copy(busy = false) }
                withContext(Dispatchers.Main) { onDone(present, updatedSettings) }
            }
        }
        activeJob = job
    }
}

package id.ohmymonev.android.data

import android.content.Context
import android.widget.Toast
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

sealed interface ModelTransferPhase {
    data object Idle : ModelTransferPhase
    data class Downloading(val percent: Int) : ModelTransferPhase
    data object Deleting : ModelTransferPhase
    data class Failed(val message: String, val isDelete: Boolean) : ModelTransferPhase

    val busy: Boolean get() = this is Downloading || this is Deleting
    val progress: Int? get() = (this as? Downloading)?.percent
    val error: String? get() = (this as? Failed)?.takeUnless { it.isDelete }?.message
    val deleteError: String? get() = (this as? Failed)?.takeIf { it.isDelete }?.message
}

/** Process-scoped model download/delete coordinator surviving tab switches and rotations. */
object ModelTransferController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ModelTransferPhase>(ModelTransferPhase.Idle)
    val state: StateFlow<ModelTransferPhase> = _state.asStateFlow()

    @Volatile
    private var activeJob: Job? = null

    fun startDownload(context: Context) {
        if (_state.value.busy) return
        val appContext = context.applicationContext
        _state.value = ModelTransferPhase.Downloading(0)
        lateinit var job: Job
        job = scope.launch {
            var succeeded = false
            try {
                if (!FeatureReadiness.read(appContext).ready) return@launch
                ModelStore(appContext).download { percent ->
                    _state.update { current ->
                        if (current is ModelTransferPhase.Downloading) ModelTransferPhase.Downloading(percent) else current
                    }
                }
                succeeded = true
            } catch (_: CancellationException) {
                // Dibatalkan oleh pengguna.
            } catch (e: Exception) {
                _state.value = ModelTransferPhase.Failed(e.message ?: "Unduhan gagal. Coba lagi.", isDelete = false)
            } finally {
                if (activeJob === job) activeJob = null
                if (succeeded) {
                    _state.value = ModelTransferPhase.Idle
                } else {
                    _state.update { current ->
                        if (current is ModelTransferPhase.Downloading) ModelTransferPhase.Idle else current
                    }
                }
            }
            if (succeeded) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "File mode otomatis terunduh", Toast.LENGTH_SHORT).show()
                }
            }
        }
        activeJob = job
    }

    fun cancelDownload() {
        val job = activeJob ?: return
        activeJob = null
        job.cancel()
        _state.value = ModelTransferPhase.Idle
    }

    fun startDelete(
        context: Context,
        saveMutex: Mutex,
    ) {
        if (_state.value.busy) return
        val appContext = context.applicationContext
        _state.value = ModelTransferPhase.Deleting
        lateinit var job: Job
        job = scope.launch {
            var failed = false
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
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                _state.value = ModelTransferPhase.Failed(e.message ?: "Model tidak dapat dihapus.", isDelete = true)
            } finally {
                if (activeJob === job) activeJob = null
                if (!failed) {
                    _state.update { current ->
                        if (current is ModelTransferPhase.Deleting) ModelTransferPhase.Idle else current
                    }
                }
            }
        }
        activeJob = job
    }
}

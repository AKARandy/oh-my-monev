package id.ohmymonev.android.schedule

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import id.ohmymonev.android.data.StoredDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ManualReportRequest(val draft: StoredDraft, val account: String, val sessionVersion: Long)

data class ManualReportState(val request: ManualReportRequest, val message: String? = null) {
    val sending: Boolean get() = message == null
}

/**
 * Layanan layar depan tipe dataSync yang satu-satunya tugasnya menjalankan
 * [AutoFillDelegate] (mesin WebView milik lead) sampai autofill selesai.
 * TODO(lead): panggil AutoFillService.finished(context) saat mesin selesai/gagal.
 */
class AutoFillService : Service() {

    private var running = false
    private var manualRequest: ManualReportRequest? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = if (intent?.action == ACTION_MANUAL) manual.value?.takeIf { it.sending }?.request else null
        if (running) {
            if (request != null && manualRequest == null) manualFinished(request, "Pengisian sedang berjalan. Cek ulang status laporan.")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MANUAL && request == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        manualRequest = request
        val factory = NotificationFactory(this)
        factory.ensureChannels()
        val notification: Notification = factory.autofillProgress()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationFactory.NOTIF_ID_AUTOFILL,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationFactory.NOTIF_ID_AUTOFILL, notification)
        }

        val delegate = AutoFillDelegateProvider.delegate
        if (delegate == null) {
            // Mesin belum terpasang: jangan diam saja saat malam money-critical.
            val factory = NotificationFactory(this)
            if (factory.canPostNotifications()) {
                NotificationManagerCompat.from(this).notify(
                    NotificationFactory.NOTIF_ID_REMINDER,
                    factory.info(
                        "oh-my-monev: pengisi otomatis belum siap",
                        "Isi manual di situs supaya stipend hari ini aman.",
                    ),
                )
            }
            if (request != null) manualFinished(request, "Pengiriman belum siap.")
            stopSelf()
        } else if (request != null) {
            delegate.startManual(request)
        } else {
            id.ohmymonev.android.engine.DebugBench.pending = intent?.action == ACTION_BENCH
            delegate.startAutoFill(applicationContext)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        manualRequest?.let { manualFinished(it, "Pengiriman terhenti. Cek ulang status laporan.") }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_BENCH = "id.ohmymonev.android.BENCH"
        private const val ACTION_MANUAL = "id.ohmymonev.android.MANUAL_REPORT"
        private val mutableManual = MutableStateFlow<ManualReportState?>(null)
        val manual = mutableManual.asStateFlow()

        fun startManual(context: Context, request: ManualReportRequest) {
            if (manual.value?.sending == true) return
            mutableManual.value = ManualReportState(request)
            try {
                context.startForegroundService(Intent(context, AutoFillService::class.java).setAction(ACTION_MANUAL))
            } catch (_: IllegalStateException) {
                manualFinished(request, "Pengiriman tidak dapat dimulai. Coba lagi.")
            } catch (_: SecurityException) {
                manualFinished(request, "Pengiriman tidak dapat dimulai. Periksa izin aplikasi.")
            }
        }

        fun manualFinished(request: ManualReportRequest, message: String) {
            val current = mutableManual.value
            if (current?.request === request && current.sending) {
                mutableManual.compareAndSet(current, ManualReportState(request, message))
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, AutoFillService::class.java)
            context.startForegroundService(intent)
        }

        /** Satu putaran uji mesin terhadap halaman tiruan di dalam aplikasi (debug). */
        fun startBench(context: Context) {
            context.startForegroundService(Intent(context, AutoFillService::class.java).setAction(ACTION_BENCH))
        }

        /** Dipanggil mesin saat satu siklus autofill selesai (sukses maupun gagal). */
        fun finished(context: Context) {
            context.stopService(Intent(context, AutoFillService::class.java))
        }
    }
}

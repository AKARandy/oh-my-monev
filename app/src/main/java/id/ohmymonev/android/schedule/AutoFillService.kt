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

class ManualReportRequest(val draft: StoredDraft, val account: String, val sessionVersion: Long)

data class ManualReportState(
    val request: ManualReportRequest,
    val message: String? = null,
    val outcome: id.ohmymonev.core.enginecore.EngineOutcome? = null,
    val recheckRequired: Boolean = false,
) {
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
    private var probeEngine: id.ohmymonev.android.engine.WebViewEngine? = null
    private val deadlineHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val deadline = Runnable {
        val currentManual = manualRequest
        if (currentManual != null) {
            manualFinished(currentManual, "Batas waktu pengiriman. Cek ulang status laporan.")
        } else {
            val dateIso = java.time.LocalDate.now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta).toString()
            val record = id.ohmymonev.core.enginecore.RunRecord(
                date = dateIso,
                attemptedAtISO = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(java.time.OffsetDateTime.now()),
                outcome = id.ohmymonev.core.enginecore.EngineOutcome.LOADING_TIMEOUT,
                attempt = 1,
                detail = "Batas waktu layanan pengisian tercapai.",
                trigger = "EMERGENCY",
                lastStage = "service_timeout",
                verification = "UNCONFIRMED",
            )
            runCatching { id.ohmymonev.android.data.AppStore(this).appendRecord(record) }
            val factory = NotificationFactory(this)
            factory.ensureChannels()
            if (factory.canPostNotifications()) {
                NotificationManagerCompat.from(this).notify(
                    NotificationFactory.NOTIF_ID_ESCALATION,
                    factory.escalation("Pengisian otomatis terhenti karena batas waktu."),
                )
            }
        }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = if (intent?.action == ACTION_MANUAL) manual.value?.takeIf { it.sending }?.request else null
        if (running) {
            if (request != null && manualRequest == null) manualFinished(request, "Pengisian sedang berjalan. Cek ulang status laporan.")
            return START_NOT_STICKY
        }
        val factory = NotificationFactory(this)
        factory.ensureChannels()
        val notification: Notification = factory.autofillProgress()
        if (intent?.action == ACTION_MANUAL && request == null) {
            runCatching { startAsForeground(factory, notification, includeLocation = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        deadlineHandler.postDelayed(deadline, 185_000)
        manualRequest = request
        val bench = intent?.action == ACTION_BENCH
        val locationManager = getSystemService(android.location.LocationManager::class.java)
        val wantNativeLocation = !bench && FeatureReadiness.read(this).location &&
            (request != null || id.ohmymonev.android.MainActivity.visible || FeatureReadiness.backgroundLocation(this)) &&
            locationManager?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } == true
        try {
            nativeLocationAllowed = startAsForeground(factory, notification, wantNativeLocation)
        } catch (_: RuntimeException) {
            request?.let { manualFinished(it, "Pengiriman tidak dapat dimulai. Periksa izin aplikasi.") }
            id.ohmymonev.android.data.ActionLog.record(this, "service.start.failed", "foreground service start denied")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_LOCATION_PROBE && id.ohmymonev.android.BuildConfig.DEBUG) {
            val engine = id.ohmymonev.android.engine.WebViewEngine(this,
                id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE,
                object : id.ohmymonev.android.engine.WebViewEngine.Callbacks {
                    override fun onEvent(message: String) = Unit
                    override fun onRunFinished(record: id.ohmymonev.core.enginecore.RunRecord) {
                        val result = if (record.outcome == id.ohmymonev.core.enginecore.EngineOutcome.FILL_OK)
                            "Lokasi tersedia\n" + when (record.locationSource) {
                                "IP" -> "Perkiraan lokasi IP"
                                "DEVICE" -> "Lokasi perangkat"
                                else -> "Sumber tidak diketahui"
                            }
                        else record.detail ?: "Lokasi tidak tersedia"
                        id.ohmymonev.android.data.ActionLog.record(this@AutoFillService, "location.probe.complete", result)
                        if (factory.canPostNotifications()) NotificationManagerCompat.from(this@AutoFillService)
                            .notify(NotificationFactory.NOTIF_ID_REMINDER, factory.info("Uji lokasi", result))
                        stopSelf()
                    }
                })
            probeEngine = engine
            engine.runAutomation(java.time.LocalDate.now().toString(), id.ohmymonev.core.model.ReportDraft("", "", ""), 1, locationOnly = true)
            return START_NOT_STICKY
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
        nativeLocationAllowed = false
        deadlineHandler.removeCallbacks(deadline)
        probeEngine?.cancel()
        AutoFillDelegateProvider.delegate?.cancel()
        id.ohmymonev.android.data.ActionLog.record(this, "service.stop")
        manualRequest?.let { manualFinished(it, "Pengiriman terhenti. Cek ulang status laporan.") }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(factory: NotificationFactory, notification: Notification, includeLocation: Boolean): Boolean {
        var nativeLocation = includeLocation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationFactory.NOTIF_ID_AUTOFILL,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            if (nativeLocation) {
                try {
                    ServiceCompat.startForeground(
                        this,
                        NotificationFactory.NOTIF_ID_AUTOFILL,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                    )
                } catch (_: SecurityException) {
                    nativeLocation = false
                }
            }
        } else {
            startForeground(NotificationFactory.NOTIF_ID_AUTOFILL, notification)
        }
        return nativeLocation
    }

    companion object {
        @Volatile var nativeLocationAllowed: Boolean = false
            private set
        const val ACTION_LOCATION_PROBE = "id.ohmymonev.android.LOCATION_PROBE"
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

        fun manualFinished(
            request: ManualReportRequest,
            message: String,
            outcome: id.ohmymonev.core.enginecore.EngineOutcome? = null,
        ) {
            val current = mutableManual.value
            if (current?.request === request && current.sending) {
                mutableManual.compareAndSet(current, ManualReportState(request, message, outcome))
            }
        }

        fun requireManualRecheck(request: ManualReportRequest, required: Boolean) {
            val current = mutableManual.value ?: return
            if (current.request === request && !current.sending && current.recheckRequired != required) {
                mutableManual.compareAndSet(current, current.copy(recheckRequired = required))
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, AutoFillService::class.java)
            try { context.startForegroundService(intent) }
            catch (_: RuntimeException) {
                id.ohmymonev.android.data.ActionLog.record(context, "service.start.failed", "background start denied")
                val factory = NotificationFactory(context)
                factory.ensureChannels()
                if (factory.canPostNotifications()) NotificationManagerCompat.from(context).notify(
                    NotificationFactory.NOTIF_ID_REMINDER, factory.info("Pengiriman belum dimulai", "Periksa izin lokasi dan alarm aplikasi."))
            }
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

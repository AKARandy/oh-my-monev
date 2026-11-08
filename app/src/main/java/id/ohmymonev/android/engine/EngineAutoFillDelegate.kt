package id.ohmymonev.android.engine

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.schedule.AlarmScheduler
import id.ohmymonev.android.schedule.AutoFillDelegate
import id.ohmymonev.android.schedule.AutoFillService
import id.ohmymonev.android.schedule.NotificationFactory
import id.ohmymonev.android.schedule.TimeSettingsStore
import id.ohmymonev.core.enginecore.EngineOutcome
import id.ohmymonev.core.enginecore.RunRecord
import id.ohmymonev.core.generator.GenerationOutcome
import id.ohmymonev.core.model.GenerationTier
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.schedule.ScheduleCalculator
import id.ohmymonev.core.schedule.ScheduleSettings
import id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Kombinasi mesin + penyimpanan untuk AutoFillService (plan.md §5).
 * Selalu isi lalu kirim: baca-kembali divalidasi, kotak pernyataan dicentang,
 * lalu tombol Kirim ditekan.
 */
class EngineAutoFillDelegate(context: Context) : AutoFillDelegate {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun startAutoFill(context: Context) {
        val factory = NotificationFactory(appContext)
        factory.ensureChannels()
        scope.launch { runCycle(factory) }
    }

    private suspend fun runCycle(factory: NotificationFactory) {
        val store = AppStore(appContext)
        val settings = TimeSettingsStore(appContext).settingsOnce()
        val today = LocalDate.now(ScheduleCalculator.asiaJakarta)
        val todayIso = today.toString()

        // Tombol debug: satu putaran terhadap halaman tiruan untuk membuktikan mesin isi+kirim.
        if (DebugBench.consume()) {
            runBench(factory, settings, store, todayIso)
            return
        }

        // Kasus malam tanpa internet: berhenti cepat, catat, dan andalkan tangga coba ulang.
        if (!NetworkStatus.isOnline(appContext)) {
            Log.d("ohmymonev", "TIDAK ADA INTERNET: putaran dibatalkan")
            val record = RunRecord(
                date = todayIso,
                attemptedAtISO = nowIso(),
                outcome = EngineOutcome.RETRYABLE_ERROR,
                attempt = attemptToday(store, todayIso),
                detail = "tidak ada internet",
            )
            store.appendRecord(record)
            post(
                factory,
                NotificationFactory.NOTIF_ID_REMINDER,
                factory.info(
                    "oh-my-monev: tidak ada internet",
                    "Laporan belum bisa diisi sekarang. Akan dicoba lagi otomatis.",
                ),
            )
            maybeScheduleRetry(record, store, settings)
            AutoFillService.finished(appContext)
            return
        }

        val status = SiteStatusProbe(appContext).check()
        if (status.sessionActive != true || status.today != id.ohmymonev.core.enginecore.TodayState.UNFILLED) {
            val outcome = when {
                status.sessionActive == false -> EngineOutcome.LOGIN_REQUIRED
                status.today == id.ohmymonev.core.enginecore.TodayState.LIBUR ||
                    status.today == id.ohmymonev.core.enginecore.TodayState.LEAVE ||
                    status.today == id.ohmymonev.core.enginecore.TodayState.FILLED -> EngineOutcome.DAY_NOT_FILLABLE
                else -> EngineOutcome.RETRYABLE_ERROR
            }
            val record = RunRecord(todayIso, nowIso(), outcome, attemptToday(store, todayIso),
                detail = status.error ?: "Laporan hari ini tidak perlu diisi.")
            store.appendRecord(record)
            if (outcome == EngineOutcome.RETRYABLE_ERROR) maybeScheduleRetry(record, store, settings)
            if (outcome != EngineOutcome.DAY_NOT_FILLABLE) post(factory, NotificationFactory.NOTIF_ID_REMINDER,
                factory.info("Laporan belum diisi", status.error ?: "Periksa sesi di aplikasi."))
            AutoFillService.finished(appContext)
            return
        }
        val prepared = PreparedReportService(appContext).prepare()
        val outcome = if (prepared.date == todayIso) prepared.outcome() else GenerationOutcome.Failed(
            id.ohmymonev.core.generator.FailureReason.INVALID_REPORT, "Tanggal berubah. Perbarui pratinjau.",
        )

        when (outcome) {
            is GenerationOutcome.Generated -> {
                // Asal isian menentukan notifikasi setelah terkirim: templat minta
                // ditinjau ulang; lanjutan otomatis harus selalu disebut terang-terangan.
                val fromTemplate = outcome.tier == GenerationTier.TEMPLATE && prepared.written == null
                val fromContinuation = outcome.tier == GenerationTier.CONTINUATION_RULES
                val engine = WebViewEngine(appContext, DEFAULT_SITE_PROFILE, runCallbacks(todayIso, store, factory, settings, usedTemplate = fromTemplate, usedContinuation = fromContinuation))
                engine.runAutomation(
                    dateISO = todayIso,
                    draft = outcome.draft,
                    expectedSessionVersion = prepared.sessionVersion,
                    attempt = attemptToday(store, todayIso),
                )
            }

            is GenerationOutcome.Failed -> {
                store.appendRecord(
                    RunRecord(
                        date = todayIso,
                        attemptedAtISO = nowIso(),
                        outcome = EngineOutcome.SUBMIT_FAILED,
                        attempt = 1,
                        detail = "generator: ${outcome.reason.name} (${outcome.detail})",
                    ),
                )
                post(factory, NotificationFactory.NOTIF_ID_ESCALATION, factory.info("Perbaiki laporan hari ini", outcome.detail))
                AutoFillService.finished(appContext)
            }
        }
    }

    /** Uji mesin terhadap halaman tiruan lokal: membuktikan jalur isi, centang, klik kirim. */
    private fun runBench(
        factory: NotificationFactory,
        settings: ScheduleSettings,
        store: AppStore,
        todayIso: String,
    ) {
        Log.d("ohmymonev", "BENCH: mulai putaran uji mesin di halaman contoh")
        val engine = WebViewEngine(
            appContext,
            BenchSite.profile,
            runCallbacks(todayIso, store, factory, settings, bench = true),
        )
        engine.runAutomation(
            dateISO = todayIso,
            draft = BenchSite.draft,
            attempt = 1,
        )
    }

    private fun runCallbacks(
        dateIso: String,
        store: AppStore,
        factory: NotificationFactory,
        settings: ScheduleSettings,
        bench: Boolean = false,
        usedTemplate: Boolean = false,
        usedContinuation: Boolean = false,
    ) = object : WebViewEngine.Callbacks {
        override fun onRunFinished(record: RunRecord) {
            scope.launch {
                store.appendRecord(record)
                if (bench) {
                    // Bukti uji mesin: catat hasil, jangan masukkan ke riwayat dan jangan beri notifikasi.
                    Log.d("ohmymonev", "BENCH selesai: ${record.outcome} (${record.detail})")
                    AutoFillService.finished(appContext)
                    return@launch
                }
                when (record.outcome) {
                    EngineOutcome.SUBMITTED -> {
                        val detail = if (usedContinuation) "Tanggal $dateIso sudah dikirim dan tercatat di situs. Isian memakai lanjutan otomatis."
                        else "Tanggal $dateIso sudah dikirim dan tercatat di situs."
                        post(
                            factory,
                            NotificationFactory.NOTIF_ID_REMINDER,
                            factory.info("oh-my-monev: laporan terkirim", detail),
                        )
                        if (usedTemplate) {
                            post(
                                factory,
                                NotificationFactory.NOTIF_ID_REMINDER,
                                factory.info(
                                    "Templat laporan terpakai",
                                    "Laporan hari ini memakai templat. Ganti templat di tab Jadwal bila perlu.",
                                ),
                            )
                        }
                    }

                    EngineOutcome.DAY_NOT_FILLABLE ->
                        // Hari sudah terisi atau libur: jangan pernah berbunyi "belum terkirim".
                        Log.d("ohmymonev", "Hari ini tidak perlu diisi (sudah terisi atau libur).")

                    else -> {
                        post(factory, NotificationFactory.NOTIF_ID_ESCALATION, factory.escalation())
                        maybeScheduleRetry(record, store, settings)
                    }
                }
                AutoFillService.finished(appContext)
            }
        }

        override fun onEvent(message: String) {
            Log.d("ohmymonev", message)
        }
    }

    /** Menyiapkan tangga coba ulang; penerima memeriksa ulang hari kerja dan jeda saat alarm menyala. */
    private fun maybeScheduleRetry(record: RunRecord, store: AppStore, settings: ScheduleSettings) {
        if (!settings.masterEnabled) return
        if (record.outcome !in RETRYABLE_OUTCOMES) return
        if (attemptToday(store, record.date) >= MAX_ATTEMPTS) {
            Log.w("ohmymonev", "batas percobaan tercapai: ${record.date} tidak dijadwalkan coba lagi")
            return
        }
        val date = runCatching { LocalDate.parse(record.date) }.getOrNull() ?: return
        val deadline = date.atTime(settings.escalationTime).atZone(ScheduleCalculator.asiaJakarta)
        val next = ScheduleCalculator.retryTimes(
            ZonedDateTime.now(ScheduleCalculator.asiaJakarta),
            deadline,
        ).firstOrNull() ?: return
        AlarmScheduler.scheduleRetry(appContext, next)
    }

    private fun attemptToday(store: AppStore, dateIso: String): Int =
        store.records().count { it.date == dateIso } + 1

    private companion object {
        val RETRYABLE_OUTCOMES: Set<EngineOutcome> = setOf(
            EngineOutcome.RETRYABLE_ERROR,
            EngineOutcome.LOADING_TIMEOUT,
            EngineOutcome.LOCATION_CHECK_FAILS,
            EngineOutcome.SUBMIT_FAILED,
        )
        const val MAX_ATTEMPTS = 3
    }

    private fun post(factory: NotificationFactory, id: Int, notification: Notification) {
        if (factory.canPostNotifications()) {
            NotificationManagerCompat.from(appContext).notify(id, notification)
        }
    }

    private fun nowIso(): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now())

}

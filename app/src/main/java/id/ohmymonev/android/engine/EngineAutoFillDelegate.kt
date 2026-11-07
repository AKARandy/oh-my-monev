package id.ohmymonev.android.engine

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.PoolState
import id.ohmymonev.android.data.SeedPack
import id.ohmymonev.android.data.StoredDraft
import id.ohmymonev.android.schedule.AlarmScheduler
import id.ohmymonev.android.schedule.AutoFillDelegate
import id.ohmymonev.android.schedule.AutoFillService
import id.ohmymonev.android.schedule.NotificationFactory
import id.ohmymonev.android.schedule.TimeSettingsStore
import id.ohmymonev.core.enginecore.EngineOutcome
import id.ohmymonev.core.enginecore.RunRecord
import id.ohmymonev.core.generator.DatedReport
import id.ohmymonev.core.generator.GenerationOutcome
import id.ohmymonev.core.generator.GeneratorPipeline
import id.ohmymonev.core.generator.PoolBundle
import id.ohmymonev.core.generator.ReportHistory
import id.ohmymonev.core.generator.Templates
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
 *
 * v0.1 berjalan dalam Mode Uji (D8): `liveSubmit = false` — form terisi namun kotak konfirmasi
 * dan tombol Kirim tidak pernah disentuh. Pengiriman otomatis aktif setelah M0 recon + konfirmasi
 * pengguna pada pengaturan.
 */
class EngineAutoFillDelegate(context: Context) : AutoFillDelegate {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun startAutoFill(context: Context, lastChance: Boolean) {
        val factory = NotificationFactory(appContext)
        factory.ensureChannels()
        scope.launch { runCycle(factory, lastChance) }
    }

    private suspend fun runCycle(factory: NotificationFactory, lastChance: Boolean) {
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
            maybeScheduleRetry(record, store, settings, lastChance)
            AutoFillService.finished(appContext)
            return
        }

        val seed = store.loadSeed()
        val poolState = store.poolState()
        val pools = PoolBundle(seed.pelajaran, seed.kendala, poolState.lastUsed.lastUsedDates())
        // Prioritas: laporan yang ditulis pengguna di aplikasi hari ini, lalu template bawaan.
        val written = store.todayDraft(todayIso)?.toReportDraft()
        Log.d(
            "ohmymonev",
            "MODE tanggal=$todayIso laporan-hari-ini=${written != null} template-seed=${seed.asTemplateOrNull() != null}",
        )
        val templates: Templates? = if (written != null) {
            Templates { written }
        } else {
            seed.asTemplateOrNull()
        }
        val history = ReportHistory { lastBefore(store, today) }

        val outcome = GeneratorPipeline(templates, history, pools).generate(today)

        if (outcome is GenerationOutcome.Generated) {
            store.savePoolState(
                PoolState((pools.pelajaran.snapshot() + pools.kendala.snapshot()).mapValues { it.value.toString() }),
            )
        }

        when (outcome) {
            is GenerationOutcome.Generated -> {
                val engine = WebViewEngine(appContext, DEFAULT_SITE_PROFILE, runCallbacks(todayIso, outcome.draft, store, factory, settings, lastChance = lastChance))
                engine.runAutomation(
                    dateISO = todayIso,
                    draft = outcome.draft,
                    // Setiap fitur memakai persetujuan pengiriman otomatisnya sendiri.
                    liveSubmit = settings.policyFor(lastChance) == ScheduleSettings.Policy.FILL_AND_SUBMIT,
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
                post(factory, NotificationFactory.NOTIF_ID_ESCALATION, factory.escalation())
                AutoFillService.finished(appContext)
            }
        }
    }

    private fun lastBefore(store: AppStore, today: LocalDate): DatedReport? =
        store.drafts()
            .mapNotNull { stored ->
                runCatching { LocalDate.parse(stored.date) }.getOrNull()?.let {
                    DatedReport(it, stored.toReportDraft())
                }
            }
            .filter { it.date.isBefore(today) }
            .maxByOrNull { it.date }

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
            runCallbacks(todayIso, BenchSite.draft, store, factory, settings, bench = true),
        )
        engine.runAutomation(
            dateISO = todayIso,
            draft = BenchSite.draft,
            liveSubmit = true,
            attempt = 1,
        )
    }

    private fun runCallbacks(
        dateIso: String,
        draft: ReportDraft,
        store: AppStore,
        factory: NotificationFactory,
        settings: ScheduleSettings,
        bench: Boolean = false,
        lastChance: Boolean = false,
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
                    EngineOutcome.FILL_OK -> {
                        store.appendDraft(StoredDraft(dateIso, draft.aktivitas, draft.pelajaran, draft.kendala))
                        post(
                            factory,
                            NotificationFactory.NOTIF_ID_REMINDER,
                            factory.info(
                                "oh-my-monev: laporan terisi",
                                "Form terisi dan cocok dengan tulisanmu, belum dikirim. Tinjau lalu kirim manual.",
                            ),
                        )
                    }

                    EngineOutcome.SUBMITTED ->
                        post(
                            factory,
                            NotificationFactory.NOTIF_ID_REMINDER,
                            factory.info(
                                "oh-my-monev: laporan terkirim",
                                "Tanggal $dateIso sudah dikirim dan tercatat di situs.",
                            ),
                        )

                    EngineOutcome.DAY_NOT_FILLABLE ->
                        // Hari sudah terisi atau libur: jangan pernah berbunyi "belum terkirim".
                        Log.d("ohmymonev", "Hari ini tidak perlu diisi (sudah terisi atau libur).")

                    else -> {
                        post(factory, NotificationFactory.NOTIF_ID_ESCALATION, factory.escalation())
                        maybeScheduleRetry(record, store, settings, lastChance)
                    }
                }
                AutoFillService.finished(appContext)
            }
        }

        override fun onEvent(message: String) {
            Log.d("ohmymonev", message)
        }
    }

    /** Menyiapkan tangga coba ulang, hanya bila pengisian otomatis memang menyala. */
    private fun maybeScheduleRetry(record: RunRecord, store: AppStore, settings: ScheduleSettings, lastChance: Boolean) {
        val enabled = if (lastChance) settings.escalationEnabled else settings.autofillEnabled
        val days = if (lastChance) settings.escalationDays else settings.autofillDays
        if (!settings.masterEnabled || !enabled || LocalDate.now(ScheduleCalculator.asiaJakarta).dayOfWeek !in days) return
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
        AlarmScheduler.scheduleRetry(appContext, next, lastChance)
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

    private fun Map<String, String>.lastUsedDates(): Map<String, LocalDate> =
        entries.mapNotNull { (text, iso) ->
            runCatching { text to LocalDate.parse(iso) }.getOrNull()
        }.toMap()
}

private fun SeedPack.asTemplateOrNull(): Templates? {
    val aktivitasTemplate = aktivitasTemplate
    val pelajaranTemplate = pelajaranTemplate
    val kendalaTemplate = kendalaTemplate
    if (aktivitasTemplate.isNullOrBlank() || pelajaranTemplate.isNullOrBlank() || kendalaTemplate.isNullOrBlank()) {
        return null
    }
    return Templates { ReportDraft(aktivitasTemplate, pelajaranTemplate, kendalaTemplate) }
}

private fun StoredDraft.toReportDraft(): ReportDraft = ReportDraft(aktivitas, pelajaran, kendala)

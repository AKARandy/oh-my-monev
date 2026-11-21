package id.ohmymonev.android.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import id.ohmymonev.android.data.ActionLog
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.core.enginecore.EngineOutcome
import id.ohmymonev.core.enginecore.JsSnippets
import id.ohmymonev.core.enginecore.RunRecord
import id.ohmymonev.core.enginecore.SessionClassifier
import id.ohmymonev.core.enginecore.SessionState
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.siteprofile.SiteProfile
import id.ohmymonev.core.validator.DeliveryGate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * UI-automation engine over a short-lived in-app WebView (docs/plan.md D2/D8).
 *
 * State-dumb by design: all decisions are delegated to pure :core logic (SessionClassifier,
 * DeliveryGate, JsSnippets). This class drives the WebView through explicit [EnginePhase]
 * transitions and reports at most one [RunRecord] through [Callbacks.onRunFinished] on the
 * main thread. One WebView per run; destroyed when terminated.
 */
class WebViewEngine(
    context: Context,
    private val profile: SiteProfile,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        /** Called at most once per run, on the main thread, right before the WebView is destroyed. */
        fun onRunFinished(record: RunRecord)

        /** Optional human-readable trace line (Indonesian) for the audit log screen. */
        fun onEvent(message: String)
    }

    internal sealed interface EnginePhase {
        val label: String

        data object PreparingLocation : EnginePhase { override val label = "memeriksa lokasi" }
        data object CheckingSession : EnginePhase { override val label = "membuka Monev" }
        data object OpeningForm : EnginePhase { override val label = "membuka formulir" }
        data object WaitingLocation : EnginePhase { override val label = "memeriksa lokasi" }
        data object Filling : EnginePhase { override val label = "mengisi formulir" }
        data object ReadingBack : EnginePhase { override val label = "memeriksa isian" }
        data object Submitting : EnginePhase { override val label = "menunggu tombol kirim" }
        data object AwaitingConfirmation : EnginePhase { override val label = "memeriksa hasil pengiriman" }
        data class Terminated(val outcome: EngineOutcome) : EnginePhase { override val label = "selesai" }
    }

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active: RunState? = null

    private fun postDelayed(state: RunState, delayMs: Long, action: () -> Unit) {
        androidx.core.os.HandlerCompat.postDelayed(main, action, state, delayMs)
    }

    fun cancel() { main.post { active?.let { finish(it, EngineOutcome.RETRYABLE_ERROR, "Pengiriman terhenti.", notify = false) } } }

    /** Body text of the current main frame, as a single-line JS expression. */
    private val bodyTextJs: String = "(function(){return document.body?document.body.textContent:'';})()"

    /** Count matching form textareas by placeholder, prebuilt once per engine instance. */
    private val textareaCountJs: String = run {
        val placeholders = JSONArray(profile.textareasByPart.values.toList()).toString()
        "(function(){var ph=$placeholders;var t=document.querySelectorAll('textarea');var n=0;" +
            "for(var i=0;i<t.length;i++){var s=t[i].placeholder;if(s&&ph.indexOf(s)!==-1){n++;}}return n;})()"
    }

    /** Mutable per-run bookkeeping; touched only on the main thread. */
    private class RunState(
        val dateISO: String,
        val draftStillCurrent: (() -> Boolean)?,
        val draft: ReportDraft,
        val attempt: Int,
        val attemptedAtISO: String,
        val expectedSessionVersion: Long?,
    ) {
        lateinit var scope: CoroutineScope
        lateinit var webView: WebView
        lateinit var watchdog: Runnable
        val runId = java.util.UUID.randomUUID().toString()
        var phase: EnginePhase = EnginePhase.CheckingSession
        val isTerminated: Boolean get() = phase is EnginePhase.Terminated
        fun viewOrNull(): WebView? = if (::webView.isInitialized) webView else null
        var httpStatusCode: Int = 200
        var mainFrameError: String? = null
        var sessionVersion = 0L
        var location: LocationBridge? = null
        var bootstrap: kotlinx.coroutines.Job? = null
        var locationOnly = false
    }

    fun runAutomation(
        dateISO: String,
        draft: ReportDraft,
        attempt: Int,
        expectedSessionVersion: Long? = null,
        draftStillCurrent: (() -> Boolean)? = null,
        locationOnly: Boolean = false,
    ) {
        val state = RunState(
            dateISO = dateISO,
            draftStillCurrent = draftStillCurrent,
            expectedSessionVersion = expectedSessionVersion,
            draft = draft,
            attempt = attempt,
            attemptedAtISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now()),
        )
        state.scope = CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.Main +
                kotlinx.coroutines.CoroutineExceptionHandler { _, failure ->
                    if (failure !is kotlinx.coroutines.CancellationException) {
                        ActionLog.record(appContext, "send.exception", state.runId)
                        main.post { finish(state, EngineOutcome.RETRYABLE_ERROR, "Pengiriman terhenti. Cek ulang status laporan.") }
                    }
                },
        )
        state.watchdog = Runnable {
            val detail = "Batas waktu saat ${state.phase.label}."
            finish(state, EngineOutcome.LOADING_TIMEOUT, detail)
        }
        state.locationOnly = locationOnly
        active = state
        ActionLog.record(appContext, "send.start", state.runId)
        main.post {
            if (state.isTerminated) return@post
            main.postDelayed(state.watchdog, 90_000)
            try {
                startRun(state)
            } catch (_: RuntimeException) {
                finish(state, EngineOutcome.RETRYABLE_ERROR, "Monev tidak dapat dibuka.")
            }
        }
    }

    private fun transition(state: RunState, expected: EnginePhase, next: EnginePhase): Boolean {
        if (state.phase != expected) return false
        state.phase = next
        ActionLog.record(appContext, "send.stage", "${state.runId} ${next.label}")
        return true
    }

    // ---------------------------------------------------------------- pipeline steps

    @SuppressLint("SetJavaScriptEnabled")
    private fun startRun(state: RunState) {
        val webView = WebView(appContext)
        state.webView = webView
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Situs memverifikasi lokasi lewat navigator.geolocation dan mengirim koordinat bersama
        // laporan; tanpa ini tombol Kirim tetap nonaktif ("Akses lokasi belum diizinkan").
        @Suppress("DEPRECATION")
        settings.setGeolocationEnabled(true)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = buildClient(state)
        webView.webChromeClient = SessionWebView.locationClient(appContext)
        events(state, "Mulai otomatisasi tanggal ${state.dateISO} (percobaan ${state.attempt})")
        if (SessionWebView.trusted(profile.urls.baseUrl)) {
            state.phase = EnginePhase.PreparingLocation
            ActionLog.record(appContext, "send.stage", "${state.runId} ${state.phase.label}")
            val locationEnabled = appContext.getSystemService(android.location.LocationManager::class.java)
                ?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } == true
            ActionLog.record(
                appContext,
                "send.environment",
                "${state.runId} webview=${WebView.getCurrentWebViewPackage()?.versionName} locationEnabled=$locationEnabled background=${id.ohmymonev.android.schedule.FeatureReadiness.backgroundLocation(appContext)}",
            )
            state.bootstrap = state.scope.launch {
                try {
                    val bridge = LocationBridge(
                        appContext,
                        webView,
                        state.runId,
                        deviceAllowed = { id.ohmymonev.android.schedule.AutoFillService.nativeLocationAllowed },
                    )
                    state.location = bridge
                    bridge.install()
                    bridge.prepare()
                    if (state.phase != EnginePhase.PreparingLocation) return@launch
                    if (state.locationOnly) {
                        if (!transition(state, EnginePhase.PreparingLocation, EnginePhase.WaitingLocation)) return@launch
                        webView.loadDataWithBaseURL(
                            profile.urls.baseUrl + "/",
                            "<html><body><script>navigator.geolocation.getCurrentPosition(function(){document.body.textContent='Lokasi berhasil diakses';},function(){document.body.textContent='Lokasi gagal';},{maximumAge:60000,timeout:12000});</script></body></html>",
                            "text/html",
                            "UTF-8",
                            null,
                        )
                        checkLocation(state)
                    } else if (transition(state, EnginePhase.PreparingLocation, EnginePhase.CheckingSession)) {
                        openDashboard(state)
                    }
                } catch (failure: LocationFailure) {
                    ActionLog.record(appContext, "send.location.failed", "${state.runId} reason=${failure.reason}")
                    finish(state, EngineOutcome.LOCATION_CHECK_FAILS, failure.message)
                }
            }
        } else {
            state.phase = EnginePhase.CheckingSession
            ActionLog.record(appContext, "send.stage", "${state.runId} ${state.phase.label}")
            openDashboard(state)
        }
    }

    private fun buildClient(state: RunState) = object : WebViewClient() {
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                ActionLog.record(appContext, "web.load.error", "${state.runId} code=${error.errorCode}")
                state.mainFrameError = "gagal memuat (kesalahan ${error.errorCode})"
                state.httpStatusCode = 0
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) {
                state.httpStatusCode = errorResponse.statusCode
                ActionLog.record(appContext, "web.http.error", "${state.runId} status=${errorResponse.statusCode}")
            }
        }
    }

    /** Loads the dashboard and polls until the loggedInTabs markers appear (session sanity). */
    private fun openDashboard(state: RunState) {
        if (state.phase != EnginePhase.CheckingSession) return
        state.scope.launch {
            val session = SessionRepository.get(appContext)
            val credentials = session.credentials()
            if (state.phase != EnginePhase.CheckingSession) return@launch
            if (state.expectedSessionVersion != null && credentials?.version != state.expectedSessionVersion) {
                finish(state, EngineOutcome.LOGIN_REQUIRED, "Sesi berubah. Perbarui pratinjau sebelum mengisi.")
                return@launch
            }
            state.sessionVersion = credentials?.version ?: session.state.value.version
            SessionWebView.load(state.webView, credentials?.token, profile.dashboardUrl())
            val deadline = SystemClock.elapsedRealtime() + profile.timing.textareaWaitTimeoutMs
            postDelayed(state, profile.timing.spaSettleMs) { waitTabs(state, deadline) }
        }
    }

    private fun waitTabs(state: RunState, deadline: Long) {
        if (state.phase != EnginePhase.CheckingSession) return
        state.webView.evaluateJavascript(bodyTextJs) { raw ->
            if (state.phase != EnginePhase.CheckingSession) return@evaluateJavascript
            val pageText = unwrap(raw)
            if (SessionClassifier.pageHasTabs(pageText, profile.markers.loggedInTabs)) {
                CookieManager.getInstance().flush()
                events(state, "Sesi terdeteksi aktif pada dashboard")
                if (transition(state, EnginePhase.CheckingSession, EnginePhase.OpeningForm)) {
                    openForm(state)
                }
            } else if (SystemClock.elapsedRealtime() >= deadline) {
                finishSessionCheck(state, pageText)
            } else {
                postDelayed(state, profile.timing.pollIntervalMs) { waitTabs(state, deadline) }
            }
        }
    }

    /** Timeout path for the dashboard stage: classify via the pure core rules. */
    private fun finishSessionCheck(state: RunState, pageText: String?) {
        if (state.phase != EnginePhase.CheckingSession) return
        val finalUrl = state.webView.url ?: "about:blank"
        if (state.mainFrameError != null) {
            finish(state, EngineOutcome.RETRYABLE_ERROR, state.mainFrameError)
            return
        }
        when (SessionClassifier.classify(state.httpStatusCode, finalUrl, pageText, profile)) {
            SessionState.EXPIRED ->
                finish(state, EngineOutcome.LOGIN_REQUIRED, "mengarah ke halaman login: $finalUrl")
            else ->
                finish(state, EngineOutcome.LOADING_TIMEOUT, "tab dashboard tidak muncul dalam batas waktu")
        }
    }

    /** Loads the per-date riwayat edit URL and polls for the 3 textareas, matched by placeholder. */
    private fun openForm(state: RunState) {
        if (state.phase != EnginePhase.OpeningForm) return
        state.mainFrameError = null
        state.httpStatusCode = 200
        val deadline = SystemClock.elapsedRealtime() + profile.timing.textareaWaitTimeoutMs
        state.webView.loadUrl(profile.riwayatEditUrl(state.dateISO))
        postDelayed(state, profile.timing.spaSettleMs) { waitTextareas(state, deadline) }
    }

    private fun waitTextareas(state: RunState, deadline: Long) {
        if (state.phase != EnginePhase.OpeningForm) return
        val webView = state.webView
        webView.evaluateJavascript(textareaCountJs) { raw ->
            if (state.phase != EnginePhase.OpeningForm) return@evaluateJavascript
            val count = unwrap(raw)?.toIntOrNull() ?: 0
            if (count >= profile.textareasByPart.size) {
                events(state, "Form laporan terbuka")
                if (transition(state, EnginePhase.OpeningForm, EnginePhase.WaitingLocation)) {
                    checkLocation(state)
                }
            } else if (SystemClock.elapsedRealtime() >= deadline) {
                val finalUrl = webView.url ?: "about:blank"
                val (outcome, detail) = classifyTextareaTimeout(
                    state.httpStatusCode,
                    state.mainFrameError,
                    finalUrl,
                    state.dateISO,
                    profile,
                )
                finish(state, outcome, detail)
            } else {
                postDelayed(state, profile.timing.pollIntervalMs) { waitTextareas(state, deadline) }
            }
        }
    }

    /** The site shows the location OK text above the Kehadiran selector once the check passes. */
    private fun checkLocation(state: RunState) {
        if (state.phase != EnginePhase.WaitingLocation) return
        val deadline = SystemClock.elapsedRealtime() + profile.timing.textareaWaitTimeoutMs
        waitLocation(state, deadline)
    }

    private fun waitLocation(state: RunState, deadline: Long) {
        if (state.phase != EnginePhase.WaitingLocation) return
        state.webView.evaluateJavascript(bodyTextJs) { raw ->
            if (state.phase != EnginePhase.WaitingLocation) return@evaluateJavascript
            val pageText = unwrap(raw)
            ActionLog.record(
                appContext,
                "send.location.check",
                "${state.runId} callback=true success=${pageText?.contains(profile.markers.locationOkText)}",
            )
            when {
                pageText != null && pageText.contains(profile.markers.locationOkText) ->
                    if (state.locationOnly) {
                        finish(state, EngineOutcome.FILL_OK, "Lokasi otomatis tersedia.")
                    } else if (transition(state, EnginePhase.WaitingLocation, EnginePhase.Filling)) {
                        fillForm(state)
                    }
                SystemClock.elapsedRealtime() >= deadline ->
                    finish(state, EngineOutcome.LOCATION_CHECK_FAILS, "tanda '${profile.markers.locationOkText}' tidak ditemukan")
                else -> postDelayed(state, profile.timing.pollIntervalMs) { waitLocation(state, deadline) }
            }
        }
    }

    private fun fillForm(state: RunState): kotlinx.coroutines.Job = state.scope.launch {
        if (state.phase != EnginePhase.Filling) return@launch
        if (!currentDraftScope(state, EnginePhase.Filling)) return@launch
        val js = JsSnippets.fillTextareas(
            profile,
            aktivitas = state.draft.aktivitas,
            pelajaran = state.draft.pelajaran,
            kendala = state.draft.kendala,
        )
        state.webView.evaluateJavascript(js) { raw ->
            if (state.phase != EnginePhase.Filling) return@evaluateJavascript
            val done = unwrapObject(raw)
            if (done?.optBoolean("ok") == true) {
                if (transition(state, EnginePhase.Filling, EnginePhase.ReadingBack)) {
                    readBackAndGate(state)
                }
            } else {
                finish(state, EngineOutcome.RETRYABLE_ERROR, "isi textarea tidak lengkap: ${done ?: "null"}")
            }
        }
    }

    /** Read back what the site actually holds in the 3 textareas, then gate it before touching submit. */
    private fun readBackAndGate(state: RunState) {
        if (state.phase != EnginePhase.ReadingBack) return
        state.webView.evaluateJavascript(JsSnippets.readBack(profile)) { raw ->
            if (state.phase != EnginePhase.ReadingBack) return@evaluateJavascript
            val back = readDraft(unwrapObject(raw))
            if (back == null) {
                finish(state, EngineOutcome.READBACK_MISMATCH, "hasil baca tidak terbaca")
                return@evaluateJavascript
            }
            val mismatchDetail = mismatch(state.draft, back)
            val gate = DeliveryGate().validate(back)
            when {
                mismatchDetail != null -> finish(state, EngineOutcome.READBACK_MISMATCH, mismatchDetail)
                !gate.passed -> finish(state, EngineOutcome.READBACK_MISMATCH, gate.violations.joinToString("; ") { it.id })
                transition(state, EnginePhase.ReadingBack, EnginePhase.Submitting) -> tickAndSubmit(state)
            }
        }
    }

    private suspend fun currentDraftScope(state: RunState, expectedPhase: EnginePhase): Boolean {
        if (state.phase != expectedPhase) return false
        val draftCurrent = kotlinx.coroutines.withContext(Dispatchers.IO) { state.draftStillCurrent?.invoke() != false }
        if (state.phase != expectedPhase) return false
        // Bench runs have no participant-bound draft and stay on the local fixture.
        if (state.expectedSessionVersion == null) return true
        if (!id.ohmymonev.android.schedule.FeatureReadiness.read(appContext).ready) {
            finish(state, EngineOutcome.LOGIN_REQUIRED, "Periksa sesi dan izin aplikasi.")
            return false
        }
        if (java.time.LocalDate.now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta).toString() != state.dateISO) {
            finish(state, EngineOutcome.DAY_NOT_FILLABLE, "Tanggal berubah. Perbarui pratinjau.")
            return false
        }
        if (SessionRepository.get(appContext).state.value.version != state.expectedSessionVersion) {
            finish(state, EngineOutcome.LOGIN_REQUIRED, "Sesi berubah. Perbarui pratinjau.")
            return false
        }
        if (!draftCurrent) {
            finish(state, EngineOutcome.READBACK_MISMATCH, "Draf berubah. Tinjau draf lagi.")
            return false
        }
        return true
    }

    private fun mismatch(intended: ReportDraft, back: ReportDraft): String? {
        val partNames = listOf("aktivitas", "pelajaran", "kendala")
        val a = listOf(intended.aktivitas, intended.pelajaran, intended.kendala)
        val b = listOf(back.aktivitas, back.pelajaran, back.kendala)
        for (i in a.indices) {
            if (a[i].trim() != b[i].trim()) {
                return "${partNames[i]}: nilai di form tidak sama dengan rintisan"
            }
        }
        return null
    }

    /** Tick the confirmation checkbox, then click 'Simpan dan Kirim'. */
    private fun tickAndSubmit(state: RunState): kotlinx.coroutines.Job = state.scope.launch {
        if (!currentDraftScope(state, EnginePhase.Submitting)) return@launch
        state.webView.evaluateJavascript(JsSnippets.tickCheckbox(profile)) { raw ->
            if (state.phase != EnginePhase.Submitting) return@evaluateJavascript
            if (unwrapObject(raw)?.optBoolean("checked") != true) {
                finish(state, EngineOutcome.SUBMIT_FAILED, "kotak konfirmasi tidak ditemukan")
                return@evaluateJavascript
            }
            waitSubmitEnabled(state, SystemClock.elapsedRealtime() + profile.timing.textareaWaitTimeoutMs)
        }
    }

    private fun waitSubmitEnabled(state: RunState, deadline: Long): kotlinx.coroutines.Job = state.scope.launch {
        if (!currentDraftScope(state, EnginePhase.Submitting)) return@launch
        state.webView.evaluateJavascript(JsSnippets.clickSubmit(profile)) { raw ->
            if (state.phase != EnginePhase.Submitting) return@evaluateJavascript
            val result = unwrapObject(raw)
            ActionLog.record(
                appContext,
                "send.button.result",
                "${state.runId} clicked=${result?.optBoolean("clicked")} blocked=${result?.optBoolean("blocked")}",
            )
            when {
                result?.optBoolean("clicked") == true -> {
                    if (transition(state, EnginePhase.Submitting, EnginePhase.AwaitingConfirmation)) {
                        events(state, "Tombol kirim ditekan")
                        waitSubmitLanding(state, SystemClock.elapsedRealtime() + profile.timing.textareaWaitTimeoutMs)
                    }
                }
                result?.optBoolean("blocked") == true && SystemClock.elapsedRealtime() < deadline ->
                    postDelayed(state, profile.timing.pollIntervalMs) { waitSubmitEnabled(state, deadline) }
                else -> finish(
                    state,
                    EngineOutcome.SUBMIT_FAILED,
                    if (result?.optBoolean("blocked") == true) "Tombol kirim Monev belum aktif."
                    else "Tombol kirim Monev tidak dapat ditekan.",
                )
            }
        }
    }

    private fun waitSubmitLanding(state: RunState, deadline: Long) {
        if (state.phase != EnginePhase.AwaitingConfirmation) return
        state.webView.evaluateJavascript(JsSnippets.submissionState()) { raw ->
            if (state.phase != EnginePhase.AwaitingConfirmation) return@evaluateJavascript
            val page = unwrapObject(raw)
            val url = page?.optString("url").orEmpty()
            val text = page?.optString("text").orEmpty()
            val successText = text.contains(profile.markers.submissionSuccessText) ||
                text.contains(profile.markers.submissionUpdatedText)
            when {
                successText -> finish(state, EngineOutcome.SUBMITTED, "pesan sukses situs terlihat")
                url.substringBefore('?').substringBefore('#') == profile.riwayatUrl() && !url.contains("view=edit") ->
                    finish(state, EngineOutcome.SUBMITTED, "form tertutup: $url")
                SystemClock.elapsedRealtime() >= deadline ->
                    finish(state, EngineOutcome.SUBMIT_FAILED, "tidak ada indikasi sukses dari situs")
                else ->
                    postDelayed(state, profile.timing.pollIntervalMs) { waitSubmitLanding(state, deadline) }
            }
        }
    }

    private fun finish(state: RunState, outcome: EngineOutcome, detail: String?, notify: Boolean = true) {
        val priorPhase = state.phase
        if (priorPhase is EnginePhase.Terminated) return
        state.phase = EnginePhase.Terminated(outcome)
        val locationSource = state.location?.lastSource
        val fallbackReason = state.location?.lastFallbackReason
        state.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        if (active === state) active = null
        state.bootstrap?.cancel()
        runCatching { state.location?.close() }
        ActionLog.record(appContext, "send.finish", "${state.runId} outcome=$outcome stage=${priorPhase.label}")
        main.removeCallbacks(state.watchdog)
        main.removeCallbacksAndMessages(state)
        runCatching { CookieManager.getInstance().flush() }
        events(state, "Hasil: $outcome" + (detail?.let { " ($it)" } ?: ""))
        state.viewOrNull()?.let { view ->
            SessionWebView.release(view, SessionRepository.get(appContext), state.sessionVersion, inspectToken = false)
        }
        if (notify) callbacks.onRunFinished(
            RunRecord(
                date = state.dateISO,
                attemptedAtISO = state.attemptedAtISO,
                outcome = outcome,
                attempt = state.attempt,
                detail = detail,
                locationSource = locationSource,
                fallbackReason = fallbackReason,
                lastStage = priorPhase.label,
            ),
        )
    }

    // ---------------------------------------------------------------- small helpers

    private fun events(state: RunState, message: String) = callbacks.onEvent(message)

    private fun readDraft(read: JSONObject?): ReportDraft? {
        val values = read?.optJSONArray("values") ?: return null
        if (values.length() < 3) return null
        // A missed textarea reads back JSON null; treat it as empty so the gate / mismatch logic rejects it.
        fun valueAt(index: Int): String = if (values.isNull(index)) "" else values.getString(index)
        return ReportDraft(
            aktivitas = valueAt(0),
            pelajaran = valueAt(1),
            kendala = valueAt(2),
        )
    }

    /** evaluateJavascript wraps the JS result in a JSON string; unwrap plain values back to their text. */
    private fun unwrap(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val value = JSONTokener(raw).nextValue()
            value.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun unwrapObject(raw: String?): JSONObject? {
        val inner = unwrap(raw) ?: return null
        if (!inner.startsWith("{")) return null
        return try {
            JSONObject(inner)
        } catch (e: Exception) {
            null
        }
    }

    internal companion object {
        fun classifyTextareaTimeout(
            httpStatusCode: Int,
            mainFrameError: String?,
            finalUrl: String,
            dateISO: String,
            profile: SiteProfile,
        ): Pair<EngineOutcome, String?> = when {
            SessionClassifier.classify(httpStatusCode, finalUrl, null, profile) == SessionState.EXPIRED ->
                EngineOutcome.LOGIN_REQUIRED to "sesi berakhir selama menunggu form"
            mainFrameError != null ->
                EngineOutcome.RETRYABLE_ERROR to mainFrameError
            httpStatusCode !in 200..299 ->
                EngineOutcome.RETRYABLE_ERROR to "gagal membuka form (HTTP $httpStatusCode)"
            else ->
                EngineOutcome.DAY_NOT_FILLABLE to "textarea tidak diketemukan untuk tanggal $dateISO"
        }
    }
}

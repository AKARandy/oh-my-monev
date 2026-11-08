package id.ohmymonev.android.engine

import android.annotation.SuppressLint
import id.ohmymonev.android.data.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import id.ohmymonev.core.enginecore.EngineOutcome
import id.ohmymonev.core.enginecore.JsSnippets
import id.ohmymonev.core.enginecore.RunRecord
import id.ohmymonev.core.enginecore.SessionClassifier
import id.ohmymonev.core.enginecore.SessionState
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.siteprofile.SiteProfile
import id.ohmymonev.core.validator.DeliveryGate
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * UI-automation engine over a short-lived in-app WebView (docs/plan.md D2/D8).
 *
 * State-dumb by design: all decisions are delegated to pure :core logic (SessionClassifier,
 * DeliveryGate, JsSnippets). This class only drives the WebView and reports exactly one
 * [RunRecord] through [Callbacks.onRunFinished] — always on the main thread. One WebView per run;
 * it is destroyed when the run finishes. Cookies persist in the OS WebView store across runs.
 */
class WebViewEngine(
    context: Context,
    private val profile: SiteProfile,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        /** Called exactly once per run, on the main thread, right before the WebView is destroyed. */
        fun onRunFinished(record: RunRecord)

        /** Optional human-readable trace line (Indonesian) for the audit log screen. */
        fun onEvent(message: String)
    }

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Body text of the current main frame, as a single-line JS expression. */
    private val bodyTextJs: String = "(function(){return document.body?document.body.textContent:'';})()"

    /** Frame URL of the current main frame, as a single-line JS expression. */
    private val hrefJs: String = "(function(){return window.location.href;})()"

    /** Mutable per-run bookkeeping; touched only on the main thread. */
    private class RunState(
        val dateISO: String,
        val draft: ReportDraft,
        val attempt: Int,
        val attemptedAtISO: String,
        val expectedSessionVersion: Long?,
    ) {
        lateinit var webView: WebView
        var httpStatusCode: Int = 200
        var mainFrameError: String? = null
        var finished = false
        var sessionVersion = 0L
    }

    fun runAutomation(dateISO: String, draft: ReportDraft, attempt: Int, expectedSessionVersion: Long? = null) {
        val state = RunState(
            dateISO = dateISO,
            expectedSessionVersion = expectedSessionVersion,
            draft = draft,
            attempt = attempt,
            attemptedAtISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now()),
        )
        main.post { startRun(state) }
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
        webView.webChromeClient = buildChromeClient()
        events(state, "Mulai otomatisasi tanggal ${state.dateISO} (percobaan ${state.attempt})")
        openDashboard(state)
    }

    /** Situs meminta geolokasi lewat prompt JS; mesin ini sudah memegang izin runtime aplikasi. */
    private fun buildChromeClient() = object : WebChromeClient() {
        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?,
        ) {
            callback?.invoke(origin, true, false)
        }
    }

    private fun buildClient(state: RunState) = object : WebViewClient() {
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                state.mainFrameError = "gagal memuat (kesalahan ${error.errorCode})"
                state.httpStatusCode = 0
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) {
                state.httpStatusCode = errorResponse.statusCode
            }
        }
    }

    /** Loads the dashboard and polls until the loggedInTabs markers appear (session sanity). */
    private fun openDashboard(state: RunState) {
        CoroutineScope(Dispatchers.Main).launch {
            val session = SessionRepository.get(appContext)
            val credentials = session.credentials()
            if (state.finished) return@launch
            if (state.expectedSessionVersion != null && credentials?.version != state.expectedSessionVersion) {
                finish(state, EngineOutcome.LOGIN_REQUIRED, "Sesi berubah. Perbarui pratinjau sebelum mengisi.")
                return@launch
            }
            state.sessionVersion = credentials?.version ?: session.state.value.version
            SessionWebView.load(state.webView, credentials?.token, profile.dashboardUrl())
            val deadline = SystemClock.elapsedRealtime() + profile.timing.textareaWaitTimeoutMs
            main.postDelayed({ waitTabs(state, deadline) }, profile.timing.spaSettleMs)
        }
    }

    private fun waitTabs(state: RunState, deadline: Long) {
        if (state.finished) return
        val webView = state.webView
        webView.evaluateJavascript(bodyTextJs) { raw ->
            if (state.finished) return@evaluateJavascript
            val pageText = unwrap(raw)
            if (SessionClassifier.pageHasTabs(pageText, profile.markers.loggedInTabs)) {
                CookieManager.getInstance().flush()
                events(state, "Sesi terdeteksi aktif pada dashboard")
                openForm(state)
            } else if (SystemClock.elapsedRealtime() >= deadline) {
                finishSessionCheck(state, pageText)
            } else {
                main.postDelayed({ waitTabs(state, deadline) }, profile.timing.pollIntervalMs)
            }
        }
    }

    /** Timeout path for the dashboard stage: classify via the pure core rules. */
    private fun finishSessionCheck(state: RunState, pageText: String?) {
        val webView = state.webView
        val finalUrl = webView.url ?: "about:blank"
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
        val deadline = SystemClock.elapsedRealtime() + profile.timing.textareaWaitTimeoutMs
        state.webView.loadUrl(profile.riwayatEditUrl(state.dateISO))
        main.postDelayed({ waitTextareas(state, deadline) }, profile.timing.spaSettleMs)
    }

    private fun waitTextareas(state: RunState, deadline: Long) {
        if (state.finished) return
        val webView = state.webView
        val placeholders = JSONArray(profile.textareasByPart.values.toList()).toString()
        val countJs = "(function(){var ph=$placeholders;var t=document.querySelectorAll('textarea');var n=0;" +
            "for(var i=0;i<t.length;i++){var s=t[i].placeholder;if(s&&ph.indexOf(s)!==-1){n++;}}return n;})()"
        webView.evaluateJavascript(countJs) { raw ->
            if (state.finished) return@evaluateJavascript
            val count = unwrap(raw)?.toIntOrNull() ?: 0
            if (count >= profile.textareasByPart.size) {
                events(state, "Form laporan terbuka")
                checkLocation(state)
            } else if (SystemClock.elapsedRealtime() >= deadline) {
                val finalUrl = webView.url ?: "about:blank"
                when (SessionClassifier.classify(state.httpStatusCode, finalUrl, null, profile)) {
                    SessionState.EXPIRED ->
                        finish(state, EngineOutcome.LOGIN_REQUIRED, "sesi berakhir selama menunggu form")
                    else ->
                        finish(state, EngineOutcome.DAY_NOT_FILLABLE, "textarea tidak diketemukan untuk tanggal ${state.dateISO}")
                }
            } else {
                main.postDelayed({ waitTextareas(state, deadline) }, profile.timing.pollIntervalMs)
            }
        }
    }

    /** The site shows the location OK text above the Kehadiran selector once the check passes. */
    private fun checkLocation(state: RunState) {
        val deadline = SystemClock.elapsedRealtime() + profile.timing.textareaWaitTimeoutMs
        waitLocation(state, deadline)
    }

    private fun waitLocation(state: RunState, deadline: Long) {
        if (state.finished) return
        state.webView.evaluateJavascript(bodyTextJs) { raw ->
            if (state.finished) return@evaluateJavascript
            val pageText = unwrap(raw)
            when {
                pageText != null && pageText.contains(profile.markers.locationOkText) -> fillForm(state)
                SystemClock.elapsedRealtime() >= deadline ->
                    finish(state, EngineOutcome.LOCATION_CHECK_FAILS, "tanda '${profile.markers.locationOkText}' tidak ditemukan")
                else -> main.postDelayed({ waitLocation(state, deadline) }, profile.timing.pollIntervalMs)
            }
        }
    }

    private fun fillForm(state: RunState) {
        if (!currentDraftScope(state)) return
        val js = JsSnippets.fillTextareas(
            profile,
            aktivitas = state.draft.aktivitas,
            pelajaran = state.draft.pelajaran,
            kendala = state.draft.kendala,
        )
        state.webView.evaluateJavascript(js) { raw ->
            if (state.finished) return@evaluateJavascript
            val done = unwrapObject(raw)
            if (done?.optBoolean("ok") == true) {
                readBackAndGate(state)
            } else {
                finish(state, EngineOutcome.RETRYABLE_ERROR, "isi textarea tidak lengkap: ${done ?: "null"}")
            }
        }
    }

    /** Read back what the site actually holds in the 3 textareas, then gate it before touching submit. */
    private fun readBackAndGate(state: RunState) {
        state.webView.evaluateJavascript(JsSnippets.readBack(profile)) { raw ->
            if (state.finished) return@evaluateJavascript
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
                else -> tickAndSubmit(state)
            }
        }
    }

    private fun currentDraftScope(state: RunState): Boolean {
        // Bench runs have no participant-bound draft and stay on the local fixture.
        if (state.expectedSessionVersion == null) return true
        if (java.time.LocalDate.now(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta).toString() != state.dateISO) {
            finish(state, EngineOutcome.DAY_NOT_FILLABLE, "Tanggal berubah. Perbarui pratinjau.")
            return false
        }
        if (SessionRepository.get(appContext).state.value.version != state.expectedSessionVersion) {
            finish(state, EngineOutcome.LOGIN_REQUIRED, "Sesi berubah. Perbarui pratinjau.")
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
    private fun tickAndSubmit(state: RunState) {
        if (!currentDraftScope(state)) return
        val webView = state.webView
        webView.evaluateJavascript(JsSnippets.tickCheckbox(profile)) { raw ->
            if (state.finished) return@evaluateJavascript
            if (unwrapObject(raw)?.optBoolean("checked") != true) {
                finish(state, EngineOutcome.SUBMIT_FAILED, "kotak konfirmasi tidak ditemukan")
                return@evaluateJavascript
            }
            if (!currentDraftScope(state)) return@evaluateJavascript
            webView.evaluateJavascript(JsSnippets.clickSubmit(profile)) { raw2 ->
                if (state.finished) return@evaluateJavascript
                if (unwrapObject(raw2)?.optBoolean("clicked") != true) {
                    finish(state, EngineOutcome.SUBMIT_FAILED, "tombol kirim tidak ditemukan")
                    return@evaluateJavascript
                }
                events(state, "Terkirim: menunggu halaman keluar dari form")
                waitSubmitLanding(state, SystemClock.elapsedRealtime() + profile.timing.textareaWaitTimeoutMs)
            }
        }
    }

    /** Success marker: the site shows a success snackbar and/or the SPA leaves the ?view=edit form URL. */
    private fun waitSubmitLanding(state: RunState, deadline: Long) {
        if (state.finished) return
        val probeJs = "(function(){return (window.location.href||'')+'\\n'+(document.body?document.body.textContent:'');})()"
        state.webView.evaluateJavascript(probeJs) { raw ->
            if (state.finished) return@evaluateJavascript
            val both = unwrap(raw).orEmpty()
            val url = both.substringBefore('\n')
            val text = both.substringAfter('\n', "")
            val successText = text.contains(profile.markers.submissionSuccessText) ||
                text.contains(profile.markers.submissionUpdatedText)
            when {
                successText -> finish(state, EngineOutcome.SUBMITTED, "pesan sukses situs terlihat")
                url.isNotEmpty() && !url.contains("view=edit") ->
                    finish(state, EngineOutcome.SUBMITTED, "form tertutup: $url")
                SystemClock.elapsedRealtime() >= deadline ->
                    finish(state, EngineOutcome.SUBMIT_FAILED, "tidak ada indikasi sukses dari situs")
                else ->
                    main.postDelayed({ waitSubmitLanding(state, deadline) }, profile.timing.pollIntervalMs)
            }
        }
    }

    private fun finish(state: RunState, outcome: EngineOutcome, detail: String?) {
        if (state.finished) return
        state.finished = true
        CookieManager.getInstance().flush()
        events(state, "Hasil: $outcome" + (detail?.let { " ($it)" } ?: ""))
        val webView = state.webView
        SessionWebView.release(webView, SessionRepository.get(appContext), state.sessionVersion)
        callbacks.onRunFinished(
            RunRecord(
                date = state.dateISO,
                attemptedAtISO = state.attemptedAtISO,
                outcome = outcome,
                attempt = state.attempt,
                detail = detail,
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
}

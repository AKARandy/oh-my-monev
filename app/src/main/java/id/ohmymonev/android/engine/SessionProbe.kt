package id.ohmymonev.android.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import id.ohmymonev.core.enginecore.SessionClassifier
import id.ohmymonev.core.siteprofile.SiteProfile
import org.json.JSONTokener

/**
 * Cek sesi ringan: muat Beranda dashboard, tunggu tab login, laporkan hasil.
 * TIDAK menyentuh form laporan sama sekali — aman dipanggil kapan pun.
 */
class SessionProbe(
    context: Context,
    private val profile: SiteProfile,
    private val onResult: (sessionActive: Boolean, finalUrl: String?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val bodyTextJs = "(function(){return document.body?document.body.textContent:'';})()"

    fun check() {
        main.post { start() }
    }

    private fun start() {
        val webView = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        webView.loadUrl(profile.dashboardUrl())
        val state = ProbeState(webView, SystemClock.elapsedRealtime() + profile.timing.textareaWaitTimeoutMs)
        main.postDelayed({ poll(state) }, profile.timing.spaSettleMs)
    }

    private inner class ProbeState(val webView: WebView, val deadline: Long) {
        var finished = false
    }

    private fun poll(state: ProbeState) {
        if (state.finished) return
        state.webView.evaluateJavascript(bodyTextJs) { raw ->
            if (state.finished) return@evaluateJavascript
            val pageText = unwrap(raw)
            val active = pageText?.let { SessionClassifier.pageHasTabs(it, profile.markers.loggedInTabs) } == true
            when {
                active -> finish(state, true)
                SystemClock.elapsedRealtime() >= state.deadline -> finish(state, false)
                else -> main.postDelayed({ poll(state) }, profile.timing.pollIntervalMs)
            }
        }
    }

    private fun finish(state: ProbeState, active: Boolean) {
        if (state.finished) return
        state.finished = true
        if (active) CookieManager.getInstance().flush()
        val url = state.webView.url
        state.webView.post {
            state.webView.stopLoading()
            state.webView.destroy()
        }
        onResult(active, url)
    }

    /** evaluateJavascript membungkus hasil dalam JSON string; uraikan kembali ke teks. */
    private fun unwrap(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            JSONTokener(raw).nextValue().toString()
        } catch (e: Exception) {
            null
        }
    }
}

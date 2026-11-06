package id.ohmymonev.android.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import id.ohmymonev.core.enginecore.DayEntry
import id.ohmymonev.core.enginecore.DashboardParser
import id.ohmymonev.core.enginecore.TodayState
import id.ohmymonev.core.siteprofile.SiteProfile
import org.json.JSONTokener

/** Hasil pembacaan status dari situs. */
data class SiteStatus(
    val sessionActive: Boolean,
    val today: TodayState,
    val deadline: String?,
    val days: List<DayEntry>,
)

/**
 * Membaca (tanpa menyentuh form sama sekali):
 * 1. Beranda -> status laporan hari ini + batas jam.
 * 2. Riwayat -> status hari-hari sebelumnya.
 * Hasil dipakai layar Beranda untuk menunjukkan kondisi nyata, bukan klaim.
 */
class SiteStatusProbe(
    context: Context,
    private val profile: SiteProfile,
    private val onResult: (SiteStatus) -> Unit,
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
            webViewClient = android.webkit.WebViewClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        readPage(
            webView,
            profile.dashboardUrl(),
            ready = { text ->
                text.contains("Laporan hari ini sudah diisi") ||
                    text.contains("Laporan hari ini belum diisi") ||
                    text.contains("Batas pengisian")
            },
        ) { dashboardText ->
            if (dashboardText == null || !DashboardParser.isLoggedIn(dashboardText)) {
                finish(webView, SiteStatus(false, TodayState.UNKNOWN, null, emptyList()))
                return@readPage
            }
            val today = DashboardParser.todayState(dashboardText)
            val deadline = DashboardParser.deadline(dashboardText)
            readPage(
                webView,
                profile.riwayatUrl(),
                ready = { text ->
                    text.contains("Riwayat Kehadiran") && DashboardParser.days(text).isNotEmpty()
                },
            ) { riwayatText ->
                val days = riwayatText?.let { DashboardParser.days(it) }.orEmpty()
                finish(webView, SiteStatus(true, today, deadline, days))
            }
        }
    }

    private fun readPage(webView: WebView, url: String, ready: (String) -> Boolean, onText: (String?) -> Unit) {
        var finished = false
        var lastText: String? = null
        val deadline = SystemClock.elapsedRealtime() + 45_000
        webView.loadUrl(url)
        fun poll() {
            if (finished) return
            webView.evaluateJavascript(bodyTextJs) { raw ->
                if (finished) return@evaluateJavascript
                val text = unwrap(raw)
                if (text != null) lastText = text
                if (text != null && ready(text)) {
                    finished = true
                    onText(text)
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    finished = true
                    onText(lastText)
                } else {
                    main.postDelayed({ poll() }, profile.timing.pollIntervalMs)
                }
            }
        }
        main.postDelayed({ poll() }, profile.timing.spaSettleMs)
    }

    private fun finish(webView: WebView, status: SiteStatus) {
        webView.post {
            webView.stopLoading()
            webView.destroy()
        }
        onResult(status)
    }

    private fun unwrap(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            JSONTokener(raw).nextValue().toString()
        } catch (e: Exception) {
            null
        }
    }
}

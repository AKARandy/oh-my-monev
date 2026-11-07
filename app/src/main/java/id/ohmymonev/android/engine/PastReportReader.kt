package id.ohmymonev.android.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneOffset

/**
 * Membaca LAPORAN ASLI dari situs (bukan tebakan lokal):
 * 1. Dalam WebView milik aplikasi (sesi login sendiri), jalankan fetch() ke API Monev.
 * 2. GET /attendances -> participant_id milik akun.
 * 3. GET /daily-logs?date=<tanggal kemarin> -> activity_log/lesson_learned/obstacles asli.
 *
 * Semua dijalankan lewat fetch() di dalam halaman dashboard sehingga mewarisi cookie sesi
 * dan Bearer token yang sudah ada. Tidak ada penyimpanan atau tebakan lokal.
 */
class PastReportReader(
    context: Context,
    private val onResult: (PastReportResult) -> Unit,
) {
    data class PastReportResult(
        val sessionActive: Boolean,
        val date: LocalDate,
        /** Laporan asli dari situs; null bila hari itu libur/belum diisi/tidak terbaca. */
        val draft: ReportDraft?,
        val source: String,
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bridge = Bridge()

    // Respons JS dikirim lewat interface ini (evaluateJavascript terpotong pada teks panjang
    // di sebagian perangkat; interface lebih aman untuk payload besar).
    private inner class Bridge {
        @JavascriptInterface
        fun post(json: String) {
            scope.launch { handle(json) }
        }
    }

    private fun handle(rawJson: String) {
        try {
            val root = JSONObject(rawJson)
            val loggedIn = root.optBoolean("loggedIn", false)
            if (!loggedIn) {
                onResult(PastReportResult(false, targetDate, null, "sesi habis"))
                return
            }
            val participantId = root.optString("participantId", "")
            val logsJson = root.optString("logs", "[]")
            if (participantId.isEmpty()) {
                onResult(PastReportResult(true, targetDate, null, "participant_id tidak terbaca"))
                return
            }
            val logs = JSONArray(logsJson)
            // Ambil log yang cocok dengan tanggal target (paling baru bila beberapa).
            var chosen: JSONObject? = null
            for (i in 0 until logs.length()) {
                val o = logs.optJSONObject(i) ?: continue
                val d = o.optString("date", "").take(10)
                if (d == targetDate.toString()) {
                    chosen = o
                    break
                }
            }
            chosen = chosen ?: run {
                for (i in 0 until logs.length()) {
                    val o = logs.optJSONObject(i) ?: continue
                    if (o.optString("state", "") == "SUBMITTED") return@run o
                }
                null
            }
            val draft = chosen?.let {
                val aktivitas = it.optString("activity_log", "").trim()
                val pelajaran = it.optString("lesson_learned", "").trim()
                val kendala = it.optString("obstacles", "").trim()
                if (aktivitas.isBlank() && pelajaran.isBlank() && kendala.isBlank()) null
                else ReportDraft(aktivitas, pelajaran, kendala)
            }
            onResult(
                PastReportResult(
                    sessionActive = true,
                    date = targetDate,
                    draft = draft,
                    source = if (draft != null) "dibaca dari situs: /daily-logs" else "situs: tidak ada laporan pada tanggal ini",
                ),
            )
        } catch (e: Exception) {
            onResult(PastReportResult(true, targetDate, null, "gagal membaca: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private val targetDate: LocalDate = LocalDate.now(ZonedDateTime.now().zone).minusDays(1)

    fun read() {
        main.post { start() }
    }

    private fun start() {
        val p = DEFAULT_SITE_PROFILE
        val api = "https://monev-api.maganghub.kemnaker.go.id/api/v1"
        val js = """
            (async function(){
              function out(o){ PastReportBridge.post(JSON.stringify(o)); }
              try {
                var dash = await fetch('$p ${p.dashboardUrl()}', {credentials:'include'}).catch(function(){return null});
                var dashText = dash && dash.ok ? await dash.text() : '';
                var loggedIn = ['Beranda','Riwayat','Perkembangan','Akun'].every(function(t){return dashText.indexOf(t)!==-1;});
                if(!loggedIn){ out({loggedIn:false}); return; }
                var idMatch = dashText.match(/"participant_id"\s*:\s*"?(\d+)"?/) || dashText.match(/"id"\s*:\s*"?(\d+)"?,\s*"name"/);
                var participantId = idMatch ? idMatch[1] : '';
                // Coba /auth/me bila id tak ditemukan di HTML.
                if(!participantId){
                  var me = await fetch('$api/auth/me', {credentials:'include'}).catch(function(){return null});
                  if(me && me.ok){
                    var meText = await me.text();
                    var m2 = meText.match(/"id"\s*:\s*"?(\d+)"?/);
                    if(m2) participantId = m2[1];
                  }
                }
                if(!participantId){ out({loggedIn:true, participantId:'', logs:[]}); return; }
                var d = new Date(Date.now() - 24*3600*1000);
                var iso = d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
                var res = await fetch('$api/daily-logs?date='+iso+'&participant_id='+participantId+'&limit=50', {credentials:'include'});
                var body = res && res.ok ? await res.text() : '[]';
                var data = [];
                try { data = JSON.parse(body).data || JSON.parse(body) || []; } catch(e) { data = []; }
                out({loggedIn:true, participantId:participantId, logs:JSON.stringify(data)});
              } catch(err) {
                out({loggedIn:true, participantId:'', logs:[], error:String(err)});
              }
            })()
        """.trimIndent().replace("$p ", "")
        val webView = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(bridge, "PastReportBridge")
            webViewClient = android.webkit.WebViewClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        webView.webChromeClient = object : android.webkit.WebChromeClient() {}
        var done = false
        val deadline = SystemClock.elapsedRealtime() + 45_000
        webView.loadUrl(p.dashboardUrl())
        fun poll() {
            if (done) return
            webView.evaluateJavascript("(function(){return document.body?document.body.innerText:'';})()") { raw ->
                if (done) return@evaluateJavascript
                val ready = raw != null && (raw.contains("Riwayat") || raw.contains("Beranda"))
                if (ready) {
                    done = true
                    webView.evaluateJavascript(js) { _ -> }
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    done = true
                    webView.destroy()
                    scope.launch { onResult(PastReportResult(false, targetDate, null, "halaman tidak siap")) }
                } else {
                    main.postDelayed({ poll() }, 400)
                }
            }
        }
        main.postDelayed({ poll() }, 2500)
    }
}

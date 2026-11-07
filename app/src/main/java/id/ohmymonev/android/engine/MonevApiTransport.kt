package id.ohmymonev.android.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Only app-owned HTML runs in this view. API responses remain data, never page content. */
internal object MonevApiTransport {
    suspend fun request(context: Context, method: String, path: String, token: String?): ApiResponse = withContext(Dispatchers.Main) {
        require(path.startsWith("/") && !path.startsWith("//"))
        val response = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<ApiResponse> { continuation ->
                val main = Handler(Looper.getMainLooper())
                val view = WebView(context.applicationContext)
                val closed = AtomicBoolean(false)
                fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    view.removeJavascriptInterface("MonevRead")
                    view.stopLoading()
                    view.destroy()
                }
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.allowFileAccess = false
                CookieManager.getInstance().setAcceptCookie(true)
                view.addJavascriptInterface(object {
                    @JavascriptInterface fun result(status: Int, body: String) {
                        main.post {
                            if (closed.get()) return@post
                            close()
                            if (continuation.isActive) continuation.resume(ApiResponse(status, body))
                        }
                    }
                }, "MonevRead")
                continuation.invokeOnCancellation { main.post { close() } }
                val headers = JSONObject().put("Accept", "application/json")
                token?.let { headers.put("Authorization", "Bearer $it") }
                val url = JSONObject.quote("https://monev-api.maganghub.kemnaker.go.id/api/v1$path").replace("<", "\\u003c")
                val options = JSONObject().put("method", method).put("credentials", "include")
                    .put("cache", "no-store").put("redirect", "error").put("headers", headers).toString().replace("<", "\\u003c")
                val html = """
                    <html><head><meta charset="UTF-8"></head><body><script>
                    (async function(){
                      try { var r=await fetch($url,$options); MonevRead.result(r.status,await r.text()); }
                      catch(e) { MonevRead.result(0,''); }
                    })();
                    </script></body></html>
                """.trimIndent()
                view.loadDataWithBaseURL("https://monev.maganghub.kemnaker.go.id/", html, "text/html", "UTF-8", null)
            }
        } ?: throw SocketTimeoutException()
        if (response.status == 0) throw IOException("API connection failed")
        response
    }
}

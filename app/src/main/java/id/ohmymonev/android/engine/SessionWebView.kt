package id.ohmymonev.android.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import androidx.core.content.ContextCompat
import android.webkit.WebView
import id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import id.ohmymonev.android.data.SessionRepository
import org.json.JSONObject
import org.json.JSONTokener

internal class PageSession(val token: String?)

internal object SessionWebView {
    private val origin = DEFAULT_SITE_PROFILE.urls.baseUrl

    fun trusted(url: String?): Boolean {
        val uri = url?.let { Uri.parse(it) } ?: return false
        return uri.scheme == "https" && uri.host == Uri.parse(origin).host && uri.port in listOf(-1, 443)
    }

    fun locationClient(context: Context) = object : WebChromeClient() {
        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?,
        ) {
            val allowed = trusted(origin) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            id.ohmymonev.android.data.ActionLog.record(context, "web.location.permission",
                "trusted=${trusted(origin)} allowed=$allowed")
            callback?.invoke(origin, allowed, false)
        }
    }

    /** Seed only the app-owned page at Monev's HTTPS origin before opening the real SPA. */
    fun load(view: WebView, token: String?, destination: String) {
        if (token == null || !trusted(destination)) { view.loadUrl(destination); return }
        view.loadDataWithBaseURL("$origin/", restoreHtml(token, destination), "text/html", "UTF-8", null)
    }

    internal fun restoreHtml(token: String, destination: String): String {
        require(trusted(destination))
        val value = JSONObject.quote(token).replace("<", "\\u003c")
        val target = JSONObject.quote(destination).replace("<", "\\u003c")
        return "<html><head><meta charset='UTF-8'></head><body><script>" +
            "sessionStorage.setItem('monev_access_token',$value);location.replace($target);" +
            "</script></body></html>"
    }

    suspend fun inspect(view: WebView): PageSession? {
        if (!trusted(view.url)) return null
        return withTimeoutOrNull(2_000) { suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript("""
                (function(){return JSON.stringify({origin:location.origin,
                  token:sessionStorage.getItem('monev_access_token')});})()
            """.trimIndent()) { raw ->
                if (!continuation.isActive) return@evaluateJavascript
                val result = runCatching {
                    val json = JSONObject(JSONTokener(raw).nextValue().toString())
                    if (json.optString("origin") != origin || !trusted(view.url)) null
                    else PageSession(json.optString("token").takeUnless { it.isBlank() || it == "null" })
                }.getOrNull()
                continuation.resume(result)
            }
        } }
    }

    fun release(view: WebView, session: SessionRepository, expectedVersion: Long) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                inspect(view)?.token?.let {
                    val version = if (session.state.value.hasToken) expectedVersion else session.state.value.version
                    session.capture(it, version)
                }
            } finally {
                view.stopLoading()
                view.destroy()
            }
        }
    }
}

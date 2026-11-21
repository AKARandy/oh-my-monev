package id.ohmymonev.android.engine

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import id.ohmymonev.android.data.ActionLog
import id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class LocationBridge(
    private val context: Context,
    private val view: WebView,
    private val runId: String,
    deviceAllowed: () -> Boolean = { true },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val resolver = LocationResolver(context, deviceAllowed)
    private var closed = false
    private var script: androidx.webkit.ScriptHandler? = null
    @Volatile private var sourceSnapshot: String? = null
    @Volatile private var fallbackSnapshot: String? = null

    val lastSource: String? get() = sourceSnapshot
    val lastFallbackReason: String? get() = fallbackSnapshot

    fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            throw LocationFailure("webview", 2, "Perbarui Android System WebView.")
        val origins = setOf(DEFAULT_SITE_PROFILE.urls.baseUrl)
        WebViewCompat.addWebMessageListener(view, "monevDeviceLocation", origins) { _, message, origin, mainFrame, reply ->
            if (closed || !accepts(origin.toString(), mainFrame)) return@addWebMessageListener
            val request = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            val id = request.optString("id")
            if (id.isBlank() || id.length > 100) return@addWebMessageListener
            scope.launch {
                val result = JSONObject().put("id", id)
                try {
                    val fix = resolver.resolve(request.optLong("maximumAge", 0).coerceAtLeast(0),
                        request.optLong("timeout", 30_000).coerceIn(1, 30_000))
                    remember(fix)
                    val fallback = fix.fallbackReason ?: "none"
                    ActionLog.record(context, "send.location.result", "$runId source=${fix.source.name} fallback=$fallback")
                    result.put("position", JSONObject().put("timestamp", fix.obtainedAtMs).put("coords", JSONObject()
                        .put("latitude", fix.latitude).put("longitude", fix.longitude)
                        .put("accuracy", fix.accuracy ?: JSONObject.NULL)
                        .put("altitude", JSONObject.NULL).put("altitudeAccuracy", JSONObject.NULL)
                        .put("heading", JSONObject.NULL).put("speed", JSONObject.NULL)))
                } catch (failure: LocationFailure) {
                    rememberFailure(failure)
                    ActionLog.record(context, "send.location.result", "$runId reason=${failure.reason}")
                    result.put("error", JSONObject().put("code", failure.code).put("message", failure.message))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (sourceSnapshot == null) fallbackSnapshot = "unavailable"
                    ActionLog.record(context, "send.location.result", "$runId reason=unavailable")
                    result.put("error", JSONObject().put("code", 2).put("message", "Lokasi tidak tersedia."))
                }
                if (!closed) reply.postMessage(result.toString())
            }
        }
        script = WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, origins)
    }

    suspend fun prepare() {
        ActionLog.record(context, "send.location.start", runId)
        try {
            val fix = resolver.resolve(0, 30_000)
            remember(fix)
            ActionLog.record(context, "send.location.ready", "$runId source=${fix.source.name} fallback=${fix.fallbackReason ?: "none"}")
        } catch (failure: LocationFailure) {
            rememberFailure(failure)
            throw failure
        }
    }

    fun close() {
        closed = true
        scope.cancel()
        resolver.close()
        script?.remove()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.removeWebMessageListener(view, "monevDeviceLocation")
    }

    private fun remember(fix: LocationFix) {
        sourceSnapshot = fix.source.name
        fallbackSnapshot = fix.fallbackReason
    }

    private fun rememberFailure(failure: LocationFailure) {
        if (sourceSnapshot == null) fallbackSnapshot = failure.reason
    }

    companion object {
        fun accepts(origin: String, mainFrame: Boolean) = mainFrame && SessionWebView.trusted(origin)
        val SCRIPT = """
            (function(){
              if(window.top!==window || !window.monevDeviceLocation) return;
              var pending={}, sequence=0;
              monevDeviceLocation.onmessage=function(event){
                var result=JSON.parse(event.data), item=pending[result.id];
                if(!item) return; delete pending[result.id]; clearTimeout(item.timer);
                if(result.error){if(item.error)item.error(result.error);}else item.success(result.position);
              };
              navigator.geolocation.getCurrentPosition=function(success,error,options){
                if(typeof success!=='function') throw new TypeError('A location callback is required');
                options=options||{};
                var rawTimeout=options.timeout==null?30000:Number(options.timeout);
                var id=String(++sequence), timeout=isFinite(rawTimeout)?Math.max(0,Math.min(30000,rawTimeout)):30000;
                var age=Math.max(0,Number(options.maximumAge)||0);
                pending[id]={success:success,error:error,timer:setTimeout(function(){
                  var item=pending[id]; delete pending[id];
                  if(item&&item.error)item.error({code:3,message:'Lokasi belum ditemukan.'});
                },timeout)};
                monevDeviceLocation.postMessage(JSON.stringify({id:id,maximumAge:age,timeout:timeout}));
              };
            })();
        """.trimIndent()
    }
}

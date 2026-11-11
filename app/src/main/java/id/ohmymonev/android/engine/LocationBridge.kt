package id.ohmymonev.android.engine

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import id.ohmymonev.android.data.ActionLog
import id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class LocationBridge(private val context: Context, private val view: WebView, private val runId: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val device = DeviceLocation(context)
    private var closed = false
    private var script: androidx.webkit.ScriptHandler? = null

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
                    val location = device.current(request.optLong("maximumAge", 0).coerceAtLeast(0), request.optLong("timeout", 30_000))
                    ActionLog.record(context, "send.location.result", "$runId success=true ageMs=${DeviceLocation.ageMs(location)}")
                    result.put("position", JSONObject().put("timestamp", location.time).put("coords", JSONObject()
                        .put("latitude", location.latitude).put("longitude", location.longitude).put("accuracy", location.accuracy)
                        .put("altitude", JSONObject.NULL).put("altitudeAccuracy", JSONObject.NULL)
                        .put("heading", JSONObject.NULL).put("speed", JSONObject.NULL)))
                } catch (failure: LocationFailure) {
                    ActionLog.record(context, "send.location.result", "$runId reason=${failure.reason}")
                    result.put("error", JSONObject().put("code", failure.code).put("message", failure.message))
                }
                if (!closed) reply.postMessage(result.toString())
            }
        }
        script = WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, origins)
    }

    suspend fun prepare() {
        ActionLog.record(context, "send.location.start", runId)
        val location = device.current(0, 30_000)
        ActionLog.record(context, "send.location.ready", "$runId ageMs=${DeviceLocation.ageMs(location)}")
    }

    fun close() {
        closed = true
        scope.cancel()
        device.clear()
        script?.remove()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.removeWebMessageListener(view, "monevDeviceLocation")
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
                var id=String(++sequence), timeout=options.timeout===undefined?30000:Math.max(0,Math.min(30000,Number(options.timeout)));
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

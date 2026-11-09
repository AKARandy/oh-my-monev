package id.ohmymonev.core.enginecore

import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.model.ReportPart
import id.ohmymonev.core.siteprofile.SiteProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JS snippets for evaluateJavascript() against the Monev SPA. Facts per docs/site-research.md:
 * element ids are generated (input-v-...), so everything is matched by placeholder or visible label;
 * textarea values must go through the native HTMLTextAreaElement value setter + a bubbled 'input'
 * event, or the framework state and live counter stay stale and the field is invalid.
 *
 * Rules here:
 *  - every snippet is a SINGLE line (no raw newlines) so Android logs and error positions stay clean;
 *  - all Kotlin-provided strings (placeholders, draft text, markers) travel inside a JSON payload
 *    produced by the JSON encoder — never interpolated raw into the script;
 *  - text payloads are embedded as a JSON literal (valid JS object/array literal syntax), with the
 *    two line-separator characters U+2028/U+2029 escaped for JS string-literal safety;
 *  - snippets announce completion through window.__ohmymonev_done (a JSON string) and return it.
 */
object JsSnippets {

    private val json = Json

    /** Placeholder texts in ReportPart order at [FORM_PARTS] index i pair with value at index i. */
    private val FORM_PARTS: List<ReportPart> = listOf(
        ReportPart.AKTIVITAS,
        ReportPart.PELAJARAN,
        ReportPart.KENDALA,
    )

    @Serializable
    private data class SnippetPayload(
        /** Form textarea placeholders in [FORM_PARTS] order. */
        val h: List<String>,
        /** Draft texts to type, paired with [h]. */
        val v: List<String>,
        val tabs: List<String>,
        val loadingText: String,
        val appOpenButtonLabel: String,
        val confirmCheckboxText: String,
        val submitButtonLabel: String,
        val locationOkText: String,
    )

    private fun payload(profile: SiteProfile, draft: ReportDraft?): String {
        val p = SnippetPayload(
            h = FORM_PARTS.map { profile.textareasByPart.getValue(it) },
            v = if (draft == null) {
                // Read-back does not need values; empty strings keep list indices aligned.
                FORM_PARTS.map { "" }
            } else {
                listOf(draft.aktivitas, draft.pelajaran, draft.kendala)
            },
            tabs = profile.markers.loggedInTabs,
            loadingText = profile.markers.loadingText,
            appOpenButtonLabel = profile.markers.appOpenButtonLabel,
            confirmCheckboxText = profile.markers.confirmCheckboxText,
            submitButtonLabel = profile.markers.submitButtonLabel,
            locationOkText = profile.markers.locationOkText,
        )
        return embed(json.encodeToString(SnippetPayload.serializer(), p))
    }

    /** JSON -> valid single-line JS literal (JSON is a JS literal subset except these separators). */
    private fun embed(jsonText: String): String =
        jsonText.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    /**
     * Fills each of the 3 form textareas by matching its placeholder string exactly under
     * document.querySelectorAll, setting the value with the native prototype setter inside a try
     * and dispatching a bubbling 'input' event, so Vue-like state and the live counter update.
     * window.__ohmymonev_done is set to {"ok":bool,"filled":[i...],"missing":[i...]}.
     */
    fun fillTextareas(profile: SiteProfile, aktivitas: String, pelajaran: String, kendala: String): String {
        val payload = payload(profile, ReportDraft(aktivitas, pelajaran, kendala))
        return "(function(){var p=$payload;var set=null;try{set=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;}catch(e){}" +
            "var r={ok:false,filled:[],missing:[]};if(set){var t=document.querySelectorAll('textarea');" +
            "for(var i=0;i<p.h.length;i++){var el=null;for(var j=0;j<t.length;j++){if(t[j].placeholder===p.h[i]){el=t[j];break;}}" +
            "if(el){try{set.call(el,p.v[i]);el.dispatchEvent(new Event('input',{bubbles:true}));r.filled.push(i);}catch(e2){r.missing.push(i);}}else{r.missing.push(i);}}}" +
            "r.ok=r.filled.length===p.h.length;window.__ohmymonev_done=JSON.stringify(r);return window.__ohmymonev_done;})()"
    }

    /**
     * Read-back snippet: returns JSON.stringify({values:[val|null per part],
     * checkboxChecked:bool, submitPresent:bool}). Does not set __ohmymonev_done; the caller
     * parses the evaluateJavascript result directly.
     */
    fun readBack(profile: SiteProfile): String {
        val payload = payload(profile, null)
        return "(function(){var p=$payload;var v=[];var t=document.querySelectorAll('textarea');" +
            "for(var i=0;i<p.h.length;i++){var el=null;for(var j=0;j<t.length;j++){if(t[j].placeholder===p.h[i]){el=t[j];break;}}" +
            "v.push(el===null?null:el.value);}" +
            "var cb=false;var labels=document.querySelectorAll('label');for(var a=0;a<labels.length;a++){" +
            "if((labels[a].textContent||'').indexOf(p.confirmCheckboxText)!==-1){var c=labels[a].querySelector('input[type=checkbox]');" +
            "if(c!==null){cb=!!c.checked;break;}}}" +
            "var sub=false;var bs=document.querySelectorAll('button');for(var b=0;b<bs.length;b++){" +
            "if((bs[b].textContent||'').trim()===p.submitButtonLabel){sub=true;break;}}" +
            "return JSON.stringify({values:v,checkboxChecked:cb,submitPresent:sub});})()"
    }

    /**
     * Ticks the confirmation checkbox: finds the label containing [SiteMarkers.confirmCheckboxText]
     * and checks the checkbox inside it, emitting both 'input' and 'change'. Falls back to the only
     * checkbox on the page when exactly one exists. window.__ohmymonev_done = {"checked":bool}.
     */
    fun tickCheckbox(profile: SiteProfile): String {
        val payload = payload(profile, null)
        return "(function(){var p=$payload;var done=false;var labels=document.querySelectorAll('label');" +
            "for(var i=0;i<labels.length;i++){" +
            "if((labels[i].textContent||'').indexOf(p.confirmCheckboxText)!==-1){var c=labels[i].querySelector('input[type=checkbox]');if(c!==null){" +
            "try{c.checked=true;c.dispatchEvent(new Event('input',{bubbles:true}));c.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}" +
            "done=!!c.checked;break;}}}" +
            "if(!done){var all=document.querySelectorAll('input[type=checkbox]');if(all.length===1&&all[0]&&!all[0].checked){" +
            "try{all[0].checked=true;all[0].dispatchEvent(new Event('input',{bubbles:true}));all[0].dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}" +
            "done=!!all[0].checked;}}" +
            "window.__ohmymonev_done=JSON.stringify({checked:done});return window.__ohmymonev_done;})()"
    }

    /**
     * Clicks the submit button whose visible label is [SiteMarkers.submitButtonLabel]
     * (exact trim match first, then containment fallback).
     * window.__ohmymonev_done = {"clicked":bool}.
     */
    fun clickSubmit(profile: SiteProfile): String {
        val payload = payload(profile, null)
        return "(function(){var p=$payload;var b=null;var bs=document.querySelectorAll('button');" +
            "for(var i=0;i<bs.length;i++){if((bs[i].textContent||'').trim()===p.submitButtonLabel){b=bs[i];break;}}" +
            "if(b===null){for(var j=0;j<bs.length;j++){if((bs[j].textContent||'').indexOf(p.submitButtonLabel)!==-1){b=bs[j];break;}}}" +
            "var blocked=b!==null&&(b.disabled||b.getAttribute('aria-disabled')==='true');" +
            "var clicked=false;if(b!==null&&!blocked){try{b.click();clicked=true;}catch(e){}}" +
            "window.__ohmymonev_done=JSON.stringify({clicked:clicked,blocked:blocked});return window.__ohmymonev_done;})()"
    }

    fun submissionState(): String =
        "(function(){return JSON.stringify({url:window.location.href,text:document.body?document.body.textContent:''});})()"

}

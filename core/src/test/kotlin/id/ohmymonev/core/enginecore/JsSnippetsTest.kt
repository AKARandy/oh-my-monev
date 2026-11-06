package id.ohmymonev.core.enginecore

import id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSnippetsTest {

    private val profile = DEFAULT_SITE_PROFILE

    // Adversarial text: quotes, CR/LF, tab, backslash, a literal "$payload" and a JSON end-bracket.
    private val roughText = "Hal {ohm}: \"tes\" \nbaris\tdua \\ back \$payload ] \"malformed\":"

    @Test
    fun `fill snippet is one line`() {
        assertEquals(1, JsSnippets.fillTextareas(profile, "a", "b", "c").lines().size)
    }

    @Test
    fun `readback tick and click snippets are one line`() {
        assertEquals(1, JsSnippets.readBack(profile).lines().size)
        assertEquals(1, JsSnippets.tickCheckbox(profile).lines().size)
        assertEquals(1, JsSnippets.clickSubmit(profile).lines().size)
    }

    @Test
    fun `fill snippet contains the three verbatim placeholders`() {
        val js = JsSnippets.fillTextareas(profile, "- a", "b", "c")
        for (placeholder in profile.textareasByPart.values) {
            assertTrue("missing placeholder: $placeholder", js.contains(placeholder))
        }
    }

    @Test
    fun `fill snippet uses the native value setter and bubbled input event`() {
        val js = JsSnippets.fillTextareas(profile, "a", "b", "c")
        assertTrue(js.contains("Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set"))
        assertTrue(js.contains("dispatchEvent(new Event('input',{bubbles:true})"))
        assertTrue(js.contains("querySelectorAll('textarea')"))
        assertTrue(js.contains("window.__ohmymonev_done"))
    }

    @Test
    fun `fill snippet never admits a textarea by element id`() {
        val js = JsSnippets.fillTextareas(profile, "a", "b", "c")
        assertFalse(js.contains("getElementById"))
    }

    @Test
    fun `readback exposes textarea values checkbox and submit`() {
        val js = JsSnippets.readBack(profile)
        assertTrue(js.contains("JSON.stringify({values:v,checkboxChecked:cb,submitPresent:sub})"))
        assertTrue(js.contains("Simpan dan Kirim"))
        assertTrue(js.contains("Saya menyatakan telah meninjau"))
    }

    @Test
    fun `tick and click snippets reference their markers`() {
        assertTrue(JsSnippets.tickCheckbox(profile).contains("Saya menyatakan telah meninjau"))
        assertTrue(JsSnippets.clickSubmit(profile).contains("Simpan dan Kirim"))
        assertTrue(JsSnippets.clickSubmit(profile).contains(".click()"))
    }

    @Test
    fun `rough text is injected through the json encoder`() {
        val js = JsSnippets.fillTextareas(profile, roughText, "ilmu\"x", "kend\\ala")

        // Quotes must arrive JSON-escaped (raw " would terminate a JS string literal early).
        assertTrue(js.contains("\\\"tes\\\""))
        // Control characters must be escaped: no raw newline may reach the script body.
        assertFalse(js.contains("\n"))
        assertFalse(js.contains("\r"))
        assertTrue(js.contains("\\nbaris"))
        assertTrue(js.contains("\\tdua"))
        // Backslashes doubled by the encoder.
        assertTrue(js.contains("\\\\ back"))
        assertTrue(js.contains("kend\\\\ala"))
        // "$" is inert in JSON and JS; it must appear verbatim, never interpolated.
        assertTrue(js.contains("\$payload"))
    }

    @Test
    fun `empty snippet payload still carries the marker strings`() {
        val js = JsSnippets.readBack(DEFAULT_SITE_PROFILE)
        assertTrue(js.contains("Beranda"))
        assertTrue(js.contains("Lokasi berhasil diakses"))
    }
}

package id.ohmymonev.core.siteprofile

import id.ohmymonev.core.model.ReportPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteProfileTest {

    private val default = DEFAULT_SITE_PROFILE

    @Test
    fun `default urls match site research`() {
        assertEquals("https://monev.maganghub.kemnaker.go.id", default.urls.baseUrl)
        assertEquals("/dashboard", default.urls.dashboardPath)
        assertEquals("/dashboard/evaluasi", default.urls.evaluasiPath)
        assertEquals("/dashboard/riwayat?date={date}&view=edit", default.urls.riwayatEditTemplate)
    }

    @Test
    fun `default built-in is not marked modified`() {
        assertFalse(default.modified)
    }

    @Test
    fun `default textareas map holds verbatim placeholders`() {
        assertEquals(
            mapOf(
                ReportPart.AKTIVITAS to "Tulis uraian aktivitas minimal 100 karakter",
                ReportPart.PELAJARAN to "Tulis ilmu/pembelajaran yang diperoleh minimal 100 karakter",
                ReportPart.KENDALA to "Tulis kendala/hambatan yang dialami minimal 100 karakter",
            ),
            default.textareasByPart,
        )
    }

    @Test
    fun `default timing values match spec`() {
        assertEquals(1500L, default.timing.spaSettleMs)
        assertEquals(250L, default.timing.pollIntervalMs)
        assertEquals(20_000L, default.timing.textareaWaitTimeoutMs)
    }

    @Test
    fun `url helpers compose and substitute the date`() {
        assertEquals("https://monev.maganghub.kemnaker.go.id/dashboard", default.dashboardUrl())
        assertEquals("https://monev.maganghub.kemnaker.go.id/dashboard/evaluasi", default.evaluasiUrl())
        assertEquals(
            "https://monev.maganghub.kemnaker.go.id/dashboard/riwayat?date=2026-11-06&view=edit",
            default.riwayatEditUrl("2026-11-06"),
        )
    }

    @Test
    fun `loader parses the built-in json round trip`() {
        val parsed = SiteProfileLoader.load(DEFAULT_SITE_PROFILE_JSON)
        assertEquals(default, parsed)
    }

    @Test
    fun `loader accepts a slightly modified valid copy`() {
        val modifiedJson = DEFAULT_SITE_PROFILE_JSON
            .replace("https://monev.maganghub.kemnaker.go.id", "https://mirror.example.go.id")
            .replace("\"spaSettleMs\":1500", "\"spaSettleMs\":2500")
        val parsed = SiteProfileLoader.load(modifiedJson)

        assertEquals("https://mirror.example.go.id", parsed.urls.baseUrl)
        assertEquals("/dashboard", parsed.urls.dashboardPath)
        assertEquals(2500L, parsed.timing.spaSettleMs)
        assertEquals(default.textareasByPart, parsed.textareasByPart)
        assertFalse(parsed.modified)
        assertNotEquals(default, parsed)
    }

    @Test
    fun `loader falls back to a modified default on invalid json`() {
        val fallback = SiteProfileLoader.load("{ this is not json")

        assertEquals(default.copy(modified = true), fallback)
        assertTrue(fallback.modified)
    }

    @Test
    fun `loader falls back when a required key is missing`() {
        val broken = DEFAULT_SITE_PROFILE_JSON.replace("\"markers\":{", "\"bogus\":{")
        val fallback = SiteProfileLoader.load(broken)

        assertEquals(default.copy(modified = true), fallback)
        assertTrue(fallback.modified)
    }

    @Test
    fun `loader falls back when a textareas entry is missing`() {
        // A partial textareasByPart decodes fine (maps tolerate missing entries) but is unusable
        // for the engine: it must fall back too.
        val broken = DEFAULT_SITE_PROFILE_JSON
            .replace("\"AKTIVITAS\":\"Tulis uraian aktivitas minimal 100 karakter\",", "")
        val fallback = SiteProfileLoader.load(broken)

        assertEquals(default.copy(modified = true), fallback)
        assertTrue(fallback.modified)
    }

    @Test
    fun `loader falls back on blank input`() {
        val fallback = SiteProfileLoader.load("   ")
        assertEquals(default.copy(modified = true), fallback)
        assertTrue(fallback.modified)
    }

    @Test
    fun `fallback is a copy, not the same instance`() {
        val fallback = SiteProfileLoader.load("")
        assertFalse(fallback === default)
    }
}

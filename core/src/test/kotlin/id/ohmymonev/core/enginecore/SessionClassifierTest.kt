package id.ohmymonev.core.enginecore

import id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionClassifierTest {

    private val tabs = DEFAULT_SITE_PROFILE.markers.loggedInTabs
    private val dashboardText =
        "<nav>Beranda</nav> <nav>Riwayat</nav> <nav>Perkembangan</nav> <a>Akun</a> Isi Laporan Hari Ini"

    @Test
    fun `two hundred with all dashboard tabs is active`() {
        assertEquals(SessionState.ACTIVE, SessionClassifier.classify(200, false, true))
    }

    @Test
    fun `two hundred without dashboard tabs is unknown`() {
        assertEquals(SessionState.UNKNOWN, SessionClassifier.classify(200, false, false))
    }

    @Test
    fun `two hundred landing on a login path is expired`() {
        assertEquals(SessionState.EXPIRED, SessionClassifier.classify(200, true, true))
        assertEquals(SessionState.EXPIRED, SessionClassifier.classify(200, true, false))
    }

    @Test
    fun `redirect that ends on a login url is expired`() {
        assertEquals(SessionState.EXPIRED, SessionClassifier.classify(302, true, false))
    }

    @Test
    fun `redirects ending anywhere else are unknown`() {
        assertEquals(SessionState.UNKNOWN, SessionClassifier.classify(302, false, true))
        assertEquals(SessionState.UNKNOWN, SessionClassifier.classify(301, false, false))
    }

    @Test
    fun `server errors and unauthorized stay unknown`() {
        assertEquals(SessionState.UNKNOWN, SessionClassifier.classify(500, false, true))
        assertEquals(SessionState.UNKNOWN, SessionClassifier.classify(401, false, false))
    }

    @Test
    fun `login and sso paths are detected`() {
        assertTrue(SessionClassifier.landsOnLoginUrl("https://monev.maganghub.kemnaker.go.id/login"))
        assertTrue(SessionClassifier.landsOnLoginUrl("https://monev.maganghub.kemnaker.go.id/auth/SsoCallback?next=/dashboard"))
        assertTrue(SessionClassifier.landsOnLoginUrl("https://auth.example.go.id/sign-in"))
        assertFalse(SessionClassifier.landsOnLoginUrl("https://monev.maganghub.kemnaker.go.id/dashboard"))
        assertFalse(SessionClassifier.landsOnLoginUrl("about:blank"))
    }

    @Test
    fun `url query alone does not mark a login page`() {
        // Path has no login/sso piece; only the query mentions it.
        assertFalse(SessionClassifier.landsOnLoginUrl("https://monev.maganghub.kemnaker.go.id/dashboard?redirect=login"))
    }

    @Test
    fun `tabs detection requires every marker`() {
        assertTrue(SessionClassifier.pageHasTabs(dashboardText, tabs))
        assertFalse(SessionClassifier.pageHasTabs("<div>Memuat halaman</div>", tabs))
        assertFalse(SessionClassifier.pageHasTabs("Beranda Riwayat Perkembangan", tabs))
        assertFalse(SessionClassifier.pageHasTabs(null, tabs))
        assertFalse(SessionClassifier.pageHasTabs(dashboardText, emptyList()))
    }

    @Test
    fun `convenience overload composes url and text signals`() {
        val profile = DEFAULT_SITE_PROFILE
        assertEquals(
            SessionState.ACTIVE,
            SessionClassifier.classify(200, "https://monev.maganghub.kemnaker.go.id/dashboard", dashboardText, profile),
        )
        assertEquals(
            SessionState.EXPIRED,
            SessionClassifier.classify(302, "https://monev.maganghub.kemnaker.go.id/login", dashboardText, profile),
        )
        assertEquals(
            SessionState.UNKNOWN,
            SessionClassifier.classify(200, "https://monev.maganghub.kemnaker.go.id/dashboard", "Memuat halaman", profile),
        )
    }
}

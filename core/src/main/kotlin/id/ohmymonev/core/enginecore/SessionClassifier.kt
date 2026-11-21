package id.ohmymonev.core.enginecore

import id.ohmymonev.core.siteprofile.SiteProfile

/** Whether the WebView session currently points at a logged-in dashboard. */
sealed class SessionState {
    data object ACTIVE : SessionState()
    data object EXPIRED : SessionState()
    data object UNKNOWN : SessionState()
}

/**
 * Pure decision logic for [SessionState]. Rules (docs/plan.md D2, SiteProfile markers):
 *  - flows end on a login/auth host or a URL path containing "login"/"sso" -> EXPIRED;
 *  - HTTP 200 whose page contains every loggedInTab marker -> ACTIVE;
 *  - anything else (redirects elsewhere, 5xx, idle SPA without tabs) -> UNKNOWN.
 */
object SessionClassifier {

    private val LOGIN_HOST_REGEX = Regex("(^|[.-])(login|auth|sso)([.-]|$)")
    private val LOGIN_PATH_REGEX = Regex("(^|[/._-])(login|auth|sso)")

    fun classify(statusCode: Int, landsOnLoginUrl: Boolean, hasDashboardTabs: Boolean): SessionState = when {
        landsOnLoginUrl -> SessionState.EXPIRED
        statusCode == 200 && hasDashboardTabs -> SessionState.ACTIVE
        else -> SessionState.UNKNOWN
    }

    /** True when the final URL lands on a login/auth/sso host or a login/auth/sso path. */
    fun landsOnLoginUrl(finalUrl: String): Boolean {
        val withoutQueryOrFragment = finalUrl.trim().substringBefore('?').substringBefore('#')
        val uri = try {
            java.net.URI(withoutQueryOrFragment)
        } catch (e: Exception) {
            return false
        }
        val host = uri.host?.lowercase().orEmpty()
        if (LOGIN_HOST_REGEX.containsMatchIn(host)) return true
        val path = uri.rawPath?.lowercase().orEmpty()
        if (LOGIN_PATH_REGEX.containsMatchIn(path)) return true
        return false
    }

    /** True when pageText contains every non-blank tab label (pure; case- and whitespace-sensitive match). */
    fun pageHasTabs(pageText: String?, loggedInTabs: List<String>): Boolean {
        if (pageText == null || loggedInTabs.isEmpty()) return false
        return loggedInTabs.all { it.isNotBlank() && pageText.contains(it) }
    }

    /** Convenience composition over a real page load: final URL, page text and profile markers. */
    fun classify(statusCode: Int, finalUrl: String, pageText: String?, profile: SiteProfile): SessionState =
        classify(statusCode, landsOnLoginUrl(finalUrl), pageHasTabs(pageText, profile.markers.loggedInTabs))
}

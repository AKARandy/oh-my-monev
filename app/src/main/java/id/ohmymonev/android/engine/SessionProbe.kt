package id.ohmymonev.android.engine

import android.content.Context
import id.ohmymonev.core.enginecore.SessionState

/** A successful profile response confirms authentication; HTML and elapsed time do not. */
class SessionProbe(private val context: Context) {
    suspend fun check(): SessionState {
        val api = MonevApiClient(context)
        val profile = api.objectData("/users/me") ?: return api.failureState
        if (profile.isNull("id") || profile.optString("id").isBlank()) {
            api.failed("Profil belum dapat diperiksa. Status login terakhir tetap disimpan.")
            return SessionState.UNKNOWN
        }
        api.confirmActive()
        return SessionState.ACTIVE
    }
}

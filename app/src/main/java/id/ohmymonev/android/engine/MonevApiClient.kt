package id.ohmymonev.android.engine

import android.content.Context
import id.ohmymonev.android.data.SessionCredentials
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.core.enginecore.SessionState
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal data class ApiResponse(val status: Int, val body: String)

/** Bearer authentication follows the live contract in docs/monev-exploration-2026-11-07.md. */
internal class MonevApiClient(
    private val context: Context,
    private val session: SessionRepository = SessionRepository.get(context),
    private val transport: (suspend (String, String, String?) -> ApiResponse)? = null,
) {
    private var access: SessionCredentials? = null
    var failureState: SessionState = SessionState.UNKNOWN
        private set
    var message: String = "Situs belum dapat diperiksa."
        private set

    suspend fun confirmActive() { access?.let { session.confirm(it) } }

    suspend fun failed(reason: String) {
        message = reason
        session.uncertain(access, reason)
    }

    suspend fun objectData(path: String): JSONObject? {
        val body = get(path) ?: return null
        return try { JSONObject(body).let { it.optJSONObject("data") ?: it } }
        catch (_: JSONException) { failed("Situs mengirim data yang tidak dikenali."); null }
    }

    suspend fun arrayData(path: String): JSONArray? {
        val body = get(path) ?: return null
        return try {
            val root = JSONTokener(body).nextValue()
            val rows = if (root is JSONArray) root else (root as? JSONObject)?.optJSONArray("data")
            if (rows == null) failed("Situs mengirim data laporan yang tidak dikenali.")
            rows
        } catch (_: JSONException) { failed("Situs mengirim data laporan yang tidak dikenali."); null }
    }

    private suspend fun get(path: String): String? {
        if (access == null) access = session.credentials()
        val credential = access ?: run { failed("Login melalui halaman situs pada tab Sesi."); return null }
        try {
            var response = request("GET", path, credential.token)
            if (!session.matches(credential)) return null
            if (response.status == 401) {
                val refresh = request("POST", "/auth/refresh", null)
                if (refresh.status == 401 || refresh.status == 403) return expired(credential)
                if (refresh.status !in 200..299) { failed("Layanan login belum tersedia. Coba lagi."); return null }
                val root = JSONObject(refresh.body)
                val token = root.optString("access_token").ifBlank { root.optJSONObject("data")?.optString("access_token").orEmpty() }
                if (token.isBlank()) { failed("Situs belum dapat memperbarui login."); return null }
                if (!session.matches(credential)) return null
                session.capture(token, credential.version, preserveConfirmation = true)
                val refreshed = session.credentials()
                if (refreshed?.token != token) { failed("Login belum dapat diperbarui. Coba Cek sesi kembali."); return null }
                access = refreshed
                val updated = access ?: return null
                response = request("GET", path, updated.token)
                if (response.status == 401) return expired(updated)
            }
            if (!session.matches(access ?: return null)) return null
            if (response.status !in 200..299) { failed("Situs belum dapat memproses permintaan. Coba lagi."); return null }
            return response.body
        } catch (_: IOException) {
            failed("Aplikasi belum dapat menghubungi situs. Status login terakhir tidak berubah.")
        } catch (_: JSONException) {
            failed("Situs mengirim data login yang tidak dikenali.")
        }
        return null
    }

    private suspend fun expired(credential: SessionCredentials): String? {
        if (session.reject(credential)) {
            failureState = SessionState.EXPIRED
            message = "Sesi berakhir. Login kembali."
        }
        return null
    }

    private suspend fun request(method: String, path: String, token: String?): ApiResponse {
        transport?.let { return it(method, path, token) }
        return MonevApiTransport.request(context, method, path, token)
    }
}

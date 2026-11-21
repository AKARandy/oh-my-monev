package id.ohmymonev.android.engine

import android.content.Context
import id.ohmymonev.android.data.ActionLog
import id.ohmymonev.android.data.SessionCredentials
import id.ohmymonev.android.data.SessionRefresh
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

    val credentialVersion: Long? get() = access?.version

    suspend fun isCurrent(): Boolean = access?.let { session.matches(it) } == true

    suspend fun confirmActive() { access?.let { session.confirm(it) } }

    suspend fun failed(reason: String) {
        message = reason
        session.uncertain(access, reason)
    }

    suspend fun objectData(path: String): JSONObject? {
        val body = get(path) ?: return null
        return try {
            val data = JSONObject(body).let { it.optJSONObject("data") ?: it }
            if (path == "/users/me" && isCurrent()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                id.ohmymonev.android.schedule.WorkDaysStore(context).save(data)
                id.ohmymonev.android.schedule.AlarmScheduler.armAll(context,
                    id.ohmymonev.android.schedule.TimeSettingsStore(context).settingsOnce())
                }
            }
            data
        }
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

    /** Site list responses expose data, page and page_size (chunk-YWtjk5YC.js). */
    suspend fun allPages(path: String): JSONArray? {
        val result = JSONArray()
        var page = 1
        var previous: String? = null
        while (true) {
            if (page > MAX_PAGES) { failed("Halaman riwayat terlalu banyak."); return null }
            val body = get("$path&page=$page") ?: return null
            try {
                val root = JSONTokener(body).nextValue()
                val objectRoot = root as? JSONObject
                val rows = if (root is JSONArray) root else objectRoot?.optJSONArray("data")
                if (rows == null) { failed("Data riwayat belum dapat dibaca."); return null }
                if (rows.length() > 0 && (objectRoot?.has("page") != true || !objectRoot.has("page_size"))) {
                    failed("Informasi halaman riwayat belum dapat dibaca."); return null
                }
                val pageSize = if (objectRoot?.has("page_size") == true) objectRoot.optInt("page_size", -1) else 100
                if (pageSize <= 0 || objectRoot?.has("page") == true && objectRoot.optInt("page") != page ||
                    rows.length() > 0 && rows.toString() == previous) {
                    failed("Halaman riwayat tidak sesuai. Coba Perbarui kembali."); return null
                }
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i)
                    if (row == null) { failed("Data riwayat belum dapat dibaca."); return null }
                    result.put(row)
                }
                val total = if (objectRoot?.has("total") == true) objectRoot.optInt("total", -1) else -1
                if (objectRoot?.has("total") == true && total < 0) { failed("Jumlah riwayat belum dapat dibaca."); return null }
                if (total >= 0 && result.length() > total) { failed("Jumlah riwayat tidak sesuai."); return null }
                if (total >= 0 && result.length() == total) return result
                if (rows.length() < pageSize) {
                    if (total > result.length()) { failed("Riwayat belum terbaca seluruhnya."); return null }
                    return result
                }
                if (objectRoot?.has("page") != true || !objectRoot.has("page_size")) {
                    failed("Informasi halaman riwayat belum dapat dibaca."); return null
                }
                previous = rows.toString()
                page++
            } catch (_: JSONException) { failed("Data riwayat belum dapat dibaca."); return null }
        }
    }

    private suspend fun get(path: String): String? {
        ActionLog.record(context, "api.read.start", path.substringBefore('?'))
        failureState = SessionState.UNKNOWN
        val version = session.state.value.version
        val saved = access ?: session.credentials()
        if (saved != null && !session.matches(saved)) return null
        try {
            var credential = saved ?: refresh(version, "missing") ?: return null
            access = credential
            var response = request("GET", path, credential.token)
            if (response.status == 401 && saved != null) {
                credential = refresh(credential.version, "unauthorized") ?: return null
                access = credential
                response = request("GET", path, credential.token)
            }
            if (!session.matches(credential)) return null
            ActionLog.record(context, "api.read.response", "status=${response.status}")
            if (response.status !in 200..299) { failed("Situs belum dapat memproses permintaan. Coba lagi."); return null }
            return if (response.status == 204) "[]" else response.body
        } catch (_: IOException) {
            id.ohmymonev.android.data.ActionLog.record(context, "api.read.failed", "io")
            failed("Aplikasi belum dapat menghubungi situs. Status login terakhir tidak berubah.")
        } catch (_: JSONException) {
            failed("Situs mengirim data login yang tidak dikenali.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            failed("Situs belum dapat diperiksa. Coba lagi.")
        }
        return null
    }

    private suspend fun refresh(version: Long, trigger: String): SessionCredentials? {
        val result = session.refresh(version) {
            ActionLog.record(context, "auth.refresh.start", "trigger=$trigger")
            val response = request("POST", "/auth/refresh", null)
            ActionLog.record(context, "auth.refresh.response", "status=${response.status}")
            when (response.status) {
                401 -> null
                in 200..299 -> {
                    val root = try { JSONObject(response.body) } catch (_: JSONException) {
                        throw IOException("Invalid refresh response")
                    }
                    root.optString("access_token").ifBlank {
                        root.optJSONObject("data")?.optString("access_token").orEmpty()
                    }.takeIf { it.isNotBlank() && it != "null" } ?: throw IOException("Missing access token")
                }
                else -> throw IOException("Refresh unavailable")
            }
        }
        when (result) {
            is SessionRefresh.Renewed -> {
                ActionLog.record(context, "auth.refresh.result", "renewed")
                return result.credentials
            }
            SessionRefresh.Expired -> {
                ActionLog.record(context, "auth.refresh.result", "expired")
                failureState = SessionState.EXPIRED
                message = "Sesi berakhir. Login kembali."
            }
            SessionRefresh.Unavailable -> {
                ActionLog.record(context, "auth.refresh.result", "unverified")
                failed("Login belum dapat diperiksa. Coba lagi.")
            }
        }
        return null
    }

    private suspend fun request(method: String, path: String, token: String?): ApiResponse {
        transport?.let { return it(method, path, token) }
        return MonevApiTransport.request(context, method, path, token)
    }

    private companion object {
        const val MAX_PAGES = 50
    }
}

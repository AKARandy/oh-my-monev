package id.ohmymonev.android.engine

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.SessionRecord
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.android.data.SessionTokenStore
import id.ohmymonev.core.enginecore.SessionState
import java.io.File
import java.net.SocketTimeoutException
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionAuthenticationTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(base.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$name-${directory.name}", mode)
    }
    private val key = SecretKeySpec(ByteArray(16) { (it + 1).toByte() }, "AES")
    private val vault = SessionTokenStore(context) { key }

    private suspend fun active(): SessionRepository = SessionRepository(context, vault).also {
        it.credentials()
        assertTrue(it.capture("synthetic-token-a", it.state.value.version))
        it.confirm(requireNotNull(it.credentials()))
    }

    @Test fun oldSessionMetadataCanBeReadAndClearedWithoutResurrectingIt() {
        File(directory, "session.json").writeText("""{"active":true,"checkedAtISO":"2020-01-01T00:00:00Z"}""")
        val store = AppStore(context)
        assertEquals(true, store.sessionStatus()?.active)
        store.clearSessionStatus()
        assertNull(AppStore(context).sessionStatus())
        store.saveSessionStatus(SessionRecord(false, "2026-11-07T00:00:00Z"))
        assertEquals(false, AppStore(context).sessionStatus()?.active)
    }

    @Test fun encryptedLoginSurvivesRepositoryRecreationWithoutNinetyMinuteExpiry() = runBlocking {
        vault.save("synthetic-token-a")
        AppStore(context).saveSessionStatus(SessionRecord(true, "2020-01-01T00:00:00Z"))
        val restarted = SessionRepository(context, SessionTokenStore(context) { key })
        assertEquals("synthetic-token-a", restarted.credentials()?.token)
        assertTrue(restarted.state.value.usable)
        assertFalse(context.getSharedPreferences("monev_session", Context.MODE_PRIVATE).getString("credential", "")!!.contains("synthetic-token-a"))
    }

    @Test fun timeoutPreservesConfirmedLoginAndSavedCredential() = runBlocking {
        val repository = active()
        val api = MonevApiClient(context, repository) { _, _, _ -> throw SocketTimeoutException() }
        assertNull(api.objectData("/users/me"))
        assertTrue(repository.state.value.usable)
        assertEquals(true, AppStore(context).sessionStatus()?.active)
        assertEquals("synthetic-token-a", vault.read())
        assertEquals(SessionState.UNKNOWN, api.failureState)
    }

    @Test fun serverErrorAndMalformedJsonDoNotLogTheUserOut() = runBlocking {
        val repository = active()
        val server = MonevApiClient(context, repository) { _, _, _ -> ApiResponse(503, "unavailable") }
        assertNull(server.objectData("/users/me"))
        val html = MonevApiClient(context, repository) { _, _, _ -> ApiResponse(200, "<html>loading</html>") }
        assertNull(html.objectData("/users/me"))
        assertTrue(repository.state.value.usable)
        assertEquals(true, AppStore(context).sessionStatus()?.active)
    }

    @Test fun unauthorizedAndRejectedRefreshRequireLogin() = runBlocking {
        val repository = active()
        val calls = mutableListOf<Pair<String, String?>>()
        val api = MonevApiClient(context, repository) { _, path, token ->
            calls += path to token
            ApiResponse(401, "{}")
        }
        assertNull(api.objectData("/users/me"))
        assertEquals(listOf("/users/me" to "synthetic-token-a", "/auth/refresh" to null), calls)
        assertEquals(SessionState.EXPIRED, api.failureState)
        assertEquals(false, repository.state.value.record?.active)
        assertNull(vault.read())
        assertFalse(repository.capture("synthetic-token-a", repository.state.value.version))
    }

    @Test fun unavailableRefreshKeepsThePreviousLogin() = runBlocking {
        val repository = active()
        val api = MonevApiClient(context, repository) { _, path, _ -> ApiResponse(if (path == "/users/me") 401 else 503, "{}") }
        assertNull(api.objectData("/users/me"))
        assertEquals(SessionState.UNKNOWN, api.failureState)
        assertTrue(repository.state.value.usable)
    }

    @Test fun refreshedCredentialIsUsedForTheRetriedApiRequest() = runBlocking {
        val repository = active()
        val api = MonevApiClient(context, repository) { _, path, token ->
            when {
                path == "/auth/refresh" -> ApiResponse(200, """{"access_token":"synthetic-token-b"}""")
                token == "synthetic-token-a" -> ApiResponse(401, "{}")
                else -> ApiResponse(200, """{"data":{"id":"00000000-0000-0000-0000-000000000001"}}""")
            }
        }
        assertNotNull(api.objectData("/users/me"))
        api.confirmActive()
        assertEquals("synthetic-token-b", vault.read())
        assertTrue(repository.state.value.usable)
    }

    @Test fun aLateFailureCannotOverwriteANewerLogin() = runBlocking {
        val repository = active()
        val old = requireNotNull(repository.credentials())
        assertTrue(repository.capture("synthetic-token-b", old.version))
        repository.confirm(requireNotNull(repository.credentials()))
        assertFalse(repository.reject(old))
        repository.uncertain(old, "old timeout")
        assertFalse(repository.capture("synthetic-token-a", old.version))
        assertTrue(repository.state.value.usable)
        assertNull(repository.state.value.issue)
        assertEquals("synthetic-token-b", vault.read())
    }

    @Test fun anOldApiResponseCannotRefreshOrRejectANewerLogin() = runBlocking {
        val repository = active()
        var requests = 0
        val api = MonevApiClient(context, repository) { _, _, _ ->
            requests++
            assertEquals(1, requests)
            val old = requireNotNull(repository.credentials())
            repository.capture("synthetic-token-b", old.version)
            repository.confirm(requireNotNull(repository.credentials()))
            ApiResponse(401, "{}")
        }
        assertNull(api.objectData("/users/me"))
        assertEquals(1, requests)
        assertTrue(repository.state.value.usable)
        assertEquals("synthetic-token-b", vault.read())
    }

    @Test fun missingCredentialDoesNotTurnAnOldConfirmationIntoLogout() = runBlocking {
        AppStore(context).saveSessionStatus(SessionRecord(true, "2020-01-01T00:00:00Z"))
        val repository = SessionRepository(context, vault)
        val api = MonevApiClient(context, repository) { _, _, _ -> error("No request without a token") }
        assertNull(api.objectData("/users/me"))
        assertEquals(true, repository.state.value.record?.active)
        assertFalse(repository.state.value.usable)
        assertEquals(true, AppStore(context).sessionStatus()?.active)
    }

    @Test fun locationRequiresPrecisePermissionAndTrustedMonevOrigin() {
        val app = base as Application
        val permissions = org.robolectric.Shadows.shadowOf(app)
        val client = SessionWebView.locationClient(context)
        val origin = id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE.urls.baseUrl
        fun allowed(requestOrigin: String): Boolean {
            var granted = false
            client.onGeolocationPermissionsShowPrompt(requestOrigin) { _, allow, retain ->
                assertFalse(retain)
                granted = allow
            }
            return granted
        }
        permissions.denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        assertFalse(allowed(origin))
        permissions.grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(allowed(origin))
        assertFalse(allowed(origin.replace("https:", "http:")))
        assertFalse(allowed("https://example.test"))
        assertFalse(allowed("$origin:444"))
    }

}

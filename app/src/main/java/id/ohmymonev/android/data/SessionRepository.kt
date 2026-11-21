package id.ohmymonev.android.data

import android.content.Context
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SessionSnapshot(
    val record: SessionRecord? = null,
    val hasToken: Boolean = false,
    val issue: String? = null,
    val initializing: Boolean = false,
    val version: Long = 0,
    val signedOut: Boolean = false,
) {
    val usable: Boolean get() = record?.active == true && hasToken
}

internal class SessionCredentials(val token: String, val version: Long)

internal sealed interface SessionRefresh {
    data class Renewed(val credentials: SessionCredentials) : SessionRefresh
    data object Expired : SessionRefresh
    data object Unavailable : SessionRefresh
}

internal class SessionRepository(private val context: Context, private val vault: SessionTokenStore = SessionTokenStore(context)) {
    private val store = AppStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private var refreshFlight: Pair<Long, Deferred<SessionRefresh>>? = null
    private val loaded = CompletableDeferred<Unit>()
    @Volatile private var token: String? = null
    private var rejectedToken: String? = null
    private val mutable = MutableStateFlow(SessionSnapshot(initializing = true))
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                token = vault.read()
                mutable.value = SessionSnapshot(store.sessionStatus(), token != null, signedOut = vault.signedOut())
            }
            loaded.complete(Unit)
        }
    }

    suspend fun awaitLoaded() {
        loaded.await()
    }

    suspend fun credentials(): SessionCredentials? {
        loaded.await()
        return mutex.withLock { token?.let { SessionCredentials(it, mutable.value.version) } }
    }

    suspend fun capture(
        value: String,
        expectedVersion: Long,
        preserveConfirmation: Boolean = false,
        afterLogin: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            val snapshot = mutable.value
            if (value.isBlank() || value == token || (!afterLogin && value == rejectedToken) ||
                snapshot.version != expectedVersion || (snapshot.signedOut && !afterLogin)
            ) return@withLock false
            val previousToken = token
            try { vault.save(value) } catch (_: Exception) {
                mutable.value = mutable.value.copy(issue = "Login belum dapat disimpan. Coba Cek sesi kembali.")
                return@withLock false
            }
            if (!preserveConfirmation) {
                try {
                    store.clearSessionStatus()
                } catch (_: IOException) {
                    if (previousToken != null) runCatching { vault.save(previousToken) }
                    else runCatching { vault.clear(snapshot.signedOut) }
                    mutable.value = mutable.value.copy(issue = "Login belum dapat disimpan. Coba Cek sesi kembali.")
                    return@withLock false
                }
                id.ohmymonev.android.schedule.WorkDaysStore(context).clear()
            }
            rejectedToken = null
            token = value
            mutable.value = SessionSnapshot(if (preserveConfirmation) mutable.value.record else null, hasToken = true, version = expectedVersion + 1)
            true
        }
    }

    /** An auth request may rotate the cookie even if one caller stops waiting. */
    suspend fun refresh(expectedVersion: Long, fetchToken: suspend () -> String?): SessionRefresh {
        loaded.await()
        val flight = refreshMutex.withLock {
            val snapshot = mutable.value
            if (snapshot.signedOut) return SessionRefresh.Expired
            refreshFlight?.let { (version, task) ->
                if (version == expectedVersion && (!task.isCompleted || snapshot.version != version)) {
                    return@withLock task
                }
            }
            if (snapshot.version != expectedVersion) return SessionRefresh.Unavailable
            scope.async { renew(expectedVersion, snapshot.hasToken, fetchToken) }.also {
                refreshFlight = expectedVersion to it
            }
        }
        val result = flight.await()
        val current = mutable.value
        return when (result) {
            is SessionRefresh.Renewed -> result.takeIf { matches(it.credentials) } ?: SessionRefresh.Unavailable
            SessionRefresh.Expired -> result.takeIf {
                current.version in expectedVersion..(expectedVersion + 1) && !current.hasToken && current.record?.active == false
            }
                ?: SessionRefresh.Unavailable
            SessionRefresh.Unavailable -> result
        }
    }

    private suspend fun renew(
        expectedVersion: Long,
        preserveConfirmation: Boolean,
        fetchToken: suspend () -> String?,
    ): SessionRefresh = try {
        withTimeoutOrNull(20_000) {
            val value = fetchToken()
            if (value == null) {
                if (reject(expectedVersion)) SessionRefresh.Expired else SessionRefresh.Unavailable
            } else {
                val saved = capture(value, expectedVersion, preserveConfirmation)
                val version = if (saved) expectedVersion + 1 else expectedVersion
                credentials()?.takeIf { it.version == version && it.token == value }
                    ?.let { SessionRefresh.Renewed(it) } ?: SessionRefresh.Unavailable
            }
        } ?: SessionRefresh.Unavailable
    } catch (_: IOException) {
        SessionRefresh.Unavailable
    }

    suspend fun confirm(access: SessionCredentials) = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            if (!matches(access)) return@withLock
            val record = SessionRecord(true, OffsetDateTime.now().toString())
            try { store.saveSessionStatus(record) } catch (_: IOException) { return@withLock }
            mutable.value = mutable.value.copy(record = record, issue = null)
        }
    }

    suspend fun reject(expectedVersion: Long): Boolean = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            if (mutable.value.version != expectedVersion) return@withLock false
            invalidate(signedOut = false)
            true
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock { invalidate(signedOut = true) }
    }

    private fun invalidate(signedOut: Boolean) {
        val snapshot = mutable.value
        val message = "Sesi berakhir. Login kembali."
        if (!snapshot.hasToken && snapshot.record?.active == false && snapshot.signedOut == signedOut) {
            mutable.value = snapshot.copy(issue = message)
            return
        }
        val previousToken = token
        val previousRecord = snapshot.record
        val record = SessionRecord(false, OffsetDateTime.now().toString())
        try {
            vault.clear(signedOut)
            store.saveSessionStatus(record)
        } catch (_: IOException) {
            if (previousToken != null) runCatching { vault.save(previousToken) }
            if (previousRecord != null) runCatching { store.saveSessionStatus(previousRecord) }
            mutable.value = snapshot.copy(issue = message)
            return
        }
        rejectedToken = previousToken
        token = null
        mutable.value = SessionSnapshot(record, issue = message, version = snapshot.version + 1, signedOut = signedOut)
    }

    suspend fun uncertain(access: SessionCredentials?, message: String) {
        loaded.await()
        mutex.withLock {
            if ((access == null && token == null) || (access != null && matches(access))) mutable.value = mutable.value.copy(issue = message)
        }
    }

    fun matches(access: SessionCredentials): Boolean = mutable.value.version == access.version && token == access.token

    companion object {
        @Volatile private var instance: SessionRepository? = null
        fun get(context: Context): SessionRepository {
            val app = context.applicationContext
            return instance?.takeIf { it.context.applicationContext === app } ?: synchronized(this) {
                instance?.takeIf { it.context.applicationContext === app }
                    ?: SessionRepository(app).also { instance = it }
            }
        }
    }
}

package id.ohmymonev.android.data

import android.content.Context
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

internal class SessionRepository(
    private val context: Context,
    private val vault: SessionTokenStore = SessionTokenStore(context),
) {
    private sealed interface SessionPhase {
        fun toSnapshot(): SessionSnapshot
        val phaseVersion: Long
        val activeToken: String? get() = null
        val rejected: String? get() = null
        val statusRecord: SessionRecord? get() = null

        data object Initializing : SessionPhase {
            override val phaseVersion = 0L
            override fun toSnapshot() = SessionSnapshot(initializing = true)
        }

        data class SignedOut(
            override val phaseVersion: Long,
            override val statusRecord: SessionRecord?,
            override val rejected: String? = null,
            val issue: String? = null,
        ) : SessionPhase {
            override fun toSnapshot() = SessionSnapshot(
                record = statusRecord,
                hasToken = false,
                issue = issue,
                version = phaseVersion,
                signedOut = true,
            )
        }

        data class Expired(
            override val phaseVersion: Long,
            override val rejected: String?,
            override val statusRecord: SessionRecord?,
            val issue: String? = null,
        ) : SessionPhase {
            override fun toSnapshot() = SessionSnapshot(
                record = statusRecord,
                hasToken = false,
                issue = issue,
                version = phaseVersion,
                signedOut = false,
            )
        }

        data class Unverified(
            override val phaseVersion: Long,
            override val activeToken: String,
            override val statusRecord: SessionRecord? = null,
            val issue: String? = null,
        ) : SessionPhase {
            override fun toSnapshot() = SessionSnapshot(
                record = statusRecord,
                hasToken = true,
                issue = issue,
                version = phaseVersion,
                signedOut = false,
            )
        }

        data class Active(
            override val phaseVersion: Long,
            override val activeToken: String,
            override val statusRecord: SessionRecord,
            val issue: String? = null,
        ) : SessionPhase {
            override fun toSnapshot() = SessionSnapshot(
                record = statusRecord,
                hasToken = true,
                issue = issue,
                version = phaseVersion,
                signedOut = false,
            )
        }

        fun withIssue(issue: String?): SessionPhase = when (this) {
            Initializing -> Initializing
            is SignedOut -> copy(issue = issue)
            is Expired -> copy(issue = issue)
            is Unverified -> copy(issue = issue)
            is Active -> copy(issue = issue)
        }
    }

    private val store = AppStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private var refreshFlight: Pair<Long, Deferred<SessionRefresh>>? = null
    private val loaded = CompletableDeferred<Unit>()
    @Volatile private var phase: SessionPhase = SessionPhase.Initializing
    private val mutable = MutableStateFlow(SessionPhase.Initializing.toSnapshot())
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                val stored = vault.read()
                val status = store.sessionStatus()
                val signedOut = vault.signedOut()
                setPhase(
                    when {
                        signedOut -> SessionPhase.SignedOut(0, status)
                        stored != null && status?.active == true -> SessionPhase.Active(0, stored, status)
                        stored != null -> SessionPhase.Unverified(0, stored, status)
                        else -> SessionPhase.Expired(0, null, status)
                    },
                )
            }
            loaded.complete(Unit)
        }
    }

    private fun setPhase(next: SessionPhase) {
        phase = next
        mutable.value = next.toSnapshot()
    }

    suspend fun awaitLoaded() {
        loaded.await()
    }

    suspend fun credentials(): SessionCredentials? {
        loaded.await()
        return mutex.withLock {
            phase.activeToken?.let { SessionCredentials(it, phase.phaseVersion) }
        }
    }

    suspend fun capture(
        value: String,
        expectedVersion: Long,
        preserveConfirmation: Boolean = false,
        afterLogin: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            val current = phase
            if (current is SessionPhase.Initializing || value.isBlank() ||
                value == current.activeToken || (!afterLogin && value == current.rejected) ||
                current.phaseVersion != expectedVersion || (current is SessionPhase.SignedOut && !afterLogin)
            ) return@withLock false
            val previousToken = current.activeToken
            try {
                vault.save(value)
            } catch (_: Exception) {
                setPhase(current.withIssue("Login belum dapat disimpan. Coba Cek sesi kembali."))
                return@withLock false
            }
            if (!preserveConfirmation) {
                try {
                    store.clearSessionStatus()
                } catch (_: IOException) {
                    if (previousToken != null) runCatching { vault.save(previousToken) }
                    else runCatching { vault.clear(current is SessionPhase.SignedOut) }
                    setPhase(current.withIssue("Login belum dapat disimpan. Coba Cek sesi kembali."))
                    return@withLock false
                }
                id.ohmymonev.android.schedule.WorkDaysStore(context).clear()
            }
            val nextVersion = expectedVersion + 1
            val preservedRecord = if (preserveConfirmation) current.statusRecord else null
            setPhase(
                if (preservedRecord?.active == true) SessionPhase.Active(nextVersion, value, preservedRecord)
                else SessionPhase.Unverified(nextVersion, value, preservedRecord),
            )
            true
        }
    }

    /** An auth request may rotate the cookie even if one caller stops waiting. */
    suspend fun refresh(expectedVersion: Long, fetchToken: suspend () -> String?): SessionRefresh {
        loaded.await()
        val flight = refreshMutex.withLock {
            val current = phase
            if (current is SessionPhase.SignedOut) return SessionRefresh.Expired
            refreshFlight?.let { (version, task) ->
                if (version == expectedVersion && (!task.isCompleted || current.phaseVersion != version)) {
                    return@withLock task
                }
            }
            if (current.phaseVersion != expectedVersion) return SessionRefresh.Unavailable
            scope.async { renew(expectedVersion, current.activeToken != null, fetchToken) }.also {
                refreshFlight = expectedVersion to it
            }
        }
        val result = flight.await()
        val current = phase
        return when (result) {
            is SessionRefresh.Renewed -> result.takeIf { matches(it.credentials) } ?: SessionRefresh.Unavailable
            SessionRefresh.Expired -> result.takeIf {
                current is SessionPhase.Expired &&
                    current.phaseVersion in expectedVersion..(expectedVersion + 1) &&
                    current.statusRecord?.active == false
            } ?: SessionRefresh.Unavailable
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
            try {
                store.saveSessionStatus(record)
            } catch (_: IOException) {
                return@withLock
            }
            setPhase(SessionPhase.Active(access.version, access.token, record))
        }
    }

    suspend fun reject(expectedVersion: Long): Boolean = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            if (phase.phaseVersion != expectedVersion) return@withLock false
            invalidate(signedOut = false)
            true
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock { invalidate(signedOut = true) }
    }

    private fun invalidate(signedOut: Boolean) {
        val current = phase
        val message = "Sesi berakhir. Login kembali."
        val alreadySameInvalidState = when (current) {
            is SessionPhase.SignedOut -> signedOut && current.statusRecord?.active == false
            is SessionPhase.Expired -> !signedOut && current.statusRecord?.active == false
            else -> false
        }
        if (alreadySameInvalidState) {
            setPhase(current.withIssue(message))
            return
        }
        val previousToken = current.activeToken
        val previousRecord = current.statusRecord
        val record = SessionRecord(false, OffsetDateTime.now().toString())
        try {
            vault.clear(signedOut)
            store.saveSessionStatus(record)
        } catch (_: IOException) {
            if (previousToken != null) runCatching { vault.save(previousToken) }
            if (previousRecord != null) runCatching { store.saveSessionStatus(previousRecord) }
            setPhase(current.withIssue(message))
            return
        }
        val nextVersion = current.phaseVersion + 1
        setPhase(
            if (signedOut) SessionPhase.SignedOut(nextVersion, record, previousToken, message)
            else SessionPhase.Expired(nextVersion, previousToken, record, message),
        )
    }

    suspend fun uncertain(access: SessionCredentials?, message: String) {
        loaded.await()
        mutex.withLock {
            val current = phase
            if ((access == null && current.activeToken == null) || (access != null && matches(access))) {
                setPhase(current.withIssue(message))
            }
        }
    }

    fun matches(access: SessionCredentials): Boolean {
        val current = phase
        return current.phaseVersion == access.version && current.activeToken == access.token
    }

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

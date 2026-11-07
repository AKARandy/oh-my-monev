package id.ohmymonev.android.data

import android.content.Context
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SessionSnapshot(
    val record: SessionRecord? = null,
    val hasToken: Boolean = false,
    val issue: String? = null,
    val initializing: Boolean = false,
    val version: Long = 0,
) {
    val usable: Boolean get() = record?.active == true && hasToken
}

internal class SessionCredentials(val token: String, val version: Long)

internal class SessionRepository(context: Context, private val vault: SessionTokenStore = SessionTokenStore(context)) {
    private val store = AppStore(context)
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private var token: String? = null
    private var rejectedToken: String? = null
    private val mutable = MutableStateFlow(SessionSnapshot(initializing = true))
    val state = mutable.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            token = vault.read()
            mutable.value = SessionSnapshot(store.sessionStatus(), token != null)
            loaded.complete(Unit)
        }
    }

    suspend fun credentials(): SessionCredentials? {
        loaded.await()
        return mutex.withLock { token?.let { SessionCredentials(it, mutable.value.version) } }
    }

    suspend fun capture(value: String, expectedVersion: Long, preserveConfirmation: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            if (value.isBlank() || value == token || value == rejectedToken || mutable.value.version != expectedVersion) return@withLock false
            try { vault.save(value) } catch (_: Exception) {
                mutable.value = mutable.value.copy(issue = "Login belum dapat disimpan. Coba Cek sesi kembali.")
                return@withLock false
            }
            if (!preserveConfirmation) store.clearSessionStatus()
            token = value
            mutable.value = SessionSnapshot(if (preserveConfirmation) mutable.value.record else null, hasToken = true, version = expectedVersion + 1)
            true
        }
    }

    suspend fun confirm(access: SessionCredentials) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!matches(access)) return@withLock
            val record = SessionRecord(true, OffsetDateTime.now().toString())
            store.saveSessionStatus(record)
            mutable.value = mutable.value.copy(record = record, issue = null)
        }
    }

    suspend fun reject(access: SessionCredentials): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!matches(access)) return@withLock false
            rejectedToken = token
            vault.clear()
            token = null
            val record = SessionRecord(false, OffsetDateTime.now().toString())
            store.saveSessionStatus(record)
            mutable.value = SessionSnapshot(record, issue = "Sesi berakhir. Login kembali.", version = access.version + 1)
            true
        }
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
        fun get(context: Context): SessionRepository = instance ?: synchronized(this) {
            instance ?: SessionRepository(context.applicationContext).also { instance = it }
        }
    }
}

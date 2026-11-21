package id.ohmymonev.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import id.ohmymonev.android.BuildConfig
import id.ohmymonev.core.update.AppUpdateParser
import id.ohmymonev.core.update.UpdateRelease
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AppUpdatePhase {
    data object Idle : AppUpdatePhase
    data object Checking : AppUpdatePhase
    data object UpToDate : AppUpdatePhase
    data class Available(val release: UpdateRelease) : AppUpdatePhase
    data class Downloading(val release: UpdateRelease, val percent: Int) : AppUpdatePhase
    data class ReadyToInstall(
        val release: UpdateRelease,
        val apkFile: File,
        val needsPermissionHint: Boolean = false,
    ) : AppUpdatePhase
    data class Failed(val message: String, val release: UpdateRelease? = null) : AppUpdatePhase
}

internal interface UpdateTransport {
    suspend fun fetchText(url: String, maxBytes: Int = 256 * 1024): String
    suspend fun download(
        url: String,
        target: File,
        expectedSizeBytes: Long,
        maxBytes: Long,
        onProgress: (Int) -> Unit,
    ): String
}

internal object HttpUpdateTransport : UpdateTransport {
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private suspend fun openTrustedConnection(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) {
            currentCoroutineContext().ensureActive()
            check(AppUpdateParser.isTrustedDownloadUrl(currentUrl)) {
                "URL pembaruan tidak valid."
            }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "OhMyMonev/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code in 301..303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                check(!location.isNullOrBlank()) { "Pengalihan unduhan tidak valid." }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                throw IOException("Server pembaruan merespons $code.")
            }
        }
        throw IOException("Terlalu banyak pengalihan saat mengunduh pembaruan.")
    }

    override suspend fun fetchText(url: String, maxBytes: Int): String {
        val conn = openTrustedConnection(url)
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) kotlin.concurrent.thread(isDaemon = true) { runCatching { conn.disconnect() } }
        }
        try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw IOException("Respons server terlalu besar.")
                    out.write(buf, 0, n)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            cancelHandle?.dispose()
            conn.disconnect()
        }
    }

    override suspend fun download(
        url: String,
        target: File,
        expectedSizeBytes: Long,
        maxBytes: Long,
        onProgress: (Int) -> Unit,
    ): String {
        val conn = openTrustedConnection(url)
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) kotlin.concurrent.thread(isDaemon = true) { runCatching { conn.disconnect() } }
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: expectedSizeBytes
            if (total > maxBytes) throw IOException("Ukuran berkas APK melebihi batas aman.")
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var downloaded = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        downloaded += n
                        if (downloaded > maxBytes) throw IOException("Ukuran berkas APK melebihi batas aman.")
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        if (total > 0L) {
                            val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            cancelHandle?.dispose()
            conn.disconnect()
        }
    }
}

/** Process-scoped in-app APK updater backed by GitHub Releases with mandatory SHA-256 verification. */
object AppUpdateController {
    private const val PREFS_NAME = "app_update"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_DISMISSED_TAG = "dismissed_tag"
    private const val UPDATES_DIR = "updates"
    private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val MAX_APK_BYTES = 100L * 1024L * 1024L

    private const val STABLE_RELEASE_URL = "https://api.github.com/repos/AKARandy/oh-my-monev/releases/latest"
    private const val DEV_RELEASE_URL = "https://api.github.com/repos/AKARandy/oh-my-monev/releases/tags/dev"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<AppUpdatePhase>(AppUpdatePhase.Idle)
    val state: StateFlow<AppUpdatePhase> = _state.asStateFlow()

    @Volatile private var activeJob: Job? = null
    @Volatile private var transport: UpdateTransport = HttpUpdateTransport
    @Volatile private var nowMs: () -> Long = System::currentTimeMillis
    @Volatile private var currentVersion: () -> String = { BuildConfig.VERSION_NAME }
    @Volatile private var isDevBuild: () -> Boolean = { BuildConfig.DEBUG }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun updatesDir(context: Context): File =
        File(context.applicationContext.cacheDir, UPDATES_DIR)

    fun checkIfDue(context: Context) {
        val current = _state.value
        if (current is AppUpdatePhase.Checking ||
            current is AppUpdatePhase.Available ||
            current is AppUpdatePhase.Downloading ||
            current is AppUpdatePhase.ReadyToInstall
        ) return
        val appContext = context.applicationContext
        val lastCheck = prefs(appContext).getLong(KEY_LAST_CHECK_MS, 0L)
        if (nowMs() - lastCheck < CHECK_INTERVAL_MS) return
        launchCheck(appContext, silent = true)
    }

    fun checkNow(context: Context) {
        val current = _state.value
        if (current is AppUpdatePhase.Checking || current is AppUpdatePhase.Downloading) return
        val appContext = context.applicationContext
        prefs(appContext).edit().remove(KEY_DISMISSED_TAG).apply()
        launchCheck(appContext, silent = false)
    }

    fun dismiss(context: Context, tag: String) {
        prefs(context.applicationContext).edit().putString(KEY_DISMISSED_TAG, tag).apply()
        _state.value = AppUpdatePhase.Idle
    }

    fun clearFailure() {
        if (_state.value is AppUpdatePhase.Failed) {
            _state.value = AppUpdatePhase.Idle
        }
    }

    private fun launchCheck(appContext: Context, silent: Boolean) {
        if (!silent) _state.value = AppUpdatePhase.Checking
        lateinit var job: Job
        job = scope.launch {
            try {
                cleanupStaleApks(appContext)
                val dev = isDevBuild()
                val endpoint = if (dev) DEV_RELEASE_URL else STABLE_RELEASE_URL
                val body = transport.fetchText(endpoint)
                prefs(appContext).edit().putLong(KEY_LAST_CHECK_MS, nowMs()).apply()
                val release = AppUpdateParser.parseGitHubRelease(body, isDevBuild = dev)
                if (release != null && AppUpdateParser.isNewer(currentVersion(), release)) {
                    val dismissed = prefs(appContext).getString(KEY_DISMISSED_TAG, null)
                    if (silent && dismissed == release.tag) {
                        _state.value = AppUpdatePhase.Idle
                    } else {
                        val existing = existingVerifiedApk(appContext, release)
                        _state.value = if (existing != null) {
                            AppUpdatePhase.ReadyToInstall(release, existing)
                        } else {
                            AppUpdatePhase.Available(release)
                        }
                    }
                } else {
                    _state.value = if (silent) AppUpdatePhase.Idle else AppUpdatePhase.UpToDate
                }
            } catch (_: CancellationException) {
                if (!silent && _state.value is AppUpdatePhase.Checking) _state.value = AppUpdatePhase.Idle
            } catch (e: Exception) {
                if (silent) {
                    if (_state.value is AppUpdatePhase.Checking) _state.value = AppUpdatePhase.Idle
                } else {
                    _state.value = AppUpdatePhase.Failed(
                        e.message?.takeIf { it.isNotBlank() } ?: "Gagal memeriksa pembaruan. Coba lagi.",
                    )
                }
            } finally {
                if (activeJob === job) activeJob = null
            }
        }
        activeJob = job
    }

    fun startDownload(context: Context, release: UpdateRelease) {
        if (_state.value is AppUpdatePhase.Downloading) return
        val appContext = context.applicationContext
        _state.value = AppUpdatePhase.Downloading(release, 0)
        lateinit var job: Job
        job = scope.launch {
            val dir = updatesDir(appContext).apply { mkdirs() }
            val safeName = File(release.apkName).name.ifBlank { "update.apk" }
            val partFile = File(dir, "$safeName.part")
            val finalFile = File(dir, safeName)
            try {
                partFile.delete()
                val expectedSha = release.expectedSha256
                    ?: release.sha256Url?.let { url ->
                        AppUpdateParser.parseSha256Sums(transport.fetchText(url, 32 * 1024), safeName)
                    }
                    ?: throw IOException("Checksum SHA-256 tidak ditemukan. Unduhan dibatalkan.")

                val actualSha = transport.download(
                    url = release.apkUrl,
                    target = partFile,
                    expectedSizeBytes = release.apkSizeBytes,
                    maxBytes = MAX_APK_BYTES,
                ) { percent ->
                    _state.update { current ->
                        if (current is AppUpdatePhase.Downloading) current.copy(percent = percent) else current
                    }
                }
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    partFile.delete()
                    throw IOException("Verifikasi berkas gagal. Unduhan dibatalkan.")
                }
                finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.delete()
                    throw IOException("Berkas pembaruan tidak dapat disimpan.")
                }
                _state.value = AppUpdatePhase.ReadyToInstall(release, finalFile)
            } catch (_: CancellationException) {
                partFile.delete()
                _state.value = AppUpdatePhase.Available(release)
            } catch (e: Exception) {
                partFile.delete()
                _state.value = AppUpdatePhase.Failed(
                    message = e.message?.takeIf { it.isNotBlank() } ?: "Unduhan gagal. Coba lagi.",
                    release = release,
                )
            } finally {
                if (activeJob === job) activeJob = null
            }
        }
        activeJob = job
    }

    fun cancelDownload() {
        val current = _state.value as? AppUpdatePhase.Downloading ?: return
        val job = activeJob
        activeJob = null
        job?.cancel()
        _state.value = AppUpdatePhase.Available(current.release)
    }

    /**
     * Launches Android's package installer for [apkFile], or opens Unknown Sources Settings
     * if the user hasn't granted install permission yet. Returns true when installer launched.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.isFile) {
            val rel = (_state.value as? AppUpdatePhase.ReadyToInstall)?.release
            _state.value = AppUpdatePhase.Failed("Berkas pembaruan hilang. Unduh ulang.", rel)
            return false
        }
        val appContext = context.applicationContext
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            _state.update { current ->
                if (current is AppUpdatePhase.ReadyToInstall) current.copy(needsPermissionHint = true) else current
            }
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return false
        }
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    private fun existingVerifiedApk(appContext: Context, release: UpdateRelease): File? {
        val expected = release.expectedSha256 ?: return null
        val safeName = File(release.apkName).name
        val candidate = File(updatesDir(appContext), safeName)
        if (!candidate.isFile || candidate.length() <= 0L) return null
        val actual = runCatching { sha256(candidate) }.getOrNull() ?: return null
        return candidate.takeIf { actual.equals(expected, ignoreCase = true) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cleanupStaleApks(appContext: Context) {
        val dir = updatesDir(appContext)
        if (!dir.isDirectory) return
        val cutoff = nowMs() - 7L * 24L * 60L * 60L * 1000L
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".part") || file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    internal fun resetForTest(
        transport: UpdateTransport = HttpUpdateTransport,
        nowMs: () -> Long = System::currentTimeMillis,
        currentVersion: () -> String = { BuildConfig.VERSION_NAME },
        isDevBuild: () -> Boolean = { BuildConfig.DEBUG },
    ) {
        activeJob?.cancel()
        activeJob = null
        this.transport = transport
        this.nowMs = nowMs
        this.currentVersion = currentVersion
        this.isDevBuild = isDevBuild
        _state.value = AppUpdatePhase.Idle
    }
}

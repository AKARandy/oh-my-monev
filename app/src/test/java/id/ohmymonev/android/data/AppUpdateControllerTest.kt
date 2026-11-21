package id.ohmymonev.android.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.ohmymonev.core.update.UpdateRelease
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppUpdateControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        AppUpdateController.resetForTest()
        context.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.cacheDir, "updates").deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun waitUntil(predicate: (AppUpdatePhase) -> Boolean): AppUpdatePhase {
        repeat(200) {
            val value = AppUpdateController.state.value
            if (predicate(value)) return value
            delay(10)
        }
        error("Timed out waiting for phase; current=${AppUpdateController.state.value}")
    }

    @Test
    fun checkIfDueThrottlesForSixHoursAndIgnoresSilentErrors() = runBlocking {
        var fetches = 0
        var clock = 10_000_000_000L
        val fake = object : UpdateTransport {
            override suspend fun fetchText(url: String, maxBytes: Int): String {
                fetches++
                return """{"tag_name":"v1.1.0","assets":[{"name":"oh-my-monev-v1.1.0.apk","size":10,"browser_download_url":"https://github.com/AKARandy/oh-my-monev/releases/download/v1.1.0/oh-my-monev-v1.1.0.apk"}]}"""
            }
            override suspend fun download(url: String, target: File, expectedSizeBytes: Long, maxBytes: Long, onProgress: (Int) -> Unit) = ""
        }
        AppUpdateController.resetForTest(
            transport = fake,
            nowMs = { clock },
            currentVersion = { "1.1.0" },
            isDevBuild = { false },
        )

        AppUpdateController.checkIfDue(context)
        delay(80)
        assertEquals(1, fetches)
        assertEquals(AppUpdatePhase.Idle, AppUpdateController.state.value)

        // Within 6 hours -> throttled, no second fetch.
        clock += 60_000L
        AppUpdateController.checkIfDue(context)
        delay(80)
        assertEquals(1, fetches)

        // After 6 hours -> allowed again.
        clock += 6L * 60L * 60L * 1000L
        AppUpdateController.checkIfDue(context)
        delay(80)
        assertEquals(2, fetches)
    }

    @Test
    fun downloadVerifiesSha256AndTransitionsToReadyToInstall() = runBlocking {
        val apkBytes = "fake-signed-apk-content".toByteArray()
        val expectedHash = sha256(apkBytes)
        val release = UpdateRelease(
            tag = "v1.2.0",
            versionName = "1.2.0",
            notes = "Pembaruan baru.",
            apkUrl = "https://github.com/AKARandy/oh-my-monev/releases/download/v1.2.0/oh-my-monev-v1.2.0.apk",
            apkName = "oh-my-monev-v1.2.0.apk",
            apkSizeBytes = apkBytes.size.toLong(),
            expectedSha256 = expectedHash,
            sha256Url = null,
        )
        val fake = object : UpdateTransport {
            override suspend fun fetchText(url: String, maxBytes: Int) = ""
            override suspend fun download(
                url: String,
                target: File,
                expectedSizeBytes: Long,
                maxBytes: Long,
                onProgress: (Int) -> Unit,
            ): String {
                onProgress(50)
                target.writeBytes(apkBytes)
                onProgress(100)
                return expectedHash
            }
        }
        AppUpdateController.resetForTest(transport = fake, currentVersion = { "1.1.0" }, isDevBuild = { false })

        AppUpdateController.startDownload(context, release)
        val phase = waitUntil { it is AppUpdatePhase.ReadyToInstall } as AppUpdatePhase.ReadyToInstall
        assertEquals("v1.2.0", phase.release.tag)
        assertTrue(phase.apkFile.isFile)
        assertEquals(apkBytes.size.toLong(), phase.apkFile.length())
    }

    @Test
    fun sha256MismatchDeletesPartialFileAndFails() = runBlocking {
        val release = UpdateRelease(
            tag = "v1.2.0",
            versionName = "1.2.0",
            notes = "",
            apkUrl = "https://github.com/AKARandy/oh-my-monev/releases/download/v1.2.0/oh-my-monev-v1.2.0.apk",
            apkName = "oh-my-monev-v1.2.0.apk",
            apkSizeBytes = 10L,
            expectedSha256 = "a".repeat(64),
            sha256Url = null,
        )
        val fake = object : UpdateTransport {
            override suspend fun fetchText(url: String, maxBytes: Int) = ""
            override suspend fun download(
                url: String,
                target: File,
                expectedSizeBytes: Long,
                maxBytes: Long,
                onProgress: (Int) -> Unit,
            ): String {
                target.writeBytes("corrupted".toByteArray())
                return "b".repeat(64)
            }
        }
        AppUpdateController.resetForTest(transport = fake, currentVersion = { "1.1.0" }, isDevBuild = { false })

        AppUpdateController.startDownload(context, release)
        val phase = waitUntil { it is AppUpdatePhase.Failed } as AppUpdatePhase.Failed
        assertTrue(phase.message.contains("Verifikasi berkas gagal"))
        val dir = File(context.cacheDir, "updates")
        assertFalse(File(dir, "oh-my-monev-v1.2.0.apk").exists())
        assertFalse(File(dir, "oh-my-monev-v1.2.0.apk.part").exists())
    }

    @Test
    fun manualCheckSurfacesNetworkError() = runBlocking {
        val fake = object : UpdateTransport {
            override suspend fun fetchText(url: String, maxBytes: Int): String = throw IOException("Tidak ada koneksi.")
            override suspend fun download(url: String, target: File, expectedSizeBytes: Long, maxBytes: Long, onProgress: (Int) -> Unit) = ""
        }
        AppUpdateController.resetForTest(transport = fake, currentVersion = { "1.1.0" }, isDevBuild = { false })

        AppUpdateController.checkNow(context)
        val phase = waitUntil { it is AppUpdatePhase.Failed } as AppUpdatePhase.Failed
        assertEquals("Tidak ada koneksi.", phase.message)
    }
}

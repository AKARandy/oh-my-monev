package id.ohmymonev.android.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelStoreTest {
    @Test fun freshDownload() = verify()
    @Test fun interruptedDownloadResumes() = verify(interrupt = true)
    @Test fun fullResponseReplacesPartial() = verify(interrupt = true, ignoreRange = true)
    @Test fun corruptCompletePartialRestarts() = verify(corrupt = true)

    private fun verify(interrupt: Boolean = false, ignoreRange: Boolean = false, corrupt: Boolean = false) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val archive = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                ModelStore.REQUIRED_FILES.forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(name.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        val midpoint = archive.size / 2
        val requests = mutableListOf<String>()
        val url = URL(null, "http://fixture/", object : URLStreamHandler() {
            override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                private var offset = 0
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode(): Int {
                    val range = getRequestProperty("Range").orEmpty()
                    requests += range
                    offset = if (ignoreRange || range.isEmpty()) 0 else midpoint
                    return if (offset > 0) 206 else 200
                }
                override fun getContentLengthLong() = (archive.size - offset).toLong()
                override fun getHeaderField(name: String) =
                    if (name == "Content-Range") "bytes $offset-${archive.lastIndex}/${archive.size}" else null
                override fun getInputStream() = archive.copyOfRange(offset,
                    if (interrupt && requests.size == 1) midpoint else archive.size).inputStream()
            }
        })
        val store = ModelStore(app, url, archive.size.toLong(), checksum)
        val part = File(app.filesDir, "model/indot5-paraphrase-fp16.zip.part")
        if (corrupt) {
            part.parentFile!!.mkdirs()
            part.writeBytes(ByteArray(archive.size))
            assertTrue(runCatching { store.download {} }.isFailure)
            assertFalse(part.exists())
            assertTrue(requests.isEmpty())
        }
        if (interrupt) {
            assertTrue(runCatching { store.download {} }.isFailure)
            assertEquals(midpoint.toLong(), part.length())
        }
        assertFalse(store.present())
        val progress = mutableListOf<Int>()
        store.download {
            assertFalse(store.present())
            progress += it
        }
        assertTrue(store.present())
        assertFalse(part.exists())
        assertEquals(100, progress.last())
        assertEquals(progress.distinct(), progress)
        assertEquals(if (interrupt) listOf("", "bytes=$midpoint-") else listOf(""), requests)
        store.delete()
        assertFalse(store.present())
    }
}

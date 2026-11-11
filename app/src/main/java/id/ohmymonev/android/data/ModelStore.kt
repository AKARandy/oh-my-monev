package id.ohmymonev.android.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Penyimpanan model neural mode Otomatis: satu zip fp16 (~845 MB) yang diunduh
 * pengguna lewat aplikasi ke filesDir/model/. Privat penuh — hapus aplikasi =
 * model ikut hilang (allowBackup=false). Unduhan bisa dilanjutkan dari posisi
 * terakhir (Range) dan diverifikasi SHA256 sebelum dibuka.
 */
class ModelStore internal constructor(
    private val context: Context,
    private val artifact: URL,
    private val size: Long,
    private val checksum: String,
) {
    constructor(context: Context) : this(context, URL(ARTIFACT_URL), SIZE_BYTES, SHA256)

    private val modelDir: File get() = File(context.filesDir, MODEL_DIR)
    private val zipPart: File get() = File(modelDir, "$ZIP_NAME.part")

    /** Zip sudah terunduh dan sudah dibuka lengkap (4 berkas siap pakai). */
    fun present(): Boolean = META_FILE in (modelDir.list()?.toSet() ?: emptySet()) &&
        REQUIRED_FILES.all { File(modelDir, it).isFile }

    suspend fun delete() = withContext(Dispatchers.IO) {
        check(downloadLock.tryLock()) { "Tunggu unduhan model selesai." }
        try {
            check(modelDir.deleteRecursively()) { "Model tidak dapat dihapus." }
        } finally {
            downloadLock.unlock()
        }
    }

    fun usedBytes(): Long =
        modelDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * Mengunduh (atau melanjutkan) zip model, memverifikasi SHA256, lalu membukanya.
     * onProgress memberi persen 0..100. Melempar IOException/IllegalState bila gagal.
     */
    suspend fun download(onProgress: suspend (Int) -> Unit) = withContext(Dispatchers.IO) {
        ActionLog.record(context, "model.download.start")
        downloadLock.withLock {
            if (present()) return@withLock
            check(modelDir.isDirectory || modelDir.mkdirs()) { "Penyimpanan model tidak dapat dibuka." }
            var lastProgress = -1
            suspend fun progress(value: Int) {
                if (value == lastProgress) return
                lastProgress = value
                onProgress(value)
            }
            try {
                if (zipPart.length() > size) {
                    check(zipPart.delete()) { "Berkas unduhan rusak tidak dapat dihapus." }
                }
                var offset = zipPart.length()
                if (offset < size) {
                    val connection = (artifact.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 30_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                        setRequestProperty("Accept-Encoding", "identity")
                        if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                    }
                    try {
                        val code = network { connection.responseCode }
                        when (code) {
                            HttpURLConnection.HTTP_OK -> offset = 0
                            HttpURLConnection.HTTP_PARTIAL -> {
                                val range = "bytes $offset-${size - 1}/$size"
                                check(connection.getHeaderField("Content-Range") == range) {
                                    "Bagian unduhan dari server tidak sesuai. Coba lagi."
                                }
                            }
                            else -> error("Server unduhan merespons $code. Coba lagi.")
                        }
                        val remaining = size - offset
                        val contentLength = connection.contentLengthLong
                        check(contentLength < 0 || contentLength == remaining) {
                            "Ukuran berkas dari server tidak sesuai. Coba lagi."
                        }
                        FileOutputStream(zipPart, offset > 0).use { output ->
                            network { connection.inputStream }.use { input ->
                                val buffer = ByteArray(64 * 1024)
                                progress((offset * 100 / size).toInt())
                                while (true) {
                                    ensureActive()
                                    val read = network { input.read(buffer) }
                                    if (read < 0) break
                                    check(offset + read <= size) { "Ukuran berkas dari server tidak sesuai. Coba lagi." }
                                    output.write(buffer, 0, read)
                                    offset += read
                                    if (offset < size) progress((offset * 100 / size).toInt())
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
                check(zipPart.length() == size) { "Unduhan belum lengkap. Coba lagi untuk melanjutkan." }
                progress(100)
                val digest = MessageDigest.getInstance("SHA-256")
                zipPart.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != checksum) {
                    check(zipPart.delete()) { "Berkas unduhan rusak tidak dapat dihapus." }
                    error("Verifikasi model gagal. Coba lagi untuk mengunduh ulang.")
                }
                ensureActive()
                unpack()
                zipPart.delete()
            } catch (_: ZipException) {
                error("Isi berkas model tidak dapat dibuka.")
            } catch (_: IOException) {
                error("Model tidak dapat disimpan. Periksa ruang penyimpanan.")
            }
            Unit
        }
    }

    private inline fun <T> network(action: () -> T): T = try {
        action()
    } catch (_: IOException) {
        error("Koneksi unduhan terputus. Coba lagi untuk melanjutkan.")
    }

    /** Membuka zip ke folder model; gagal apa pun membersihkan hasil setengah jadi. */
    private fun unpack() {
        val staging = File(modelDir, "staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            ZipInputStream(zipPart.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(staging, File(entry.name).name)
                    FileOutputStream(target).use { output -> zip.copyTo(output) }
                    entry = zip.nextEntry
                }
            }
            check(staging.listFiles()?.map { it.name }?.containsAll(REQUIRED_FILES) == true) {
                "Isi unduhan tidak lengkap."
            }
            val marker = File(modelDir, META_FILE)
            check(!marker.exists() || marker.delete()) { "Penyimpanan model tidak dapat diperbarui." }
            for (name in REQUIRED_FILES) {
                File(modelDir, name).delete()
                check(File(staging, name).renameTo(File(modelDir, name))) { "Gagal memindahkan $name." }
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    companion object {
        private val downloadLock = Mutex()
        private const val MODEL_DIR = "model"
        private const val ZIP_NAME = "indot5-paraphrase-fp16.zip"
        private const val META_FILE = "META.json"
        val REQUIRED_FILES = listOf(
            "encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx",
            "tokenizer.json", META_FILE,
        )
        const val VERSION = "fp16-v1"
        const val ARTIFACT_URL =
            "https://github.com/AKARandy/oh-my-monev/releases/download/model-fp16-v1/indot5-paraphrase-fp16.zip"
        const val SHA256 = "4ea77e803f5c9369d1efdde3f0a4e1bf5f9a54eb45e292e420a04a603a1b3170"
        const val SIZE_BYTES = 845_150_712L
    }
}

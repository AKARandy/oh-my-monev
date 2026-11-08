package id.ohmymonev.android.data

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Penyimpanan model neural mode Otomatis: satu zip fp16 (~845 MB) yang diunduh
 * pengguna lewat aplikasi ke filesDir/model/. Privat penuh — hapus aplikasi =
 * model ikut hilang (allowBackup=false). Unduhan bisa dilanjutkan dari posisi
 * terakhir (Range) dan diverifikasi SHA256 sebelum dibuka.
 */
class ModelStore(private val context: Context) {

    private val modelDir: File get() = File(context.filesDir, MODEL_DIR)
    private val zipPart: File get() = File(modelDir, "$ZIP_NAME.part")
    private val zipFile: File get() = File(modelDir, ZIP_NAME)

    /** Zip sudah terunduh dan sudah dibuka lengkap (4 berkas siap pakai). */
    fun present(): Boolean = META_FILE in (modelDir.list()?.toSet() ?: emptySet()) &&
        REQUIRED_FILES.all { File(modelDir, it).isFile }

    fun delete() {
        modelDir.deleteRecursively()
    }

    fun usedBytes(): Long =
        modelDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * Mengunduh (atau melanjutkan) zip model, memverifikasi SHA256, lalu membukanya.
     * onProgress memberi persen 0..100. Melempar IOException/IllegalState bila gagal.
     */
    suspend fun download(onProgress: (Int) -> Unit) {
        modelDir.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var downloaded = if (zipPart.isFile) zipPart.length() else 0L
        val connection = (URL(ARTIFACT_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (downloaded > 0L) setRequestProperty("Range", "bytes=$downloaded-")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("Server merespons $code.")
            }
            val expectedResume = code == HttpURLConnection.HTTP_PARTIAL
            if (!expectedResume && downloaded > 0L) downloaded = 0L // server tak mendukung lanjut-unduh
            val total = connection.contentLengthLong + if (expectedResume) downloaded else 0L
            if (total <= 0L) throw IllegalStateException("Ukuran unduhan tidak diketahui.")
            FileOutputStream(zipPart, expectedResume).use { output ->
                digest.reset()
                if (expectedResume) {
                    FileInputStream(zipPart).use { input ->
                        val buffer = ByteArray(1 shl 16)
                        var read: Int
                        var consumed = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            digest.update(buffer, 0, read)
                            consumed += read
                        }
                        check(consumed == downloaded) { "Bagian terunduh tidak konsisten." }
                    }
                }
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        onProgress(((downloaded * 100L) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(zipPart.length() == SIZE_BYTES) { "Ukuran unduhan tidak sesuai." }
            check(actual == SHA256) { "Checksum unduhan tidak cocok." }
            check(zipPart.renameTo(zipFile)) { "Gagal menyelesaikan berkas unduhan." }
            unpack()
        } finally {
            connection.disconnect()
        }
    }

    /** Membuka zip ke folder model; gagal apa pun membersihkan hasil setengah jadi. */
    private fun unpack() {
        val staging = File(modelDir, "staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
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
            for (name in REQUIRED_FILES) {
                File(modelDir, name).delete()
                check(File(staging, name).renameTo(File(modelDir, name))) { "Gagal memindahkan $name." }
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    companion object {
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

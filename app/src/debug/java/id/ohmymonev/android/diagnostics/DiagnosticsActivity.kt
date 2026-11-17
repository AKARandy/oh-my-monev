package id.ohmymonev.android.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import id.ohmymonev.android.BuildConfig

/** Independent process: never touches the automation repositories or WebView constructor. */
class DiagnosticsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var export: Button
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(android.graphics.Color.rgb(247, 248, 250))
        }
        layout.addView(TextView(this).apply { text = "Diagnostik"; textSize = 28f; setTextColor(android.graphics.Color.BLACK) })
        layout.addView(TextView(this).apply { text = "Simpan bukti masalah tanpa membuka fitur otomatis."; textSize = 17f; setPadding(0, padding, 0, padding) })
        status = TextView(this).apply { text = "Data aplikasi tetap tersimpan."; textSize = 16f }
        layout.addView(status)
        export = Button(this).apply {
            text = "Ekspor diagnostik"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "oh-my-monev-diagnostics-${java.time.LocalDate.now()}.zip"), 1)
            }
        }
        layout.addView(export)
        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 1 || resultCode != RESULT_OK) return
        export.isEnabled = false
        status.text = "Menyimpan diagnostik…"
        worker.execute {
            val message = try {
                ZipOutputStream(contentResolver.openOutputStream(uri) ?: error("Output unavailable")).use { zip ->
                    fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                    entry("metadata.json", JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
                        .put("android", Build.VERSION.SDK_INT).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        .put("webview", android.webkit.WebView.getCurrentWebViewPackage()?.versionName ?: "tidak tersedia").toString())
                    for (name in listOf("diagnostic-actions.jsonl", "diagnostic-actions.previous.jsonl", "diagnostic-stalls.jsonl", "diagnostic-stalls.previous.jsonl")) {
                        val file = File(filesDir, name)
                        if (file.isFile) entry(name, file.inputStream().use { it.readBounded(2_100_000).toString(Charsets.UTF_8) })
                    }
                    if (Build.VERSION.SDK_INT >= 30) {
                        val exits = getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 8)
                        entry("process-exits.json", org.json.JSONArray(exits.map {
                            JSONObject().put("time", it.timestamp).put("reason", it.reason).put("process", it.processName)
                                .put("importance", it.importance).put("pssKb", it.pss).put("rssKb", it.rss)
                        }).toString())
                        exits.forEachIndexed { index, exit ->
                            val trace = runCatching { exit.traceInputStream?.use { it.readBounded(512_000).toString(Charsets.UTF_8) } }.getOrNull()
                            entry("exit-$index-trace.txt", trace ?: "Trace tidak tersedia.")
                        }
                        if (exits.isEmpty()) entry("exit-info.txt", "Riwayat penghentian proses tidak tersedia.")
                    } else entry("exit-info.txt", "Riwayat sistem memerlukan Android 11 atau lebih baru.")
                }
                "Diagnostik tersimpan. Kirim berkas ZIP untuk diperiksa."
            } catch (_: Exception) { "Diagnostik tidak dapat disimpan. Coba lagi." }
            runOnUiThread { if (!isDestroyed) { status.text = message; export.isEnabled = true } }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}

private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

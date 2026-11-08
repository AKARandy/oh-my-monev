package id.ohmymonev.android.schedule

import android.content.Context

/**
 * HOOK UNTUK LEAD: target panggilan mesin pengisian (WebView engine, milestone M2).
 * Receiver/service di kemasan ini hanya memandulkan alur; implementasinya disuntik
 * oleh `:app` (atau kelas engine) saat integrasi.
 */
interface AutoFillDelegate {
    /** Mulai satu siklus pengisian: isi formulir lalu kirim. */
    fun startAutoFill(context: Context)
}

/**
 * Registry tunggal tempat lead mendaftarkan implementasi mesin.
 * Sisanya di kode ini menyediakan no-op yang aman supaya aplikasi tetap jalan
 * (dengan notifikasi "mesin belum terpasang") sebelum terintegrasi.
 */
object AutoFillDelegateProvider {
    @Volatile
    var delegate: AutoFillDelegate? = null
}

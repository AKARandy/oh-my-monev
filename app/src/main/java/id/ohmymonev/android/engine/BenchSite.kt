package id.ohmymonev.android.engine

import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.siteprofile.SiteMarkers
import id.ohmymonev.core.siteprofile.SiteProfile
import id.ohmymonev.core.siteprofile.SiteTiming
import id.ohmymonev.core.siteprofile.SiteUrls

/**
 * Halaman tiruan (asset lokal) untuk membuktikan MESIN pengisian bekerja:
 * placeholder, label centang, tombol kirim, dan pesan sukses sama seperti situs.
 * Hanya dipakai tombol debug "Uji mesin"; tidak pernah menyentuh akun asli.
 */
object BenchSite {

    const val PAGE_PATH = "/test-bench.html"

    val profile: SiteProfile = SiteProfile(
        urls = SiteUrls(
            baseUrl = "file:///android_asset",
            dashboardPath = PAGE_PATH,
            evaluasiPath = PAGE_PATH,
            riwayatEditTemplate = PAGE_PATH,
        ),
        textareasByPart = mapOf(
            id.ohmymonev.core.model.ReportPart.AKTIVITAS to "Tulis uraian aktivitas minimal 100 karakter",
            id.ohmymonev.core.model.ReportPart.PELAJARAN to "Tulis ilmu/pembelajaran yang diperoleh minimal 100 karakter",
            id.ohmymonev.core.model.ReportPart.KENDALA to "Tulis kendala/hambatan yang dialami minimal 100 karakter",
        ),
        markers = SiteMarkers(
            loggedInTabs = listOf("Beranda", "Riwayat", "Perkembangan", "Akun"),
            loadingText = "Memuat",
            appOpenButtonLabel = "Isi Laporan Hari Ini",
            confirmCheckboxText = "Saya menyatakan telah meninjau",
            submitButtonLabel = "Simpan dan Kirim",
            locationOkText = "Lokasi berhasil diakses",
        ),
        timing = SiteTiming(spaSettleMs = 400, pollIntervalMs = 150, textareaWaitTimeoutMs = 8000),
        modified = true,
    )

    /** Isi contoh yang lolos gate (>=100 karakter per bagian, 3 butir aktivitas). */
    val draft: ReportDraft = ReportDraft(
        aktivitas = "- Melanjutkan pembuatan endpoint login berbasis JWT untuk modul kehadiran\n" +
            "- Melanjutkan penyusunan laporan mingguan tim magang\n" +
            "- Melanjutkan pengujian modul kehadiran pada lingkungan uji",
        pelajaran = "Struktur data yang rapi mempercepat penelusuran kesalahan saat pengujian dan " +
            "membuat serah terima pekerjaan menjadi lebih mudah dipahami oleh seluruh tim.",
        kendala = "Akses lingkungan pengujian yang terbatas memaksa urutan pengujian disusun ulang " +
            "setiap hari kerja agar bagian penting selesai lebih dulu.",
    )
}

/** Halaman tiruan status (Beranda + Riwayat) untuk membuktikan pembacaan status. */
object BenchStatusSite {
    const val PAGE_PATH = "/test-status.html"

    val profile: SiteProfile = SiteProfile(
        urls = SiteUrls(
            baseUrl = "file:///android_asset",
            dashboardPath = PAGE_PATH,
            evaluasiPath = PAGE_PATH,
            riwayatEditTemplate = PAGE_PATH,
            riwayatPath = PAGE_PATH,
        ),
        textareasByPart = BenchSite.profile.textareasByPart,
        markers = BenchSite.profile.markers,
        timing = SiteTiming(spaSettleMs = 300, pollIntervalMs = 120, textareaWaitTimeoutMs = 5000),
        modified = true,
    )
}

/** Satu bendera agar tombol debug bisa meminta satu putaran uji mesin. */
object DebugBench {
    @Volatile
    var pending: Boolean = false

    fun consume(): Boolean {
        val value = pending
        pending = false
        return value
    }
}

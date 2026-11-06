package id.ohmymonev.core.siteprofile

import id.ohmymonev.core.model.ReportPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Plain serializable description of the MagangHub Monev SPA (facts verified against the live site
 * 2026-11-06, see docs/site-research.md). The engine reads nothing hard-coded from :app —
 * everything DOM/URL/timing related lives here so it can be loaded or overridden from storage.
 */
@Serializable
data class SiteUrls(
    val baseUrl: String,
    val dashboardPath: String,
    val evaluasiPath: String,

    /** Template containing the literal placeholder {date}; e.g. /dashboard/riwayat?date=YYYY-MM-DD&view=edit */
    val riwayatEditTemplate: String,

    /** Halaman kalender riwayat (tanpa parameter tanggal). */
    val riwayatPath: String = "/dashboard/riwayat",
)

@Serializable
data class SiteMarkers(
    /** Tab labels visible only in the logged-in dashboard layout. */
    val loggedInTabs: List<String>,
    val loadingText: String,
    val appOpenButtonLabel: String,
    val confirmCheckboxText: String,
    val submitButtonLabel: String,
    val locationOkText: String,
    /** Placeholder of the extra "leave reason" textarea, shown only when status = Tidak Hadir Dengan Keterangan. */
    val leaveReasonPlaceholder: String = "Tulis alasan tidak hadir minimal 100 karakter",
    /** Substring of the site's success toast after attendance+report are stored. */
    val submissionSuccessText: String = "berhasil dikirim",
    /** Substring of the site's success toast after a report update/revision. */
    val submissionUpdatedText: String = "berhasil diperbarui",
)

@Serializable
data class SiteTiming(
    val spaSettleMs: Long,
    val pollIntervalMs: Long,
    val textareaWaitTimeoutMs: Long,
)

@Serializable
data class SiteProfile(
    val urls: SiteUrls,
    val textareasByPart: Map<ReportPart, String>,
    val markers: SiteMarkers,
    val timing: SiteTiming,

    /** true when this instance is a customized variant or a loader fallback, not the built-in default. */
    val modified: Boolean = false,
) {
    fun dashboardUrl(): String = urls.baseUrl + urls.dashboardPath
    fun evaluasiUrl(): String = urls.baseUrl + urls.evaluasiPath
    fun riwayatUrl(): String = urls.baseUrl + urls.riwayatPath
    fun riwayatEditUrl(dateISO: String): String = urls.baseUrl + urls.riwayatEditTemplate.replace("{date}", dateISO)
}

/** Built-in default profile, mirrors docs/site-research.md verbatim. */
val DEFAULT_SITE_PROFILE: SiteProfile = SiteProfile(
    urls = SiteUrls(
        baseUrl = "https://monev.maganghub.kemnaker.go.id",
        dashboardPath = "/dashboard",
        evaluasiPath = "/dashboard/evaluasi",
        riwayatEditTemplate = "/dashboard/riwayat?date={date}&view=edit",
        riwayatPath = "/dashboard/riwayat",
    ),
    textareasByPart = mapOf(
        ReportPart.AKTIVITAS to "Tulis uraian aktivitas minimal 100 karakter",
        ReportPart.PELAJARAN to "Tulis ilmu/pembelajaran yang diperoleh minimal 100 karakter",
        ReportPart.KENDALA to "Tulis kendala/hambatan yang dialami minimal 100 karakter",
    ),
    markers = SiteMarkers(
        loggedInTabs = listOf("Beranda", "Riwayat", "Perkembangan", "Akun"),
        loadingText = "Memuat halaman",
        appOpenButtonLabel = "Isi Laporan Hari Ini",
        confirmCheckboxText = "Saya menyatakan telah meninjau",
        submitButtonLabel = "Simpan dan Kirim",
        locationOkText = "Lokasi berhasil diakses",
        leaveReasonPlaceholder = "Tulis alasan tidak hadir minimal 100 karakter",
        submissionSuccessText = "berhasil dikirim",
        submissionUpdatedText = "berhasil diperbarui",
    ),
    timing = SiteTiming(
        spaSettleMs = 1500,
        pollIntervalMs = 250,
        textareaWaitTimeoutMs = 20000,
    ),
)

/** JSON form of [DEFAULT_SITE_PROFILE]; also used as the profile-storage schema example. */
const val DEFAULT_SITE_PROFILE_JSON: String =
    """{"urls":{"baseUrl":"https://monev.maganghub.kemnaker.go.id","dashboardPath":"/dashboard","evaluasiPath":"/dashboard/evaluasi","riwayatEditTemplate":"/dashboard/riwayat?date={date}&view=edit"},"textareasByPart":{"AKTIVITAS":"Tulis uraian aktivitas minimal 100 karakter","PELAJARAN":"Tulis ilmu/pembelajaran yang diperoleh minimal 100 karakter","KENDALA":"Tulis kendala/hambatan yang dialami minimal 100 karakter"},"markers":{"loggedInTabs":["Beranda","Riwayat","Perkembangan","Akun"],"loadingText":"Memuat halaman","appOpenButtonLabel":"Isi Laporan Hari Ini","confirmCheckboxText":"Saya menyatakan telah meninjau","submitButtonLabel":"Simpan dan Kirim","locationOkText":"Lokasi berhasil diakses","leaveReasonPlaceholder":"Tulis alasan tidak hadir minimal 100 karakter","submissionSuccessText":"berhasil dikirim","submissionUpdatedText":"berhasil diperbarui"},"timing":{"spaSettleMs":1500,"pollIntervalMs":250,"textareaWaitTimeoutMs":20000}}"""

/**
 * Loads a [SiteProfile] from JSON. Never throws: any parse failure (blank, malformed, missing keys,
 * wrong types) falls back to a marked-up copy of [DEFAULT_SITE_PROFILE] so the engine can still run.
 */
object SiteProfileLoader {

    private val json = Json { ignoreUnknownKeys = true }

    fun load(json: String?): SiteProfile {
        if (json.isNullOrBlank()) return fallback()
        return try {
            json.decodeSiteProfile().takeIf { it.isUsable() } ?: fallback()
        } catch (e: Exception) {
            fallback()
        }
    }

    /** A textareas map must cover every part with a usable selector; a partial map falls back. */
    private fun SiteProfile.isUsable(): Boolean =
        id.ohmymonev.core.model.ReportPart.entries.all { !textareasByPart[it].isNullOrBlank() }

    private fun String.decodeSiteProfile(): SiteProfile = json.decodeFromString(SiteProfile.serializer(), this)

    private fun fallback(): SiteProfile = DEFAULT_SITE_PROFILE.copy(modified = true)
}

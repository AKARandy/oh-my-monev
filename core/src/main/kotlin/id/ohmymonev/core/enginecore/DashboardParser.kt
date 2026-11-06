package id.ohmymonev.core.enginecore

/** Status laporan hari ini menurut halaman Beranda situs. */
enum class TodayState { UNKNOWN, FILLED, UNFILLED }

/** Satu hari di kalender Riwayat: label tanggal + status (mis. "Hadir disetujui"). */
data class DayEntry(val label: String, val state: String)

/**
 * Membaca teks halaman Monev (hasil WebView) menjadi status yang bisa ditampilkan.
 * Fungsi murni: mudah diuji dengan teks halaman asli.
 */
object DashboardParser {

    private val DAY_PATTERN = Regex(
        """([A-Za-z]+,\s*\d{1,2}\s+[A-Za-z]+\s+\d{4}),\s*([A-Za-z][A-Za-z ]{2,40})""",
    )
    private val DEADLINE_PATTERN = Regex("""pukul\s*(\d{1,2}[.:]\d{2})\s*WIB""")

    fun isLoggedIn(pageText: String): Boolean =
        listOf("Beranda", "Riwayat", "Perkembangan", "Akun").all { pageText.contains(it) }

    fun todayState(pageText: String): TodayState = when {
        pageText.contains("Laporan hari ini sudah diisi") -> TodayState.FILLED
        pageText.contains("Laporan hari ini belum diisi") -> TodayState.UNFILLED
        else -> TodayState.UNKNOWN
    }

    fun deadline(pageText: String): String? =
        DEADLINE_PATTERN.find(pageText)?.groupValues?.getOrNull(1)?.replace('.', ':')

    /** Daftar hari dari halaman Riwayat, urut seperti tampil di situs. */
    fun days(pageText: String): List<DayEntry> =
        DAY_PATTERN.findAll(pageText).map { match ->
            DayEntry(
                label = match.groupValues[1].trim(),
                state = match.groupValues[2].trim(),
            )
        }.filter { it.state.isNotBlank() }
            .toList()
}

package id.ohmymonev.core.enginecore

/** Status laporan hari ini menurut data situs: LIBUR = hari libur, LEAVE = cuti/alpa sehingga tidak ada laporan kerja yang dituntut. */
enum class TodayState { UNKNOWN, FILLED, UNFILLED, LIBUR, LEAVE }

/** Satu hari di kalender Riwayat: label tanggal + status (mis. "Hadir disetujui"). */
data class DayEntry(val label: String, val state: String)

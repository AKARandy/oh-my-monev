package id.ohmymonev.core.enginecore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardParserTest {

    // Teks asli yang diambil dari halaman Beranda akun sungguhan (2026-11-06).
    private val realBeranda = """
        Halo, Alief
        Pusat Data dan Teknologi Informasi
        Hari Ini
        Jumat, 6 November 2026
        Periode magang 21 Sep 2026-20 Mar 2027
        Aktif
        Laporan hari ini sudah diisi
        Laporan dan kehadiran Anda sudah tercatat.
        Lihat Laporan Hari Ini
        Batas pengisian laporan hari ini pukul
        23.59 WIB
        Menunggu peninjauan Mentor 3 kehadiran dan laporan telah dikirim.
        Beranda
        Riwayat
        Perkembangan
        Akun
        Waktu Server 18.28.00 WIB (GMT+7)
    """.trimIndent()

    @Test
    fun `reads real dashboard as filled and logged in`() {
        assertTrue(DashboardParser.isLoggedIn(realBeranda))
        assertEquals(TodayState.FILLED, DashboardParser.todayState(realBeranda))
        assertEquals("23:59", DashboardParser.deadline(realBeranda))
    }

    @Test
    fun `reads unfilled dashboard only from the explicit sentence`() {
        val unfilled = "Laporan hari ini belum diisi\nBatas pengisian pukul 23.59 WIB"
        assertEquals(TodayState.UNFILLED, DashboardParser.todayState(unfilled))
        // Tombol yang tersembunyi di DOM tidak boleh dianggap sebagai "belum diisi".
        assertEquals(TodayState.UNKNOWN, DashboardParser.todayState("Isi Laporan Hari Ini"))
    }

    @Test
    fun `reads holiday as libur`() {
        val libur = "Hari Ini\nSabtu, 7 November 2026\nJadwal libur posisi\nBatas pengisian pukul 23.59 WIB"
        assertEquals(TodayState.LIBUR, DashboardParser.todayState(libur))
    }

    @Test
    fun `reads riwayat day states`() {
        val riwayat = """
            Riwayat Kehadiran
            Rabu, 21 Oktober 2026, Hadir disetujui
            Kamis, 22 Oktober 2026, Hadir disetujui
            Sabtu, 24 Oktober 2026, Jadwal libur posisi
            Senin, 26 Oktober 2026, Belum Diisi
            Rabu, 4 November 2026, Menunggu Persetujuan
            Jumat, 6 November 2026, Menunggu Persetujuan
        """.trimIndent()
        val days = DashboardParser.days(riwayat)
        assertEquals(6, days.size)
        assertEquals("Hadir disetujui", days[0].state)
        assertEquals("Belum Diisi", days[3].state)
        assertEquals("Menunggu Persetujuan", days[5].state)
        assertTrue(days[5].label.contains("6 November 2026"))
    }

    @Test
    fun `login page is not a logged in dashboard`() {
        val login = "SIAPkerja ID\nMasuk\nEmail atau nomor handphone\nPassword\nMasuk\nDaftar Sekarang"
        assertEquals(false, DashboardParser.isLoggedIn(login))
        assertEquals(TodayState.UNKNOWN, DashboardParser.todayState(login))
        assertNull(DashboardParser.deadline(login))
    }
}

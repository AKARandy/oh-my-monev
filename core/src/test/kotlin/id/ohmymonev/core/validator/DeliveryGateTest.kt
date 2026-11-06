package id.ohmymonev.core.validator

import id.ohmymonev.core.model.ReportDraft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryGateTest {

    private val gate = DeliveryGate()

    private fun validDraft() = ReportDraft(
        aktivitas = "- Membuat endpoint login berbasis JWT untuk modul kehadiran aplikasi internal\n" +
            "- Menulis dan menjalankan tes unit baru untuk validasi token pada setiap sesi\n" +
            "- Memperbaiki urutan middleware yang menolak token yang masih benar",
        pelajaran = "Struktur middleware menentukan urutan pemeriksaan token sehingga pesan penolakan dari " +
            "setiap lapisan perlu dirangkai agar mudah dibaca oleh pemanggil endpoint.",
        kendala = "Penelusuran penolakan token butuh waktu lama karena pesan error middleware belum konsisten, " +
            "ditambah waktu respons environment pengujian yang tersebar di beberapa layanan.",
    )

    @Test
    fun `passes a well formed report`() {
        val result = gate.validate(validDraft())
        assertTrue("unexpected violations: ${result.violations}", result.passed)
    }

    @Test
    fun `rejects a part shorter than the character minimum`() {
        val draft = validDraft().copy(pelajaran = "terlalu pendek")
        val result = gate.validate(draft)
        assertFalse(result.passed)
        assertTrue(result.violations.any { it.id == "G-1" })
    }

    @Test
    fun `rejects a 60 character part because the site minimum is 100`() {
        val draft = validDraft().copy(pelajaran = "x".repeat(60))
        val result = gate.validate(draft)
        assertFalse(result.passed)
        assertTrue(result.violations.any { it.id == "G-1" })
    }

    @Test
    fun `rejects person words`() {
        val draft = validDraft().copy(
            aktivitas = "- Saya membuat endpoint login berbasis JWT untuk modul kehadiran aplikasi\n" +
                "- Menulis dan menjalankan tes unit baru untuk validasi token pada setiap sesi\n" +
                "- Memperbaiki urutan middleware yang menolak token yang masih benar",
        )
        val result = gate.validate(draft)
        assertTrue(result.violations.any { it.id == "G-2" })
        assertTrue(result.violations.any { it.id == "G-8" }) // 'Saya' is not an active meN- opener
    }

    @Test
    fun `rejects meta markers and leftover placeholders`() {
        val draft = validDraft().copy(aktivitas = validDraft().aktivitas + "\n- Melanjutkan pencatatan {tanggal}")
        val result = gate.validate(draft)
        assertTrue(result.violations.any { it.id == "G-3" })
    }

    @Test
    fun `rejects em and en dashes`() {
        val draft = validDraft().copy(pelajaran = validDraft().pelajaran + " \u2014")
        val result = gate.validate(draft)
        assertTrue(result.violations.any { it.id == "G-4" })
    }

    @Test
    fun `rejects aktivitas with fewer than three bullets`() {
        val draft = validDraft().copy(
            aktivitas = "- Membuat endpoint login berbasis JWT untuk modul kehadiran aplikasi internal\n" +
                "- Menulis dan menjalankan tes unit baru untuk validasi token pada setiap sesi",
        )
        val result = gate.validate(draft)
        assertTrue(result.violations.any { it.id == "G-5" })
    }

    @Test
    fun `rejects multi paragraph pelajaran`() {
        val text = validDraft().pelajaran
        val draft = validDraft().copy(pelajaran = "$text\nKedua paragraf tidak diperbolehkan oleh aturan laporan harian.")
        val result = gate.validate(draft)
        assertTrue(result.violations.any { it.id == "G-6" })
    }

    @Test
    fun `rejects empty vocabulary`() {
        val draft = validDraft().copy(
            kendala = "Pekerjaan hari ini luar biasa padat sehingga perlu menyusun ulang prioritas kecil di akhir " +
                "pekan untuk menjaga mutu evaluasi mentoring mingguan program magang ini.",
        )
        val result = gate.validate(draft)
        assertTrue(result.violations.any { it.id == "G-7" })
    }

    @Test
    fun `rejects bullets opening with a passive verb`() {
        val draft = validDraft().copy(
            aktivitas = "- Dibuat endpoint login berbasis JWT untuk modul kehadiran aplikasi internal\n" +
                "- Menulis dan menjalankan tes unit baru untuk validasi token pada setiap sesi\n" +
                "- Memperbaiki urutan middleware yang menolak token yang masih benar",
        )
        val result = gate.validate(draft)
        assertTrue(result.violations.any { it.id == "G-8" })
    }

    @Test
    fun `rejects credential like literals`() {
        val draft = validDraft().copy(
            kendala = "Mengonfigurasi ulang kredensial environment pengujian: password=xxxx-rotasi-wajib-hari-ini " +
                "sehingga layanan penyimpanan bisa menyambung kembali tanpa error timeout di lingkungan utama.",
        )
        val result = gate.validate(draft)
        assertTrue(result.violations.any { it.id == "G-9" })
    }
}

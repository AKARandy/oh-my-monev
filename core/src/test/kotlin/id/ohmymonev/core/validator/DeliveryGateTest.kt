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
    fun `accepts pronouns in a valid report`() {
        val draft = validDraft()
        val result = gate.validate(draft.copy(
            aktivitas = draft.aktivitas.replace("aplikasi internal", "aplikasi kami"),
            pelajaran = "Saya mempelajari hal berikut. " + draft.pelajaran,
            kendala = "Kami menemukan kendala berikut. " + draft.kendala,
        ))
        assertTrue("unexpected violations: ${result.violations}", result.passed)
    }

    @Test
    fun `accepts plain activities and line breaks within the limits`() {
        val text = "Saya memeriksa data dan mencatat hasil — termasuk {rincian} — untuk tim.\n".repeat(2)
        assertTrue(gate.validate(ReportDraft(text, text, text)).passed)
    }

    @Test
    fun `accepts both length boundaries and rejects longer text`() {
        val draft = ReportDraft("a".repeat(100), "b".repeat(5000), "c".repeat(100))
        assertTrue(gate.validate(draft).passed)
        assertFalse(gate.validate(draft.copy(pelajaran = draft.pelajaran + "b")).passed)
    }
}

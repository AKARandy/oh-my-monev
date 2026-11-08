package id.ohmymonev.core.generator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class VerbMapperTest {

    @Test
    fun `melakukan becomes melanjutkan with unchanged rest`() {
        assertEquals("Melanjutkan aktivitas pengecekan sistem", VerbMapper.continuation("Melakukan aktivitas pengecekan sistem"))
    }

    @Test
    fun `membuat becomes pembuatan`() {
        assertEquals("Melanjutkan pembuatan endpoint login", VerbMapper.continuation("Membuat endpoint login"))
    }

    @Test
    fun `menyusun becomes penyusunan`() {
        assertEquals("Melanjutkan penyusunan laporan mingguan", VerbMapper.continuation("Menyusun laporan mingguan"))
    }

    @Test
    fun `menganalisis becomes analisis`() {
        assertEquals("Melanjutkan analisis data media", VerbMapper.continuation("Menganalisis data media"))
    }

    @Test
    fun `person words require a manual edit`() {
        assertNull(VerbMapper.continuation("Saya membuat fitur radio"))
    }

    @Test
    fun `unknown verb is rejected`() {
        assertNull(VerbMapper.continuation("Membikin kue"))
    }

    @Test
    fun `case is normalized before mapping`() {
        assertEquals("Melanjutkan pembuatan demo", VerbMapper.continuation("membuat demo"))
    }

    @Test
    fun `empty input returns null`() {
        assertNull(VerbMapper.continuation("   "))
    }
    @Test fun `continued activity keeps its complete wording`() {
        assertEquals("Melanjutkan pemeriksaan data 123.", VerbMapper.continuation("Melanjutkan pemeriksaan data 123."))
    }
    @Test fun `a known verb without an object is rejected`() { assertNull(VerbMapper.continuation("Membuat")) }
    @Test fun `list markers are normalized without changing facts or order`() {
        val input = "- Memeriksa data 123.\n• Menguji modul A.\n1. Menyusun laporan B.\n2) Melanjutkan pemeriksaan C."
        assertEquals(listOf("Melanjutkan pemeriksaan data 123.", "Melanjutkan pengujian modul A.",
            "Melanjutkan penyusunan laporan B.", "Melanjutkan pemeriksaan C."), ContinuationRuleEngine().bullets(input))
    }
    @Test fun `unsupported lines retain their number and complete source text`() {
        assertEquals(listOf(2 to "Membikin data 123."), ContinuationRuleEngine().unsupported("1. Memeriksa data.\n2) Membikin data 123."))
    }
}

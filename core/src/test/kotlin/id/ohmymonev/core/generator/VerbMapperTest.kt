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
    fun `person words are stripped before reshaping`() {
        assertEquals("Melanjutkan pembuatan fitur radio", VerbMapper.continuation("Saya membuat fitur radio"))
    }

    @Test
    fun `unknown verb falls back to a generic activity continuation`() {
        assertEquals("Melanjutkan aktivitas kue", VerbMapper.continuation("Membikin kue"))
    }

    @Test
    fun `case is normalized before mapping`() {
        assertEquals("Melanjutkan pembuatan demo", VerbMapper.continuation("membuat demo"))
    }

    @Test
    fun `empty input returns null`() {
        assertNull(VerbMapper.continuation("   "))
    }
}

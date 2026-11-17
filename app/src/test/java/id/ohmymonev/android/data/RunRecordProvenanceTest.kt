package id.ohmymonev.android.data

import id.ohmymonev.core.enginecore.EngineOutcome
import id.ohmymonev.core.enginecore.RunRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class RunRecordProvenanceTest {
    @Test
    fun `summary shows content location and verification`() {
        val record = RunRecord(
            date = "2026-11-12",
            attemptedAtISO = "2026-11-12T21:00:00+07:00",
            outcome = EngineOutcome.SUBMITTED,
            attempt = 1,
            trigger = "EMERGENCY",
            contentSource = "TEMPLATE",
            sourceDate = "2026-11-12",
            reusedPreview = true,
            locationSource = "IP",
            fallbackReason = "disabled",
            lastStage = "submit",
            verification = "CONFIRMED",
        )

        assertEquals("Templat Anda · Perkiraan lokasi IP · Tercatat di Monev", record.provenanceSummary())
        assertEquals(
            listOf(
                "Pemicu" to "Darurat",
                "Sumber isi" to "Templat",
                "Tanggal sumber" to "2026-11-12",
                "Pratinjau" to "Dipakai ulang",
                "Lokasi" to "Perkiraan IP",
                "Alasan IP" to "Lokasi mati",
                "Tahap" to "submit",
                "Pemeriksaan" to "Terkonfirmasi",
            ),
            record.provenanceDetails(),
        )
    }

    @Test
    fun `legacy record summary reports unknown source`() {
        val record = RunRecord(
            date = "2026-11-12",
            attemptedAtISO = "2026-11-12T21:00:00+07:00",
            outcome = EngineOutcome.UNKNOWN,
            attempt = 1,
        )

        assertEquals("Sumber tidak diketahui", record.provenanceSummary())
        assertEquals(emptyList<Pair<String, String>>(), record.provenanceDetails())
    }
}

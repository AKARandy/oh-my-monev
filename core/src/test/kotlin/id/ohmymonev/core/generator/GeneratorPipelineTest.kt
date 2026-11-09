package id.ohmymonev.core.generator

import id.ohmymonev.core.model.ReportDraft
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class GeneratorPipelineTest {
    private val date = LocalDate.of(2026, 11, 8)
    private val original = ReportDraft(
        "- Memeriksa data penerbangan 123.\n- Menyusun laporan untuk tim.\n- Menguji hasil pemeriksaan data.",
        "Pelajaran dari sumber asli.", "Kendala dari sumber asli.",
    )
    private fun generate(draft: ReportDraft = original) = GeneratorPipeline(
        history = ReportHistory { DatedReport(date.minusDays(1), draft) },
    ).generate(date)

    @Test fun continuationPreservesSourceFactsLessonsAndObstacles() {
        val result = generate() as GenerationOutcome.Generated
        assertEquals("- Melanjutkan pemeriksaan data penerbangan 123.\n- Melanjutkan penyusunan laporan untuk tim.\n- Melanjutkan pengujian hasil pemeriksaan data.", result.draft.aktivitas)
        assertEquals(original.pelajaran, result.draft.pelajaran)
        assertEquals(original.kendala, result.draft.kendala)
    }
    @Test fun noSourceRefuses() {
        assertEquals(FailureReason.NO_SOURCE_MATERIAL,
            (GeneratorPipeline(history = ReportHistory { null }).generate(date) as GenerationOutcome.Failed).reason)
    }
    @Test fun thinSourceIsNotPadded() {
        val result = generate(original.copy(aktivitas = "- Memeriksa data 123.")) as GenerationOutcome.Generated
        assertEquals("- Melanjutkan pemeriksaan data 123.", result.draft.aktivitas)
    }
    @Test fun longSourceIsNotTruncated() {
        val result = generate(original.copy(aktivitas = (1..6).joinToString("\n") { "- Memeriksa data $it." }))
        assertEquals(6, (result as GenerationOutcome.Generated).draft.aktivitas.lines().size)
    }
    @Test fun unsupportedBulletCannotBeSilentlyDropped() {
        val result = generate(original.copy(aktivitas = original.aktivitas + "\n- Membikin catatan."))
        assertEquals(FailureReason.UNSAFE_ACTIVITY, (result as GenerationOutcome.Failed).reason)
    }
    @Test fun duplicateActivitiesAreNotSilentlyRemoved() {
        val draft = original.copy(aktivitas = "- Memeriksa data 123.\n".repeat(3))
        assertEquals(3, (generate(draft) as GenerationOutcome.Generated).draft.aktivitas.lines().size)
    }
    @Test fun authoredTemplatesStillSupportDates() {
        val result = GeneratorPipeline(Templates { original.copy(pelajaran = "Tanggal {tanggal}") }, ReportHistory { null }).generate(date)
        assertEquals("Tanggal 8 November 2026", (result as GenerationOutcome.Generated).draft.pelajaran)
    }
}

package id.ohmymonev.core.generator

import id.ohmymonev.core.model.GenerationTier
import id.ohmymonev.core.model.ReportDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GeneratorPipelineTest {

    private val date: LocalDate = LocalDate.of(2026, 11, 6)
    private val prevDate: LocalDate = date.minusDays(1)

    private val previousDraft = ReportDraft(
        aktivitas = "- Membuat endpoint login berbasis JWT\n- Menyusun laporan mingguan tim\n- Menguji modul kehadiran",
        pelajaran = "x",
        kendala = "y",
    )

    @Test
    fun `template branch substitutes the tanggal placeholder`() {
        val pipeline = GeneratorPipeline(
            templates = Templates {
                ReportDraft("Melanjutkan pekerjaan pada {tanggal}", "x".repeat(120), "y".repeat(120))
            },
            history = ReportHistory { null },
            poolBundle = PoolBundle(listOf("p".repeat(120)), listOf("k".repeat(120))),
        )
        val outcome = pipeline.generate(date)
        assertTrue(outcome is GenerationOutcome.Generated)
        val generated = outcome as GenerationOutcome.Generated
        assertEquals(GenerationTier.TEMPLATE, generated.tier)
        assertTrue(generated.draft.aktivitas.contains("6 November 2026"))
    }

    @Test
    fun `continuation branch reshapes the previous report`() {
        val pipeline = GeneratorPipeline(
            templates = null,
            history = ReportHistory { DatedReport(prevDate, previousDraft) },
            poolBundle = PoolBundle(
                listOf("Belajar lagi bahwa X penting Because".repeat(1).let { it + "nya" }.let { it.padEnd(120, '.') }),
                listOf("Kendala panjang cocok untuk diulang minggu ini".padEnd(120, '.')),
            ),
        )
        val outcome = pipeline.generate(date)
        assertTrue(outcome is GenerationOutcome.Generated)
        val generated = outcome as GenerationOutcome.Generated
        assertEquals(GenerationTier.CONTINUATION_RULES, generated.tier)
        assertTrue(generated.draft.aktivitas.startsWith("- Melanjutkan pembuatan endpoint login berbasis JWT"))
        assertTrue(generated.notes.first().contains("2026-11-05"))
    }

    @Test
    fun `continuation bullets all open with an active meN verb`() {
        val pipeline = GeneratorPipeline(
            templates = null,
            history = ReportHistory { DatedReport(prevDate, previousDraft) },
            poolBundle = PoolBundle(listOf("p".repeat(120)), listOf("k".repeat(120))),
        )
        val generated = pipeline.generate(date) as GenerationOutcome.Generated
        for (line in generated.draft.aktivitas.lines()) {
            assertTrue("'$line' must start with an active verb", line.startsWith("- Me"))
        }
    }

    @Test
    fun `no material means refusal, not fabrication`() {
        val pipeline = GeneratorPipeline(
            templates = null,
            history = ReportHistory { null },
            poolBundle = PoolBundle(listOf("p".repeat(120)), listOf("k".repeat(120))),
        )
        val outcome = pipeline.generate(date)
        assertEquals(FailureReason.NO_SOURCE_MATERIAL, (outcome as GenerationOutcome.Failed).reason)
    }

    @Test
    fun `empty pelajaran pool fails cleanly`() {
        val pipeline = GeneratorPipeline(
            templates = null,
            history = ReportHistory { DatedReport(prevDate, previousDraft) },
            poolBundle = PoolBundle(emptyList(), listOf("k".repeat(120))),
        )
        val outcome = pipeline.generate(date)
        assertEquals(FailureReason.PELAJARAN_POOL_EXHAUSTED, (outcome as GenerationOutcome.Failed).reason)
    }
}

class RotatingPoolTest {

    private val date: LocalDate = LocalDate.of(2026, 11, 6)

    @Test
    fun `cooldown forces rotation and eventually exhausts`() {
        val pool = RotatingPool(listOf("a".repeat(10), "b".repeat(10)))
        assertEquals("a".repeat(10), pool.next(date))
        assertEquals("b".repeat(10), pool.next(date))
        assertEquals(null, pool.next(date))
    }

    @Test
    fun `item comes back after the cooldown`() {
        val pool = RotatingPool(listOf("a".repeat(10)))
        assertEquals("a".repeat(10), pool.next(date))
        assertEquals(null, pool.next(date))
        assertEquals("a".repeat(10), pool.next(date.plusDays(8)))
    }

    @Test
    fun `snapshot round trips the last-used state`() {
        val first = RotatingPool(listOf("a".repeat(10), "b".repeat(10)))
        first.next(date)
        val second = RotatingPool(listOf("a".repeat(10), "b".repeat(10)), initialLastUsed = first.snapshot())
        assertEquals("b".repeat(10), second.next(date))
    }
}

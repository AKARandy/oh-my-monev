package id.ohmymonev.core

import id.ohmymonev.core.generator.DatedReport
import id.ohmymonev.core.generator.GenerationOutcome
import id.ohmymonev.core.generator.GeneratorPipeline
import id.ohmymonev.core.generator.ReportHistory
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.schedule.ScheduleCalculator
import id.ohmymonev.core.schedule.ScheduleSettings
import id.ohmymonev.core.validator.DeliveryGate
import java.time.LocalDate
import org.junit.Test

/**
 * Bukan unit test bersyarat: ini mencetak bukti keluaran backend yang nyata
 * (generator, gate, jadwal) agar bisa dibaca manusia tanpa membaca kode.
 */
class ProofPrintTest {

    @Test
    fun printBackendEvidence() {
        val today = LocalDate.of(2026, 11, 6)
        val yesterday = DatedReport(
            today.minusDays(1),
            ReportDraft(
                aktivitas = "- Membuat endpoint login berbasis JWT\n" +
                    "- Menyusun laporan mingguan tim\n" +
                    "- Menguji modul kehadiran",
                pelajaran = "x",
                kendala = "y",
            ),
        )
        val pipeline = GeneratorPipeline(history = ReportHistory { yesterday })
        val outcome = pipeline.generate(today)

        println("=== GENERATOR (laporan kemarin -> laporan hari ini) ===")
        when (outcome) {
            is GenerationOutcome.Generated -> {
                println("tier: ${outcome.tier}")
                println("catatan: ${outcome.notes}")
                println("--- aktivitas ---")
                println(outcome.draft.aktivitas)
                println("--- pelajaran ---")
                println(outcome.draft.pelajaran)
                println("--- kendala ---")
                println(outcome.draft.kendala)
                val gate = DeliveryGate().validate(outcome.draft)
                println("gate lulus: ${gate.passed} pelanggaran: ${gate.violations}")
            }
            is GenerationOutcome.Failed -> println("GAGAL: ${outcome.reason} (${outcome.detail})")
        }

        println()
        println("=== BUKTI TANPA BAHAN: TIDAK MENGARANG ===")
        val empty = GeneratorPipeline(history = ReportHistory { null })
            .generate(today)
        println("hasil: $empty")

        println()
        println("=== JADWAL HARI INI (WIB) ===")
        val settings = ScheduleSettings()
        ScheduleCalculator.nextFires(today.atStartOfDay(ScheduleCalculator.asiaJakarta), settings)
            .forEach { (key, fire) -> println("alarm $key: $fire") }
        println("contoh coba ulang setelah gagal 21:05 (batas 22:30):")
        val failedAt = today.atTime(21, 5).atZone(ScheduleCalculator.asiaJakarta)
        val deadline = today.atTime(22, 30).atZone(ScheduleCalculator.asiaJakarta)
        ScheduleCalculator.retryTimes(failedAt, deadline).forEach { println("coba lagi: $it") }

        println()
        println("=== GATE: CONTOH DITOLAK ===")
        val bad = outcome as? GenerationOutcome.Generated
        if (bad != null) {
            val invalid = bad.draft.copy(
                aktivitas = bad.draft.aktivitas.replace("- Membuat", "- Saya membuat") + "\n- Batal",
                kendala = "Kendala singkat",
            )
            println(DeliveryGate().validate(invalid).violations.joinToString("\n") { "${it.id}: ${it.message}" })
        }
        println()
        println("=== CONTINUATION MAP CONTOH ===")
        listOf(
            "Melakukan aktivitas pengecekan sistem",
            "Membuat endpoint login",
            "Menyusun laporan mingguan",
            "Menganalisis data media",
        ).forEach { println("$it -> ${id.ohmymonev.core.generator.VerbMapper.continuation(it)}") }
    }
}

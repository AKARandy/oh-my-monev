package id.ohmymonev.core.generator

import id.ohmymonev.core.model.GenerationTier
import id.ohmymonev.core.model.ReportDraft
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A report associated with its calendar day. */
data class DatedReport(val date: LocalDate, val draft: ReportDraft)

enum class FailureReason {
    NO_SOURCE_MATERIAL,
    TOO_FEW_BULLETS,
    UNSAFE_ACTIVITY,
    INVALID_REPORT,
}

sealed interface GenerationOutcome {
    data class Generated(val draft: ReportDraft, val tier: GenerationTier, val notes: List<String>) : GenerationOutcome
    data class Failed(val reason: FailureReason, val detail: String) : GenerationOutcome
}

/** User-authored templates; see [GeneratorPipeline.substitute] for the placeholder contract. */
fun interface Templates {
    /** Returns the template draft for [date] (placeholders NOT yet substituted), or null. */
    fun templateFor(date: LocalDate): ReportDraft?
}

/** Read-only view over the locally stored report history. */
fun interface ReportHistory {
    /** The latest report strictly before [date]. */
    fun latestBefore(date: LocalDate): DatedReport?
}

/** Prepare a continuation from the original report without adding or dropping content. */
class GeneratorPipeline(
    private val templates: Templates? = null,
    private val history: ReportHistory,
    private val engine: ContinuationRuleEngine = ContinuationRuleEngine(),
) {
    fun generate(date: LocalDate): GenerationOutcome {
        val template = templates?.templateFor(date)
            ?.takeIf { it.aktivitas.isNotBlank() && it.pelajaran.isNotBlank() && it.kendala.isNotBlank() }
            ?.let { substitute(it, date) }
        if (template != null) {
            return GenerationOutcome.Generated(template, GenerationTier.TEMPLATE, emptyList())
        }

        val previous = history.latestBefore(date)
            ?: return GenerationOutcome.Failed(
                FailureReason.NO_SOURCE_MATERIAL,
                "no template and no previous report before $date; nothing may be submitted",
            )

        val bullets = engine.bullets(previous.draft.aktivitas)
            ?: return GenerationOutcome.Failed(FailureReason.UNSAFE_ACTIVITY,
                "Ada aktivitas yang tidak dapat diubah dengan aman. Tulis laporan hari ini untuk memperbaikinya.")
        if (bullets.size !in 3..5) {
            return GenerationOutcome.Failed(
                FailureReason.TOO_FEW_BULLETS,
                "Aktivitas memiliki ${bullets.size} butir. Tulis 3 sampai 5 butir tanpa menambah kegiatan rekaan.",
            )
        }

        val draft = ReportDraft(
            aktivitas = bullets.joinToString("\n") { "- $it" },
            pelajaran = previous.draft.pelajaran,
            kendala = previous.draft.kendala,
        )
        return GenerationOutcome.Generated(
            draft,
            GenerationTier.CONTINUATION_RULES,
            notes = listOf("dari laporan ${previous.date}"),
        )
    }

    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("id"))

        /** Replaces {tanggal} (e.g. "6 November 2026") in every part of a template. */
        fun substitute(template: ReportDraft, date: LocalDate): ReportDraft = ReportDraft(
            aktivitas = template.aktivitas.replace("{tanggal}", date.format(DATE_FORMAT)),
            pelajaran = template.pelajaran.replace("{tanggal}", date.format(DATE_FORMAT)),
            kendala = template.kendala.replace("{tanggal}", date.format(DATE_FORMAT)),
        )
    }
}

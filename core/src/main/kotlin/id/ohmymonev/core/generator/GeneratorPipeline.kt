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
    PELAJARAN_POOL_EXHAUSTED,
    KENDALA_POOL_EXHAUSTED,
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

/**
 * The deterministic flagship generator.
 *
 * Tier 0 (TEMPLATE): user's template with placeholder substitution.
 * Tier 1 (CONTINUATION_RULES): reshape the previous working day's report via [VerbMapper];
 * pelajaran/kendala come from rotating user pools.
 *
 * It never invents facts: with no template and no history, or exhausted pools, it refuses with
 * a failure reason rather than fabricating content. The [id.ohmymonev.core.validator.DeliveryGate]
 * still applies downstream before anything is written into the site form.
 */
class GeneratorPipeline(
    private val templates: Templates? = null,
    private val history: ReportHistory,
    private val poolBundle: PoolBundle,
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
        if (bullets.size < 3) {
            return GenerationOutcome.Failed(
                FailureReason.TOO_FEW_BULLETS,
                "continuation yields only ${bullets.size} bullets from ${previous.date}",
            )
        }

        val pelajaran = poolBundle.pelajaran.next(date)
            ?: return GenerationOutcome.Failed(FailureReason.PELAJARAN_POOL_EXHAUSTED, "pelajaran pool exhausted for $date")
        val kendala = poolBundle.kendala.next(date)
            ?: return GenerationOutcome.Failed(FailureReason.KENDALA_POOL_EXHAUSTED, "kendala pool exhausted for $date")

        val draft = ReportDraft(
            aktivitas = bullets.joinToString("\n") { "- $it" },
            pelajaran = pelajaran,
            kendala = kendala,
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

/** Two pools (pelajaran, kendala) sharing one last-used snapshot for simple persistence. */
class PoolBundle(
    pelajaranItems: List<String>,
    kendalaItems: List<String>,
    lastUsed: Map<String, LocalDate> = emptyMap(),
) {
    val pelajaran = RotatingPool(pelajaranItems, initialLastUsed = lastUsed)
    val kendala = RotatingPool(kendalaItems, initialLastUsed = lastUsed)
}

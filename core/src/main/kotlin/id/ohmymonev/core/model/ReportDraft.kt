package id.ohmymonev.core.model

/**
 * The three text parts of a MagangHub Monev daily report.
 * Field names match the JSON keys the site form uses (aktivitas / pelajaran / kendala).
 */
data class ReportDraft(
    val aktivitas: String,
    val pelajaran: String,
    val kendala: String,
)

/**
 * Which source produced a draft. Recorded in the audit trail alongside the exact text submitted.
 */
enum class GenerationTier {
    /** Directly from a user-authored template with placeholder substitution. */
    TEMPLATE,

    /** Rule-based continuation of the previous working day's report. */
    CONTINUATION_RULES,

    /** Continuation where sentences the rule map does not know were paraphrased by the on-device model. */
    NEURAL,
}

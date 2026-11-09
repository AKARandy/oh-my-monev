package id.ohmymonev.core.validator

import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.model.ReportPart

/** One failed rule. The ID is stable for callers. */
data class Violation(val id: String, val message: String)

data class GateResult(val passed: Boolean, val violations: List<Violation>) {
    companion object {
        val PASS = GateResult(true, emptyList())
    }
}

/** Each report section must contain 100 to 5000 characters after trimming. */
class DeliveryGate(
    private val minChars: Int = 100,
    private val maxChars: Int = 5000,
) {
    fun validate(draft: ReportDraft): GateResult {
        val violations = ReportPart.entries.mapNotNull { part ->
            val length = part.text(draft).trim().length
            when {
                length < minChars -> Violation("G-1", "${part.label}: only $length chars, minimum is $minChars")
                length > maxChars -> Violation("G-1", "${part.label}: $length chars, maximum is $maxChars")
                else -> null
            }
        }
        return if (violations.isEmpty()) GateResult.PASS else GateResult(false, violations)
    }
}

package id.ohmymonev.core.validator

import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.model.ReportPart

/** One failed rule. Ids are stable identifiers (G-1..G-9) used by tests and UI. */
data class Violation(val id: String, val message: String)

data class GateResult(val passed: Boolean, val violations: List<Violation>) {
    companion object {
        val PASS = GateResult(true, emptyList())
    }
}

/**
 * Delivery gate: hard constraints a draft must pass before it may be prefilled into the Monev form
 * (and certainly before anything may be auto-submitted).
 *
 *  G-1 every part at least [minChars] (and at most [maxChars]) characters
 *  G-2 no first-person perspective (saya, aku, kami, kita, penulis, anda)
 *  G-3 no meta markers, notes or leftover placeholders ({}, <>, "catatan:", "nb:", ...)
 *  G-4 no em/en dash characters
 *  G-5 aktivitas: 3-5 lines, every line a bullet starting with "- "
 *  G-6 pelajaran/kendala: exactly one paragraph, no bullets
 *  G-7 no empty cliches
 *  G-8 every activity bullet opens with an active meN- verb, not a di- passive
 *  G-9 no credential-like or personally-identifying literals
 */
class DeliveryGate(
    private val minChars: Int = 100,
    private val maxChars: Int = 5000,
) {
    fun validate(draft: ReportDraft): GateResult {
        val violations = mutableListOf<Violation>()
        for (part in ReportPart.entries) {
            val text = part.text(draft).trim()

            if (text.length < minChars) {
                violations += Violation("G-1", "${part.label}: only ${text.length} chars, minimum is $minChars")
            } else if (text.length > maxChars) {
                violations += Violation("G-1", "${part.label}: ${text.length} chars, maximum is $maxChars")
            }

            for (match in PRONOUNS.findAll(text)) {
                violations += Violation("G-2", "${part.label}: person word '${match.value}'")
            }

            if (META_MARKERS.containsMatchIn(text)) {
                violations += Violation("G-3", "${part.label}: meta marker, note or leftover placeholder")
            }

            if (DASHES.containsMatchIn(text)) {
                violations += Violation("G-4", "${part.label}: em or en dash present")
            }

            when (part.bulleted) {
                true -> {
                    val bullets = nonEmptyLines(text)
                    if (bullets.isEmpty()) {
                        violations += Violation("G-5", "${part.label}: no lines at all")
                    } else {
                        if (bullets.size !in 3..5) {
                            violations += Violation("G-5", "${part.label}: ${bullets.size} lines, expected 3 to 5")
                        }
                        for (bullet in bullets) {
                            if (!bullet.startsWith("- ")) {
                                violations += Violation("G-5", "${part.label}: line does not start with '- ': '$bullet'")
                            }
                        }
                        if (bullets.size in 3..5 && bullets.all { it.startsWith("- ") }) {
                            for (bullet in bullets) {
                                val firstWord = bullet.removePrefix("- ").substringBefore(' ')
                                if (firstWord.isBlank() || !firstWord.startsWith("Me", ignoreCase = true)) {
                                    violations += Violation("G-8", "${part.label}: activity bullet does not open with an active meN- verb: '$firstWord'")
                                }
                            }
                        }
                    }
                }

                false -> {
                    val lines = nonEmptyLines(text)
                    if (lines.size > 1) {
                        violations += Violation("G-6", "${part.label}: must be a single paragraph")
                    }
                    if (lines.any { it.startsWith("- ") }) {
                        violations += Violation("G-6", "${part.label}: must be one paragraph, not bullets")
                    }
                }
            }

            CLICHES.firstOrNull { candidate -> text.lowercase().contains(candidate) }?.let { phrase ->
                violations += Violation("G-7", "${part.label}: empty phrase \"$phrase\"")
            }

            for (secret in SECRET_PATTERNS) {
                if (secret.containsMatchIn(text)) {
                    violations += Violation("G-9", "${part.label}: credential-like or identifying literal forbidden")
                }
            }
        }
        return if (violations.isEmpty()) GateResult.PASS else GateResult(false, violations)
    }

    private fun nonEmptyLines(text: String): List<String> =
        text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        val PRONOUNS: Regex = Regex("""\b(saya|aku|kami|kita|penulis|anda)\b""", setOf(RegexOption.IGNORE_CASE))
        val DASHES: Regex = Regex("[\u2013\u2014]")
        val META_MARKERS: Regex =
            Regex("""(?:\b(?:catatan|nb|note|todo|to-do|draft)\s*:|[{}<>])""", setOf(RegexOption.IGNORE_CASE))
        val CLICHES: List<String> = listOf(
            "pengalaman berharga",
            "luar biasa",
            "sangat senang",
            "memperluas wawasan",
            "membuka wawasan",
            "penuh semangat",
            "dengan sempurna",
            "berjalan lancar",
            "semoga hari esok",
        )
        val SECRET_PATTERNS: List<Regex> = listOf(
            // JWT payload characters
            Regex("""eyJ[A-Za-z0-9_-]{8,}"""),
            // Bearer <token>
            Regex("""(?i)\bbearer\s+[A-Za-z0-9._-]{8,}"""),
            // password: / apiKey= / secret: ...
            Regex("""(?i)(password|passwd|passcode|secret|api[_-]?key|access[_-]?token)\s*[:=]"""),
            // bare IPv4 literal
            Regex("""\b\d{1,3}(\.\d{1,3}){3}\b"""),
            Regex("""(?i)\bjdbc:"""),
            // email address
            Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
        )
    }
}

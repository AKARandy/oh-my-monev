package id.ohmymonev.core.generator

/** Preserve every source activity. Refuse the whole conversion if any verb is unsupported. */
class ContinuationRuleEngine {
    fun bullets(previousAktivitas: String): List<String>? {
        val lines = previousAktivitas.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.map { VerbMapper.continuation(it.removePrefix("- ")) ?: return null }
    }
}

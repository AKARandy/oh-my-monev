package id.ohmymonev.core.generator

/** Normalize list markers only; every activity and its position are preserved. */
class ContinuationRuleEngine {
    fun activities(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        .map { it.replaceFirst(Regex("""^(?:[-•]\s*|\d+[.)]\s+)"""), "") }

    fun unsupported(text: String): List<Pair<Int, String>> = activities(text).mapIndexedNotNull { index, line ->
        if (VerbMapper.continuation(line) == null) index + 1 to line else null
    }

    fun bullets(text: String): List<String>? = activities(text).map { VerbMapper.continuation(it) ?: return null }
}

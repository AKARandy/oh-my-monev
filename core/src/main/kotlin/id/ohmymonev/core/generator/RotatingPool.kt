package id.ohmymonev.core.generator

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A pool of user-provided reusable sentences (pelajaran / kendala) handed out with a
 * least-recently-used policy and a cooldown, so the mentor never sees the exact same
 * text within [cooldownDays].
 */
class RotatingPool(
    items: List<String>,
    private val cooldownDays: Int = 7,
    initialLastUsed: Map<String, LocalDate> = emptyMap(),
) {
    private val known: List<String> = items.filter { it.isNotBlank() }.distinct()
    private val lastUsed: MutableMap<String, LocalDate> = initialLastUsed.toMutableMap()

    /** Picks the next sentence for [today] and marks it used. Null when nothing is eligible. */
    fun next(today: LocalDate): String? {
        if (known.isEmpty()) return null
        val eligible = known.filter { item ->
            val used = lastUsed[item] ?: return@filter true
            ChronoUnit.DAYS.between(used, today) > cooldownDays
        }
        val pick = eligible
            .minByOrNull { lastUsed[it] ?: LocalDate.MIN }
            ?: return null
        lastUsed[pick] = today
        return pick
    }

    /** Last-used state to persist between runs. */
    fun snapshot(): Map<String, LocalDate> = lastUsed.toMap()
}

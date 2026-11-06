package id.ohmymonev.core.generator

/**
 * Turns yesterday's aktivitas blobs into today's continuation bullets (without the "- " prefix;
 * the pipeline adds it).
 */
class ContinuationRuleEngine(
    private val extraBullets: List<String> = DEFAULT_EXTRA_BULLETS,
) {
    fun bullets(previousAktivitas: String, minBullets: Int = 3, maxBullets: Int = 5): List<String> {
        val core = previousAktivitas.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.removePrefix("- ").trim() }
            .mapNotNull { VerbMapper.continuation(it) }
            .distinct()
        if (core.size >= maxBullets) return core.take(maxBullets)
        if (core.size >= minBullets) return core
        val topUp = extraBullets
            .filter { extra -> core.none { it.equals(extra, ignoreCase = true) } }
            .take(minBullets - core.size)
        return core + topUp
    }

    companion object {
        /**
         * Generic continuation bullets for thin days. They describe *continuing* open work;
         * they never invent new facts. Shipped defaults, user-editable in the app.
         */
        val DEFAULT_EXTRA_BULLETS: List<String> = listOf(
            "Melanjutkan peninjauan hasil pekerjaan hari sebelumnya",
            "Melanjutkan diskusi teknis dengan mentor terkait progres pekerjaan",
            "Melanjutkan pencatatan hasil pekerjaan pada laporan harian",
        )
    }
}

package id.ohmymonev.core.generator

/**
 * Maps the leading verb of yesterday's activity bullet to its continuation form:
 *   "Melakukan aktivitas X"   -> "Melanjutkan aktivitas X"
 *   "Membuat endpoint Y"      -> "Melanjutkan pembuatan endpoint Y"
 *   "Menyusun dokumen Z"      -> "Melanjutkan penyusunan dokumen Z"
 *
 * Deterministic by design: the generator only re-shapes the previous report's own content,
 * it never invents new facts. Unknown verbs fall back to a phrasing that drops the verb and
 * keeps the object, which still satisfies DeliveryGate rule G-8 (bullet opens with an active meN- verb).
 */
object VerbMapper {

    /** verb (lowercase) -> noun phrase inserted after "Melanjutkan ". */
    private val NOUN_FORMS: Map<String, String> = mapOf(
        "membuat" to "pembuatan",
        "menyusun" to "penyusunan",
        "membangun" to "pembangunan",
        "menguji" to "pengujian",
        "mengembangkan" to "pengembangan",
        "memasang" to "pemasangan",
        "merancang" to "perancangan",
        "menganalisis" to "analisis",
        "menulis" to "penulisan",
        "melaksanakan" to "pelaksanaan",
        "menyelesaikan" to "penyelesaian",
        "menambahkan" to "penambahan",
        "memperbaiki" to "perbaikan",
        "mengintegrasikan" to "integrasi",
        "menyiapkan" to "penyiapan",
        "menghapus" to "penghapusan",
        "meninjau" to "peninjauan",
        "mendokumentasikan" to "dokumentasi",
        "melatih" to "pelatihan",
        "mengumpulkan" to "pengumpulan",
        "memverifikasi" to "verifikasi",
        "mengevaluasi" to "evaluasi",
    )

    const val FALLBACK_PREFIX = "Melanjutkan aktivitas"

    /** Non-fabrication invariant: strip every person word from inherited text before reshaping. */
    private val PRONOUN_STRIP = Regex("""\b(saya|aku|kami|kita|penulis|anda)\b""", setOf(RegexOption.IGNORE_CASE))

    /**
     * Transforms ONE activity sentence into its continuation sentence, or null when nothing usable remains.
     */
    fun continuation(rawSentence: String): String? {
        val sentence = PRONOUN_STRIP.replace(rawSentence.trim(), " ").trim()
        if (sentence.isEmpty()) return null
        val verb = sentence.substringBefore(' ', "").lowercase()
        val rest = sentence.substringAfter(' ', "").trim()
        return when {
            verb == "melakukan" -> join("Melanjutkan", rest)
            NOUN_FORMS.containsKey(verb) -> join("Melanjutkan ${NOUN_FORMS.getValue(verb)}", rest)
            else -> join(FALLBACK_PREFIX, rest)
        }
    }

    private fun join(prefix: String, rest: String): String =
        if (rest.isEmpty()) prefix else "$prefix $rest"
}

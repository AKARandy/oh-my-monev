package id.ohmymonev.core.generator

/** Bounded verb changes; unsupported text must be edited, never guessed. */
object VerbMapper {

    /** verb (lowercase) -> noun phrase inserted after "Melanjutkan ". */
    private val NOUN_FORMS: Map<String, String> = mapOf(
        "memeriksa" to "pemeriksaan",
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

    fun continuation(rawSentence: String): String? {
        val sentence = rawSentence.trim()
        if (sentence.isEmpty()) return null
        val verb = sentence.substringBefore(' ').lowercase()
        val rest = sentence.substringAfter(' ', "").trim()
        return when {
            verb == "melanjutkan" -> sentence
            rest.isBlank() -> null
            verb == "melakukan" -> join("Melanjutkan", rest)
            NOUN_FORMS.containsKey(verb) -> join("Melanjutkan ${NOUN_FORMS.getValue(verb)}", rest)
            else -> null
        }
    }

    private fun join(prefix: String, rest: String): String =
        if (rest.isEmpty()) prefix else "$prefix $rest"
}

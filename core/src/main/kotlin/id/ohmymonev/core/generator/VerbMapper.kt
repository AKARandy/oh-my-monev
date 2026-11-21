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

    private val WHITESPACE = Regex("""[\s\u00A0\u2007\u202F]+""")

    fun continuation(rawSentence: String): String? {
        val sentence = rawSentence.trim { it.isWhitespace() || it == '\u00A0' || it == '\u2007' || it == '\u202F' }
        if (sentence.isEmpty()) return null
        val match = WHITESPACE.find(sentence)
        val verb = (if (match == null) sentence else sentence.substring(0, match.range.first)).lowercase()
        val rest = (if (match == null) "" else sentence.substring(match.range.last + 1))
            .trim { it.isWhitespace() || it == '\u00A0' || it == '\u2007' || it == '\u202F' }
        if (rest.isBlank() || rest.none { it.isLetterOrDigit() }) return null
        return when {
            verb == "melanjutkan" -> join("Melanjutkan", rest)
            verb == "melakukan" -> join("Melanjutkan", rest)
            NOUN_FORMS.containsKey(verb) -> join("Melanjutkan ${NOUN_FORMS.getValue(verb)}", rest)
            else -> null
        }
    }

    private fun join(prefix: String, rest: String): String =
        if (rest.isEmpty()) prefix else "$prefix $rest"
}

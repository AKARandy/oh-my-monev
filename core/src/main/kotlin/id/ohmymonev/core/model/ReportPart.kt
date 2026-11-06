package id.ohmymonev.core.model

/**
 * One of the three labeled parts of the Monev daily report form.
 *
 * Facts verified against the live site 2026-11-06:
 *  - AKTIVITAS is submitted as bulleted lines starting with "- " (3-5 bullets).
 *  - PELAJARAN and KENDALA are single paragraphs.
 *  - Each part must be at least 100 characters (site-side validation), maximum 5000.
 */
enum class ReportPart(
    val jsonKey: String,
    val label: String,
    val bulleted: Boolean,
) {
    AKTIVITAS("aktivitas", "Uraian aktivitas", true),
    PELAJARAN("pelajaran", "Pembelajaran yang diperoleh", false),
    KENDALA("kendala", "Kendala yang dialami", false),
    ;

    fun text(draft: ReportDraft): String = when (this) {
        AKTIVITAS -> draft.aktivitas
        PELAJARAN -> draft.pelajaran
        KENDALA -> draft.kendala
    }
}

package id.ohmymonev.core.model

/** One of the three labeled parts of the Monev daily report form. */
enum class ReportPart(
    val jsonKey: String,
    val label: String,
) {
    AKTIVITAS("aktivitas", "Uraian aktivitas"),
    PELAJARAN("pelajaran", "Pembelajaran yang diperoleh"),
    KENDALA("kendala", "Kendala yang dialami"),
    ;

    fun text(draft: ReportDraft): String = when (this) {
        AKTIVITAS -> draft.aktivitas
        PELAJARAN -> draft.pelajaran
        KENDALA -> draft.kendala
    }
}

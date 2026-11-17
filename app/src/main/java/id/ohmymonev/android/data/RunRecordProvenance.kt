package id.ohmymonev.android.data

import id.ohmymonev.core.enginecore.RunRecord

internal fun RunRecord.provenanceSummary(separator: String = " · "): String = listOf(
    when (contentSource) {
        "AUTHORED" -> "Draf Anda"
        "TEMPLATE" -> "Templat Anda"
        "RULES" -> "Lanjutan aturan"
        "MODEL" -> "Parafrasa model"
        else -> "Sumber tidak diketahui"
    },
    when (locationSource) {
        "DEVICE" -> "Lokasi perangkat"
        "IP" -> "Perkiraan lokasi IP"
        else -> null
    },
    when (verification) {
        "CONFIRMED" -> "Tercatat di Monev"
        "UNCONFIRMED" -> "Belum terkonfirmasi"
        "FAILED" -> "Belum tercatat"
        else -> null
    },
).filterNotNull().joinToString(separator)

internal fun RunRecord.provenanceDetails(): List<Pair<String, String>> = buildList {
    trigger?.let { value -> add("Pemicu" to when (value) {
        "MANUAL" -> "Manual"
        "EMERGENCY" -> "Darurat"
        else -> value
    }) }
    contentSource?.let { value -> add("Sumber isi" to when (value) {
        "AUTHORED" -> "Tulisan Anda"
        "TEMPLATE" -> "Templat"
        "RULES" -> "Aturan"
        "MODEL" -> "Model"
        else -> value
    }) }
    sourceDate?.let { add("Tanggal sumber" to it) }
    if (reusedPreview == true) add("Pratinjau" to "Dipakai ulang")
    locationSource?.let { value -> add("Lokasi" to when (value) {
        "DEVICE" -> "Perangkat"
        "IP" -> "Perkiraan IP"
        else -> value
    }) }
    fallbackReason?.let { value -> add("Alasan IP" to when (value) {
        "timeout" -> "Lokasi lambat"
        "permission" -> "Izin lokasi tidak tersedia"
        "disabled" -> "Lokasi mati"
        "unavailable" -> "Lokasi tidak tersedia"
        "not_eligible" -> "Lokasi tidak dipakai"
        else -> value.replace('_', ' ')
    }) }
    lastStage?.let { add("Tahap" to it.replace('_', ' ')) }
    verification?.let { value -> add("Pemeriksaan" to when (value) {
        "CONFIRMED" -> "Terkonfirmasi"
        "UNCONFIRMED" -> "Belum terkonfirmasi"
        "FAILED" -> "Tidak cocok"
        else -> value
    }) }
}

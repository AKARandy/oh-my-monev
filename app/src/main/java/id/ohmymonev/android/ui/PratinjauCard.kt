package id.ohmymonev.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ohmymonev.core.generator.GenerationOutcome
import id.ohmymonev.core.model.ReportDraft

/**
 * Kartu pratinjau: bukti terlihat bahwa aplikasi MAMPU membuat laporan hari ini
 * dari laporan kemarin. Menampilkan teks asli yang akan diisikan mesin — bukan klaim.
 */
@Composable
fun PratinjauCard(
    outcome: GenerationOutcome?,
    loading: Boolean,
    source: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("automatic-preview"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Pratinjau otomatis", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Laporan hari ini dari laporan pada hari kerja sebelumnya.",
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRefresh, enabled = !loading) { Text("Perbarui", fontSize = 18.sp) }
            }

            when {
                loading -> Text(
                    "Menyusun dari laporan sebelumnya…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                outcome is GenerationOutcome.Failed -> Text(
                    text = when {
                        // Alasan dari pembaca situs ditampilkan apa adanya (sesi habis, hari libur, dll).
                        outcome.detail.contains("sesi") || outcome.detail.contains("gagal") ||
                            outcome.detail.contains("halaman") || outcome.detail.contains("situs") -> outcome.detail
                        outcome.reason.name == "NO_SOURCE_MATERIAL" ->
                            source ?: "Belum ada laporan sebelumnya. Tulis laporan hari ini."
                        outcome.reason.name == "TOO_FEW_BULLETS" ->
                            "Laporan kemarin terlalu pendek untuk ditiru menjadi 3 butir."
                        outcome.reason.name == "PELAJARAN_POOL_EXHAUSTED" || outcome.reason.name == "KENDALA_POOL_EXHAUSTED" ->
                            "Kalimat cadangan sudah terpakai semua. Tambah lewat editor Tulisan hari ini."
                        else -> "Tidak bisa menyusun: " + outcome.detail
                    },
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                outcome is GenerationOutcome.Generated -> {
                    Text(
                        source ?: "Dari laporan kemarin:",
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    PreviewPart("Aktivitas", outcome.draft.aktivitas)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PreviewPart("Pelajaran", outcome.draft.pelajaran)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PreviewPart("Kendala", outcome.draft.kendala)
                    Text(
                        "Pratinjau saja. Teks final diperiksa ulang sebelum dikirim.",
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Text(
                    "Pratinjau akan disusun setelah login.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreviewPart(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(
            text,
            fontSize = 18.sp,
            lineHeight = 26.sp,
            maxLines = 6,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
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
                Column {
                    Text("Pratinjau otomatis", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Cara mesin mengisi hari ini dari laporan kemarin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRefresh, enabled = !loading) { Text("Perbarui") }
            }

            when {
                loading -> Text(
                    "Menyusun dari laporan sebelumnya…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                outcome is GenerationOutcome.Failed -> Text(
                    text = when (outcome.reason.name) {
                        "NO_SOURCE_MATERIAL" -> "Belum ada laporan sebelumnya untuk ditiru. Tulis hari ini, besok mulai pratinjau ini terisi."
                        "TOO_FEW_BULLETS" -> "Laporan kemarin terlalu pendek untuk ditiru menjadi 3 butir."
                        "PELAJARAN_POOL_EXHAUSTED", "KENDALA_POOL_EXHAUSTED" -> "Kalimat cadangan sudah terpakai semua. Tambah lewat tab Tulis."
                        else -> "Tidak bisa menyusun: " + outcome.detail
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                outcome is GenerationOutcome.Generated -> {
                    Text(
                        "Dari laporan kemarin:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PreviewPart("Aktivitas", outcome.draft.aktivitas)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PreviewPart("Pelajaran", outcome.draft.pelajaran)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PreviewPart("Kendala", outcome.draft.kendala)
                    Text(
                        "Pratinjau saja. Teks final diperiksa ulang sebelum dikirim.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Text(
                    "Belum diperiksa.",
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
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 6,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

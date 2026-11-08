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
    holiday: Boolean = false,
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
                        "Teks yang akan digunakan untuk mengisi formulir.",
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRefresh, enabled = !loading) { Text("Perbarui", fontSize = 18.sp) }
            }

            if (holiday) Text("Laporan tidak diperlukan hari ini. Pratinjau ini hanya untuk referensi.", fontSize = 20.sp, lineHeight = 28.sp)
            when {
                loading -> Text(
                    "Menyusun dari laporan sebelumnya…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                outcome is GenerationOutcome.Failed -> Text(
                    text = outcome.detail,
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
                        "Pengisian memakai teks ini. Aplikasi memeriksa teks sebelum mengisi formulir.",
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
        )
    }
}

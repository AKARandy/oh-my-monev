@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package id.ohmymonev.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ohmymonev.android.engine.PreparedReport
import id.ohmymonev.core.model.ReportDraft

@Composable
internal fun PratinjauCard(
    prepared: PreparedReport?,
    loading: Boolean,
    onRefresh: () -> Unit,
    holiday: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var detail by remember(prepared) { mutableStateOf<String?>(null) }
    var showIssues by remember(prepared) { mutableStateOf(false) }
    val source = prepared?.sourceReport
    val written = prepared?.written
    val hasText = source != null || written != null
    Surface(
        modifier = modifier.fillMaxWidth().testTag("automatic-preview"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Pratinjau", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary)
                TextButton(onClick = onRefresh, enabled = !loading) { Text("Perbarui", fontSize = 18.sp) }
            }
            if (holiday) PreviewBadge("Hari libur")
            when {
                loading -> Text("Mencari laporan Hadir…", fontSize = 20.sp, lineHeight = 28.sp)
                prepared == null -> Text("Login untuk melihat laporan.", fontSize = 20.sp, lineHeight = 28.sp)
                !hasText -> {
                    Text(if (prepared.sourceUnavailable) "Sumber belum terbaca" else "Belum ada laporan Hadir yang dapat digunakan",
                        fontSize = 20.sp, lineHeight = 28.sp,
                        color = if (prepared.sourceUnavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (prepared.sourceUnavailable) Text(prepared.issues.joinToString("\n"), fontSize = 18.sp, lineHeight = 26.sp)
                }
                else -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreviewBadge(if (source != null) "Hadir · ${source.label()}" else "Tulisan hari ini")
                        PreviewBadge(if (prepared.issues.isEmpty()) "Draf siap" else "Draf perlu diperiksa", attention = prepared.issues.isNotEmpty())
                    }
                    Text(source?.aktivitas ?: written!!.aktivitas, fontSize = 20.sp, lineHeight = 28.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (prepared.issues.isNotEmpty()) {
                        Text("Perbaiki tulisan hari ini.", fontSize = 18.sp, lineHeight = 26.sp)
                        TextButton(onClick = { showIssues = !showIssues }) {
                            Text(if (showIssues) "Tutup rincian" else "Rincian", fontSize = 18.sp)
                        }
                        if (showIssues) Text(prepared.issues.joinToString("\n"), fontSize = 20.sp, lineHeight = 28.sp)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { detail = if (detail == "source") null else "source" }) {
                            Text(if (detail == "source") "Tutup laporan" else "Lihat laporan", fontSize = 18.sp)
                        }
                        if (prepared.draft != null) TextButton(onClick = { detail = if (detail == "draft") null else "draft" }) {
                            Text(if (detail == "draft") "Tutup draf" else "Lihat draf", fontSize = 18.sp)
                        }
                    }
                    if (detail != null) {
                        val text = if (detail == "source") source?.report() ?: written!!.let { ReportDraft(it.aktivitas, it.pelajaran, it.kendala) }
                            else prepared.draft!!.let { ReportDraft(it.aktivitas, it.pelajaran, it.kendala) }
                        ReportDetails(text, if (detail == "source") "Laporan sumber" else "Draf hari ini")
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBadge(label: String, attention: Boolean = false) {
    Surface(color = if (attention) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (attention) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(10.dp)) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 18.sp, lineHeight = 26.sp)
    }
}

@Composable
internal fun ReportDetails(draft: ReportDraft, title: String) {
    Column(Modifier.fillMaxWidth().testTag("report-details"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
        for ((label, text) in listOf("Aktivitas" to draft.aktivitas, "Pelajaran" to draft.pelajaran, "Kendala" to draft.kendala)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                Text(text, fontSize = 20.sp, lineHeight = 28.sp)
            }
        }
    }
}

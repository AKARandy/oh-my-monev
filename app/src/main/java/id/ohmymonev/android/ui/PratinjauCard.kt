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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ohmymonev.android.engine.PreparedReport
import id.ohmymonev.core.model.ReportDraft

@Composable
internal fun PratinjauCard(
    prepared: PreparedReport?,
    loading: Boolean,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean = true,
    holiday: Boolean = false,
    loadingText: String = "Mencari laporan Hadir…",
    modifier: Modifier = Modifier,
) {
    var open by remember(prepared) { mutableStateOf(false) }
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
                TextButton(onClick = loggedClick("PratinjauCard:67", onRefresh), enabled = refreshEnabled && !loading) { Text("Perbarui", fontSize = 18.sp) }
            }
            when {
                loading -> Text(loadingText, fontSize = 20.sp, lineHeight = 28.sp)
                prepared == null -> Text("Login untuk melihat laporan.", fontSize = 20.sp, lineHeight = 28.sp)
                !hasText -> {
                    // Sebut penyebab sebenarnya: pesan generik menyembunyikan perbedaan mode.
                    Text(prepared.issues.joinToString("\n").ifBlank { "Belum ada laporan Hadir yang dapat digunakan" },
                        fontSize = 20.sp, lineHeight = 28.sp,
                        color = if (prepared.sourceUnavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                else -> {
                    Text(
                        (if (source != null) "Hadir · ${source.label()}" else "Tulisan hari ini") + if (holiday) " · Hari libur" else "",
                        fontSize = 18.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(source?.aktivitas ?: written!!.aktivitas, fontSize = 20.sp, lineHeight = 28.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Button(onClick = loggedClick("PratinjauCard:85") { open = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Buka pratinjau", fontSize = 18.sp)
                    }
                }
            }
        }
    }
    if (open && prepared != null) Dialog(onDismissRequest = { open = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PreviewPage(prepared, onBack = { open = false })
    }

}

@Composable
internal fun PreviewPage(prepared: PreparedReport, onBack: () -> Unit) {
    var showDraft by remember(prepared) { mutableStateOf(false) }
    var showCause by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val source = prepared.sourceReport
    val written = prepared.written
    Surface(Modifier.fillMaxSize().testTag("preview-page"), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = loggedClick("PratinjauCard:110", onBack)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") }
                Text("Pratinjau laporan", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary)
            }
            Column(Modifier.weight(1f).scrollIndicator(scroll).verticalScroll(scroll).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(if (source != null) "Hadir · ${source.label()}" else "Tulisan hari ini", fontSize = 18.sp)
                if (prepared.issues.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Draf otomatis belum siap", fontSize = 18.sp)
                            TextButton(onClick = loggedClick("PratinjauCard:122") { showCause = true }) { Text("Lihat penyebab", fontSize = 18.sp) }
                        }
                    }
                }
                if (prepared.draft != null && prepared.issues.isEmpty() && source != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showDraft, onClick = loggedClick("PratinjauCard:128") { showDraft = false }, label = { Text("Sumber", fontSize = 18.sp) })
                        FilterChip(selected = showDraft, onClick = loggedClick("PratinjauCard:129") { showDraft = true }, label = { Text("Draf otomatis", fontSize = 18.sp) })
                    }
                }
                val text = if (showDraft) prepared.draft!!.let { ReportDraft(it.aktivitas, it.pelajaran, it.kendala) }
                    else source?.report() ?: written!!.let { ReportDraft(it.aktivitas, it.pelajaran, it.kendala) }
                ReportDetails(text, "")
            }
        }
    }
    if (showCause) AlertDialog(onDismissRequest = { showCause = false },
        title = { Text("Mengapa draf belum siap?") },
        text = { Text(previewIssueSummary(prepared.issues), fontSize = 20.sp, lineHeight = 28.sp) },
        confirmButton = { TextButton(onClick = loggedClick("PratinjauCard:141") { showCause = false }) { Text("Mengerti") } })
}

internal fun previewIssueSummary(issues: List<String>): String = issues.flatMap { it.lines() }
    .filter { it.isNotBlank() && it != "Periksa aktivitas berikut:" }
    .map { line ->
        val number = Regex("^Butir (\\d+):").find(line)?.groupValues?.get(1)
        if (number != null) "Butir $number belum dapat diolah otomatis."
        else if (line.length > 160) "Format sumber belum sesuai aturan draf otomatis."
        else line
    }.distinct().take(3).joinToString("\n")

@Composable
internal fun ReportDetails(draft: ReportDraft, title: String) {
    Column(Modifier.fillMaxWidth().testTag("report-details"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (title.isNotEmpty()) Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
        for ((label, text) in listOf("Aktivitas" to draft.aktivitas, "Pelajaran" to draft.pelajaran, "Kendala" to draft.kendala)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                Text(text, fontSize = 20.sp, lineHeight = 28.sp)
            }
        }
    }
}

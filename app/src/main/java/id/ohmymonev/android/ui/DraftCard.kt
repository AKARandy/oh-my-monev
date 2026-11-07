package id.ohmymonev.android.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.StoredDraft
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.schedule.ScheduleCalculator
import id.ohmymonev.core.validator.DeliveryGate
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kartu tulisan hari ini, menyatu di Beranda (bukan tab terpisah).
 * Lipat dua: ringkasan satu baris saat tertutup, editor penuh saat terbuka.
 * Satu dialog menggerakkan semuanya: simpan tulisan + nyalakan isi otomatis.
 */
@Composable
fun DraftCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { AppStore(context) }
    val scope = rememberCoroutineScope()
    val todayIso = LocalDate.now(ScheduleCalculator.asiaJakarta).toString()

    var aktivitas by remember { mutableStateOf("") }
    var pelajaran by remember { mutableStateOf("") }
    var kendala by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmEnableAutomation by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(expanded) {
        if (expanded && !loaded) {
            val stored = withContext(Dispatchers.IO) { store.todayDraft(todayIso) }
            stored?.let {
                aktivitas = it.aktivitas
                pelajaran = it.pelajaran
                kendala = it.kendala
            }
            loaded = true
        }
    }

    val draft = ReportDraft(aktivitas, pelajaran, kendala)
    val gate = DeliveryGate().validate(draft)
    val isEmpty = aktivitas.isBlank() && pelajaran.isBlank() && kendala.isBlank()

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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tulisan hari ini", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when {
                            isEmpty -> "Kosong. Mesin akan memakai laporan kemarin bila ada."
                            !gate.passed -> "Tertulis, belum memenuhi aturan situs."
                            else -> "Tertulis dan siap diisi ke situs."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    if (expanded && dirty) {
                        confirmDiscard = true
                    } else {
                        onExpandedChange(!expanded)
                    }
                }) { Text(if (expanded) "Tutup" else if (isEmpty) "Tulis" else "Ubah") }
            }

            if (expanded) {
                EditorField("Aktivitas", "Uraian aktivitas minimal 100 karakter", aktivitas) {
                    aktivitas = it; dirty = true
                }
                EditorField("Pelajaran", "Ilmu yang diperoleh minimal 100 karakter", pelajaran) {
                    pelajaran = it; dirty = true
                }
                EditorField("Kendala", "Kendala yang dialami minimal 100 karakter", kendala) {
                    kendala = it; dirty = true
                }

                if (!gate.passed && !isEmpty) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            for (violation in gate.violations.take(4)) {
                                Text(
                                    "${violation.id}: ${violation.message}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = { confirmEnableAutomation = true },
                    enabled = loaded && !isEmpty && gate.passed,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("Simpan dan nyalakan isi otomatis", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Buang perubahan?") },
            text = { Text("Tulisan yang belum disimpan akan hilang dari layar ini. Yang tersimpan sebelumnya tetap aman.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    dirty = false
                    onExpandedChange(false)
                }) { Text("Buang") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Lanjut menulis") }
            },
        )
    }

    if (confirmEnableAutomation) {
        AlertDialog(
            onDismissRequest = { confirmEnableAutomation = false },
            title = { Text("Simpan laporan?") },
            text = {
                Text(
                    "Aplikasi menyimpan tulisan ini. Pengisian mengikuti hari, jam, dan tindakan yang Anda pilih di Jadwal.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnableAutomation = false
                    val toSave = StoredDraft(todayIso, aktivitas, pelajaran, kendala)
                    scope.launch {
                        withContext(Dispatchers.IO) { store.saveTodayDraft(toSave) }
                        dirty = false
                        Toast.makeText(context, "Laporan tersimpan.", Toast.LENGTH_SHORT).show()
                        onSaved()
                        onExpandedChange(false)
                    }
                }) { Text("Ya, simpan") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnableAutomation = false }) { Text("Batal") }
            },
        )
    }
}

@Composable
private fun EditorField(
    label: String,
    hint: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        supportingText = {
            Text(
                text = "${value.length} karakter",
                style = MaterialTheme.typography.labelSmall,
                color = if (value.length >= 100) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

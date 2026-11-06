package id.ohmymonev.android.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
 * Tulis laporan hari ini di aplikasi. Yang disimpan di sini yang akan diisikan mesin
 * ke situs saat jadwal tiba (atau saat tombol "Isi sekarang").
 */
@Composable
fun DraftEditorTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { AppStore(context) }
    val scope = rememberCoroutineScope()
    val todayIso = LocalDate.now(ScheduleCalculator.asiaJakarta).toString()

    var aktivitas by remember { mutableStateOf("") }
    var pelajaran by remember { mutableStateOf("") }
    var kendala by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val stored = withContext(Dispatchers.IO) { store.todayDraft(todayIso) }
        stored?.let {
            aktivitas = it.aktivitas
            pelajaran = it.pelajaran
            kendala = it.kendala
        }
        loaded = true
    }

    val draft = ReportDraft(aktivitas, pelajaran, kendala)
    val gate = DeliveryGate().validate(draft)
    val isEmpty = aktivitas.isBlank() && pelajaran.isBlank() && kendala.isBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tulis laporan", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Isi di sini, nanti mesin yang memasukkan ke situs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isEmpty) {
            Text(
                "Belum ada isian. Kalau kosong, mesin akan memakai laporan kemarin bila ada.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Field(
            label = "Aktivitas",
            hint = "Tulis uraian aktivitas minimal 100 karakter",
            value = aktivitas,
            onChange = { aktivitas = it },
        )
        Field(
            label = "Pelajaran",
            hint = "Tulis ilmu/pembelajaran yang diperoleh minimal 100 karakter",
            value = pelajaran,
            onChange = { pelajaran = it },
        )
        Field(
            label = "Kendala",
            hint = "Tulis kendala/hambatan yang dialami minimal 100 karakter",
            value = kendala,
            onChange = { kendala = it },
        )

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
            onClick = {
                val toSave = StoredDraft(todayIso, aktivitas, pelajaran, kendala)
                scope.launch {
                    withContext(Dispatchers.IO) { store.saveTodayDraft(toSave) }
                    Toast.makeText(context, "Laporan hari ini disimpan", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = loaded && !isEmpty && gate.passed,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Simpan laporan hari ini", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Field(
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

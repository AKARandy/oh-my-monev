package id.ohmymonev.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.provenanceDetails
import id.ohmymonev.core.enginecore.EngineOutcome
import id.ohmymonev.core.enginecore.RunRecord
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ID_LOCALE = java.util.Locale("id", "ID")
private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM", ID_LOCALE)
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
private const val MAX_ROWS = 100

private fun plain(outcome: EngineOutcome): String = when (outcome) {
    EngineOutcome.UNKNOWN -> "Hasil tidak jelas."
    EngineOutcome.LOGIN_REQUIRED -> "Sesi habis. Login ulang."
    EngineOutcome.LOADING_TIMEOUT -> "Situs lama terbuka."
    EngineOutcome.DAY_NOT_FILLABLE -> "Hari ini tidak bisa diisi."
    EngineOutcome.LOCATION_CHECK_FAILS -> "Lokasi tidak terbaca."
    EngineOutcome.FILL_OK -> "Sudah diisi, belum dikirim."
    EngineOutcome.READBACK_MISMATCH -> "Isian tidak cocok. Tidak dikirim."
    EngineOutcome.SUBMITTED -> "Sudah dikirim."
    EngineOutcome.SUBMIT_FAILED -> "Gagal kirim."
    EngineOutcome.RETRYABLE_ERROR -> "Koneksi gagal, dicoba lagi."
}

private fun dotColor(outcome: EngineOutcome): Color = when (outcome) {
    EngineOutcome.SUBMITTED, EngineOutcome.FILL_OK -> Color(0xFF17613A)
    EngineOutcome.DAY_NOT_FILLABLE -> Color(0xFF5F6570)
    EngineOutcome.LOGIN_REQUIRED,
    EngineOutcome.LOADING_TIMEOUT,
    EngineOutcome.LOCATION_CHECK_FAILS,
    EngineOutcome.READBACK_MISMATCH,
    EngineOutcome.SUBMIT_FAILED,
    EngineOutcome.RETRYABLE_ERROR,
    -> Color(0xFFDB162F)
    EngineOutcome.UNKNOWN -> Color(0xFF8A8F98)
}

/** Log: baris ringkas, satu warna per hasil, detail dilipat ke baris kecil. */
@Composable
fun LogTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appStore = remember { AppStore(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var records by remember { mutableStateOf<List<RunRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshKey) {
        loading = true
        records = withContext(Dispatchers.IO) {
            appStore.records().takeLast(MAX_ROWS).asReversed()
        }
        loading = false
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .scrollIndicator(scrollState)
            .verticalScroll(scrollState)
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Catatan", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Hasil tiap percobaan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = loggedClick("LogTab:112") { refreshKey++ }, enabled = !loading) {
                Text("Muat ulang")
            }
        }
        if (loading && records.isEmpty()) {
            Text(
                "Memuat catatan…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (records.isEmpty()) {
            Text(
                "Belum ada catatan.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        records.forEachIndexed { index, record ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            RecordRow(record)
        }
    }
}

@Composable
internal fun RecordRow(record: RunRecord) {
    var expanded by remember(record) { mutableStateOf(false) }
    val details = record.provenanceDetails()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .background(dotColor(record.outcome), CircleShape),
        )
        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .fillMaxWidth()
                .let { if (details.isNotEmpty()) it.clickable { expanded = !expanded } else it }
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    record.date,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    attemptTime(record.attemptedAtISO),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(plain(record.outcome), style = MaterialTheme.typography.bodyMedium)
            record.detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded && details.isNotEmpty()) {
                details.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(value, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text("Tutup rincian", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            } else if (details.isNotEmpty()) {
                Text("Lihat rincian", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun attemptTime(iso: String): String = try {
    val dt = OffsetDateTime.parse(iso).atZoneSameInstant(id.ohmymonev.core.schedule.ScheduleCalculator.asiaJakarta)
    dt.format(DATE_FMT) + ", " + dt.format(TIME_FMT) + " WIB"
} catch (_: Exception) {
    iso
}

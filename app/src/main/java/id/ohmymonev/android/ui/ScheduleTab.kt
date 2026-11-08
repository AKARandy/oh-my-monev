@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package id.ohmymonev.android.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.ReportTemplate
import id.ohmymonev.android.engine.PreparedReportService
import id.ohmymonev.android.schedule.AlarmScheduler
import id.ohmymonev.android.schedule.GuardService
import id.ohmymonev.android.schedule.TimeSettingsStore
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.schedule.ScheduleSettings
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_SHORT = mapOf(
    DayOfWeek.MONDAY to "Sen",
    DayOfWeek.TUESDAY to "Sel",
    DayOfWeek.WEDNESDAY to "Rab",
    DayOfWeek.THURSDAY to "Kam",
    DayOfWeek.FRIDAY to "Jum",
    DayOfWeek.SATURDAY to "Sab",
    DayOfWeek.SUNDAY to "Min",
)

/**
 * Dua fitur: Pengingat, dan Pengisian darurat (satu-satunya pengisi otomatis, selalu
 * isi lalu kirim) — plus kartu Templat laporan sebagai sumber isian mode templat.
 * Editor menyimpan perubahan saat Simpan; Batal membuang perubahan.
 */
@Composable
fun ScheduleTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { TimeSettingsStore(context) }
    val appStore = remember { AppStore(context) }
    val scope = rememberCoroutineScope()
    val saveMutex = remember { Mutex() }

    var loaded by remember { mutableStateOf<ScheduleSettings?>(null) }
    var editing by remember { mutableStateOf<AlarmId?>(null) }
    var editingTemplate by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf<ReportTemplate?>(null) }
    // Draf editor: hanya ditulis ke penyimpanan saat Simpan ditekan.
    var pendingSubmit by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingAuto by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmMasterOff by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        template = withContext(Dispatchers.IO) { appStore.template() }
        loaded = store.settingsOnce()
    }

    val workingDays = id.ohmymonev.android.schedule.WorkDaysStore(context).days()
    val current = loaded
    if (current == null) {
        Column(
            modifier = modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Memuat…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    fun save(update: (ScheduleSettings) -> ScheduleSettings) {
        scope.launch {
            saveMutex.withLock {
                val settings = update(loaded ?: return@withLock)
                if (settings == loaded) return@withLock
                store.save(settings)
                loaded = settings
                AlarmScheduler.armAll(context, settings)
                if (settings.masterEnabled) GuardService.ensure(context) else GuardService.stop(context)
                Toast.makeText(context, "Tersimpan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun refreshTemplate() {
        scope.launch { template = withContext(Dispatchers.IO) { appStore.template() } }
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .scrollIndicator(scrollState)
            .verticalScroll(scrollState)
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Jadwal", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Mengikuti hari kerja profil Monev. Semua jam memakai WIB.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Aktifkan semua fitur", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (!current.masterEnabled) "Jadwal nonaktif" else if (workingDays == null) "Hari kerja belum dimuat" else "Jadwal aktif",
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                    )
                }
                Switch(
                    modifier = Modifier.testTag("master-toggle"),
                    checked = current.masterEnabled,
                    onCheckedChange = { on ->
                        if (on) save { it.copy(masterEnabled = true) } else confirmMasterOff = true
                    },
                )
            }
        }

        AlarmCard(
            title = "Pengingat",
            time = current.reminderTime,
            days = workingDays?.let { current.reminderDays intersect it } ?: current.reminderDays,
            enabled = current.reminderEnabled,
            controlsEnabled = current.masterEnabled,
            effective = current.reminderEnabled && current.masterEnabled,
            detail = "Mengingatkan Anda untuk mengisi laporan.",
            onToggle = { on -> save { it.copy(reminderEnabled = on) } },
        ) {
            editing = AlarmId.REMINDER
        }

        AlarmCard(
            title = "Pengisian darurat",
            time = current.escalationTime,
            days = workingDays?.let { current.escalationDays intersect it } ?: current.escalationDays,
            enabled = current.escalationEnabled,
            controlsEnabled = current.masterEnabled,
            effective = current.escalationEnabled && current.masterEnabled,
            detail = when (current.escalationSource) {
                ScheduleSettings.ContentSource.TEMPLATE ->
                    "Terpicu pada jam terjadwal bila laporan belum terkirim. Isian memakai templat Anda."
                ScheduleSettings.ContentSource.AUTOMATIC ->
                    "Terpicu pada jam terjadwal bila laporan belum terkirim. Isian melanjutkan laporan sebelumnya. Eksperimen, berisiko."
            },
            onToggle = { on -> save { it.copy(escalationEnabled = on) } },
        ) {
            editing = AlarmId.ESCALATION
        }

        TemplateCard(
            status = if (template == null) "Belum dibuat" else "Siap dipakai",
            onOpen = { editingTemplate = true },
        )
    }

    editing?.let { which ->
        AlarmEditor(
            title = when (which) {
                AlarmId.REMINDER -> "Pengingat"
                AlarmId.ESCALATION -> "Pengisian darurat"
            },
            initialTime = when (which) {
                AlarmId.REMINDER -> current.reminderTime
                AlarmId.ESCALATION -> current.escalationTime
            },
            initialEnabled = when (which) {
                AlarmId.REMINDER -> current.reminderEnabled
                AlarmId.ESCALATION -> current.escalationEnabled
            },
            initialDays = when (which) {
                AlarmId.REMINDER -> current.reminderDays
                AlarmId.ESCALATION -> current.escalationDays
            },
            initialSource = when (which) {
                AlarmId.REMINDER -> null
                AlarmId.ESCALATION -> current.escalationSource
            },
            templateReady = template != null,
            onDismiss = { editing = null },
            onSave = { time, enabled, days, source ->
                val persist: () -> Unit = {
                    save { latest ->
                        when (which) {
                            AlarmId.REMINDER -> latest.copy(reminderTime = time, reminderEnabled = enabled, reminderDays = days)
                            AlarmId.ESCALATION -> latest.copy(escalationTime = time, escalationEnabled = enabled, escalationDays = days, escalationSource = source)
                        }
                    }
                    editing = null
                }
                when {
                    // Ganti ke otomatis: dialog peringatannya sudah mencakup persetujuan kirim.
                    which == AlarmId.ESCALATION && source == ScheduleSettings.ContentSource.AUTOMATIC &&
                        current.escalationSource != ScheduleSettings.ContentSource.AUTOMATIC -> pendingAuto = persist
                    which == AlarmId.ESCALATION && enabled && !current.escalationEnabled -> pendingSubmit = persist
                    else -> persist()
                }
            },
        )
    }

    if (pendingAuto != null) {
        AlertDialog(
            onDismissRequest = { pendingAuto = null },
            title = { Text("Pakai otomatis?") },
            text = {
                Text(
                    "Aplikasi melanjutkan laporan hari sebelumnya. Anda tidak menulis apa pun. " +
                        "Ini pilihan terakhir dan mudah salah. Periksa pratinjau di Beranda.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingAuto?.invoke()
                    pendingAuto = null
                }) { Text("Paham, pakai otomatis") }
            },
            dismissButton = {
                TextButton(onClick = { pendingAuto = null }) { Text("Batal") }
            },
        )
    }

    if (pendingSubmit != null) {
        AlertDialog(
            onDismissRequest = { pendingSubmit = null },
            title = { Text("Nyalakan kirim otomatis?") },
            text = {
                Text(
                    "Aplikasi mengisi formulir. Aplikasi mencentang kotak pernyataan. " +
                        "Aplikasi lalu mengirim laporan ke situs. Periksa laporan sebelum mengizinkan pengiriman otomatis.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSubmit?.invoke()
                    pendingSubmit = null
                }) { Text("Nyalakan") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSubmit = null }) { Text("Batal") }
            },
        )
    }

    if (confirmMasterOff) {
        AlertDialog(
            onDismissRequest = { confirmMasterOff = false },
            title = { Text("Matikan kedua fitur?") },
            text = {
                Text(
                    "Aplikasi menghentikan Pengingat dan Pengisian darurat. " +
                        "Anda tetap dapat mengisi formulir melalui Beranda.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    save { it.copy(masterEnabled = false) }
                    confirmMasterOff = false
                }) { Text("Ya, matikan") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMasterOff = false }) { Text("Batal") }
            },
        )
    }

    if (editingTemplate) {
        TemplateEditor(
            current = template,
            clearBlocked = current.escalationSource == ScheduleSettings.ContentSource.TEMPLATE,
            onDismiss = { editingTemplate = false },
            onSave = { saved ->
                scope.launch {
                    withContext(Dispatchers.IO) { appStore.saveTemplate(saved) }
                    template = saved
                    editingTemplate = false
                    Toast.makeText(context, "Tersimpan", Toast.LENGTH_SHORT).show()
                }
            },
            onClear = {
                scope.launch {
                    withContext(Dispatchers.IO) { appStore.clearTemplate() }
                    template = null
                    editingTemplate = false
                }
            },
        )
    }
}

@Composable
internal fun AlarmEditor(
    title: String,
    initialTime: LocalTime,
    initialEnabled: Boolean,
    initialDays: Set<DayOfWeek>,
    initialSource: ScheduleSettings.ContentSource? = null,
    templateReady: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (LocalTime, Boolean, Set<DayOfWeek>, ScheduleSettings.ContentSource) -> Unit,
) {
    var enabled by remember { mutableStateOf(initialEnabled) }
    var days by remember { mutableStateOf(initialDays) }
    var source by remember { mutableStateOf(initialSource ?: ScheduleSettings.ContentSource.TEMPLATE) }
    val profileDays = id.ohmymonev.android.schedule.WorkDaysStore(LocalContext.current).days()
    val editorScrollState = rememberScrollState()
    var hour by remember { mutableStateOf(initialTime.hour.toString().padStart(2, '0')) }
    var minute by remember { mutableStateOf(initialTime.minute.toString().padStart(2, '0')) }
    val editorView = androidx.compose.ui.platform.LocalView.current
    val selectedTime = parseTimeParts(hour, minute)
    val templateBlocked = initialSource != null &&
        source == ScheduleSettings.ContentSource.TEMPLATE && !templateReady
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).windowInsetsPadding(WindowInsets.safeDrawing),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.scrollIndicator(editorScrollState).verticalScroll(editorScrollState).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Jam (WIB, 24 jam)", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                TimeWheels(hour, minute, { hour = it }, { minute = it })
                Text("Hari untuk fitur ini", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                DayChipsRow(days, profileDays) { day -> days = if (day in days) days - day else days + day }
                if (days.isEmpty()) Text("Pilih minimal satu hari.", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Aktifkan fitur ini", modifier = Modifier.weight(1f), fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (initialSource != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Sumber isian", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    SourceOption(
                        "Templat saya",
                        selected = source == ScheduleSettings.ContentSource.TEMPLATE,
                        enabled = templateReady,
                    ) { source = ScheduleSettings.ContentSource.TEMPLATE }
                    SourceOption(
                        "Otomatis (eksperimen)",
                        selected = source == ScheduleSettings.ContentSource.AUTOMATIC,
                    ) { source = ScheduleSettings.ContentSource.AUTOMATIC }
                    Text(
                        when (source) {
                            ScheduleSettings.ContentSource.TEMPLATE -> "Memakai templat Anda."
                            ScheduleSettings.ContentSource.AUTOMATIC -> "Melanjutkan laporan sebelumnya. Berisiko."
                        },
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (templateBlocked) {
                        Text(
                            "Templat belum lengkap. Buka Templat laporan.",
                            fontSize = 18.sp,
                            lineHeight = 26.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = days.isNotEmpty() && selectedTime != null && !templateBlocked,
                onClick = { editorView.clearFocus(); parseTimeParts(hour, minute)?.let { onSave(it, enabled, days, source) } },
            ) { Text("Simpan", fontSize = 18.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal", fontSize = 18.sp) } },
    )
}

private enum class AlarmId { REMINDER, ESCALATION }

/** Kartu ringkas templat: status satu baris + tombol Atur (editor dalam dialog). */
@Composable
private fun TemplateCard(status: String, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("template-card"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Templat laporan", style = MaterialTheme.typography.titleMedium)
                Text(status, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpen, modifier = Modifier.testTag("template-edit")) {
                Text("Atur", fontSize = 18.sp)
            }
        }
    }
}

/** Dialog templat: tiga bagian, divalidasi dengan aturan yang sama seperti saat dikirim. */
@Composable
internal fun TemplateEditor(
    current: ReportTemplate?,
    clearBlocked: Boolean,
    onDismiss: () -> Unit,
    onSave: (ReportTemplate) -> Unit,
    onClear: () -> Unit,
) {
    var aktivitas by remember(current) { mutableStateOf(current?.aktivitas ?: "") }
    var pelajaran by remember(current) { mutableStateOf(current?.pelajaran ?: "") }
    var kendala by remember(current) { mutableStateOf(current?.kendala ?: "") }
    val issues = PreparedReportService.validationIssues(ReportDraft(aktivitas, pelajaran, kendala))
    val editorScrollState = rememberScrollState()
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).windowInsetsPadding(WindowInsets.safeDrawing),
        onDismissRequest = onDismiss,
        title = { Text("Templat laporan") },
        text = {
            Column(
                modifier = Modifier.scrollIndicator(editorScrollState).verticalScroll(editorScrollState).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Teks ini menjadi isi laporan. Tanda {tanggal} diganti tanggal hari ini.",
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                EditorField("Aktivitas", "Uraian aktivitas minimal 100 karakter", aktivitas) { aktivitas = it }
                EditorField("Pelajaran", "Ilmu yang diperoleh minimal 100 karakter", pelajaran) { pelajaran = it }
                EditorField("Kendala", "Kendala yang dialami minimal 100 karakter", kendala) { kendala = it }
                if (issues.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            for (issue in issues.take(4)) {
                                Text(issue, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
                if (clearBlocked && current != null) {
                    Text(
                        "Mode Templat aktif. Ganti sumber di Pengisian darurat dahulu.",
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = issues.isEmpty(),
                onClick = { onSave(ReportTemplate(aktivitas, pelajaran, kendala)) },
            ) { Text("Simpan", fontSize = 18.sp) }
        },
        dismissButton = {
            Row {
                if (current != null && !clearBlocked) {
                    TextButton(onClick = onClear) { Text("Hapus", fontSize = 18.sp) }
                }
                TextButton(onClick = onDismiss) { Text("Batal", fontSize = 18.sp) }
            }
        },
    )
}

/** Keep the purpose and instructions as prominent as the scheduled time. */
@Composable
private fun AlarmCard(
    title: String,
    time: LocalTime,
    days: Set<DayOfWeek>,
    enabled: Boolean,
    effective: Boolean,
    controlsEnabled: Boolean,
    detail: String,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val textColor = if (controlsEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("alarm-card-$title"),
        shape = RoundedCornerShape(20.dp),
        color = if (controlsEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                )
                Switch(checked = enabled, onCheckedChange = onToggle, enabled = controlsEnabled, modifier = Modifier.testTag("alarm-toggle-$title"))
            }
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    time.format(HHMM),
                    fontSize = 48.sp,
                    lineHeight = 56.sp,
                    maxLines = 1,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                )
                TextButton(onClick = onOpen, enabled = controlsEnabled,
                    colors = ButtonDefaults.textButtonColors(disabledContentColor = textColor),
                    modifier = Modifier.align(Alignment.CenterVertically).testTag("alarm-edit-$title")) {
                    Text("Atur fitur", fontSize = 18.sp)
                }
            }
            Text(
                if (days.isEmpty()) "Tidak ada hari kerja terpilih" else if (days.size == DayOfWeek.entries.size) "Setiap hari" else days.sortedBy { it.value }.joinToString(", ") { DAY_SHORT.getValue(it) },
                fontSize = 18.sp,
                color = textColor,
                fontWeight = FontWeight.Medium,
            )
            if (!effective) {
                Text(
                    if (enabled) "Semua fitur nonaktif." else "Fitur ini nonaktif.",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
                    color = textColor,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            detail.split('\n').forEach { instruction ->
                Text(
                    instruction,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    color = textColor,
                )
            }
        }
    }
}

/**
 * Chip hari dengan status terpilih yang jelas: centang + kotak terisi saat aktif,
 * kotak kosong saat tidak. Tidak bergantung pada warna saja.
 */
@Composable
private fun DayChipsRow(
    selected: Set<DayOfWeek>,
    allowed: Set<DayOfWeek>?,
    onToggle: (DayOfWeek) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (day in DayOfWeek.entries) {
            val on = day in selected
            FilterChip(
                enabled = allowed == null || day in allowed,
                selected = on,
                onClick = { onToggle(day) },
                leadingIcon = {
                    if (on) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.height(18.dp),
                        )
                    } else {
                        Spacer(Modifier.size(18.dp))
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                ),
                border = BorderStroke(
                    1.dp,
                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                ),
                label = { Text(DAY_SHORT[day] ?: "", fontSize = 18.sp) },
            )
        }
    }
}

/** Pilihan sumber bersifat radio: satu yang terpilih, wajib memilih satu. */
@Composable
private fun SourceOption(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(
            selected = selected,
            role = Role.RadioButton,
            enabled = enabled,
            onClick = onSelect,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp).weight(1f), fontSize = 20.sp, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

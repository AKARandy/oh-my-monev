@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package id.ohmymonev.android.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
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
import id.ohmymonev.android.schedule.AlarmScheduler
import id.ohmymonev.android.schedule.GuardService
import id.ohmymonev.android.schedule.TimeSettingsStore
import id.ohmymonev.core.schedule.ScheduleSettings
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Setiap fitur memiliki hari, jam, dan tindakan sendiri.
 * Editor menyimpan perubahan saat Simpan; Batal membuang perubahan.
 */
@Composable
fun ScheduleTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { TimeSettingsStore(context) }
    val scope = rememberCoroutineScope()
    val saveMutex = remember { Mutex() }

    var loaded by remember { mutableStateOf<ScheduleSettings?>(null) }
    var editing by remember { mutableStateOf<AlarmId?>(null) }
    // Draf editor: hanya ditulis ke penyimpanan saat Simpan ditekan.
    var pendingSubmit by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmMasterOff by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { loaded = store.settingsOnce() }

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
                "Semua jam memakai WIB.",
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
                        if (current.masterEnabled) "Jadwal aktif" else "Jadwal nonaktif",
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
            days = current.reminderDays,
            enabled = current.reminderEnabled,
            controlsEnabled = current.masterEnabled,
            effective = current.reminderEnabled && current.masterEnabled,
            detail = "Mengingatkan Anda untuk mengisi laporan.",
            onToggle = { on -> save { it.copy(reminderEnabled = on) } },
        ) {
            editing = AlarmId.REMINDER
        }

        AlarmCard(
            title = "Pengisian",
            time = current.autofillTime,
            days = current.autofillDays,
            enabled = current.autofillEnabled,
            controlsEnabled = current.masterEnabled,
            effective = current.autofillEnabled && current.masterEnabled,
            detail = when (current.autofillPolicy) {
                ScheduleSettings.Policy.REMIND_ONLY -> "Mengirim pengingat saja."
                ScheduleSettings.Policy.FILL_ONLY -> "Mengisi formulir; Anda yang mengirim."
                ScheduleSettings.Policy.FILL_AND_SUBMIT -> "Mengisi dan mengirim laporan."
            },
            onToggle = { on -> save { it.copy(autofillEnabled = on) } },
        ) {
            editing = AlarmId.AUTOFILL
        }

        AlarmCard(
            title = "Peringatan terakhir",
            time = current.escalationTime,
            days = current.escalationDays,
            enabled = current.escalationEnabled,
            controlsEnabled = current.masterEnabled,
            effective = current.escalationEnabled && current.masterEnabled,
            detail = when {
                current.escalationAction == ScheduleSettings.Policy.REMIND_ONLY ->
                    "Mengirim pengingat terakhir."
                current.escalationAction == ScheduleSettings.Policy.FILL_AND_SUBMIT ->
                    "Mencoba isi dan kirim jika belum tercatat."
                else ->
                    "Mencoba mengisi; Anda yang mengirim."
            },
            onToggle = { on -> save { it.copy(escalationEnabled = on) } },
        ) {
            editing = AlarmId.ESCALATION
        }

    }

    editing?.let { which ->
        AlarmEditor(
            title = when (which) {
                AlarmId.REMINDER -> "Pengingat"
                AlarmId.AUTOFILL -> "Pengisian"
                AlarmId.ESCALATION -> "Peringatan terakhir"
            },
            initialTime = when (which) {
                AlarmId.REMINDER -> current.reminderTime
                AlarmId.AUTOFILL -> current.autofillTime
                AlarmId.ESCALATION -> current.escalationTime
            },
            initialEnabled = when (which) {
                AlarmId.REMINDER -> current.reminderEnabled
                AlarmId.AUTOFILL -> current.autofillEnabled
                AlarmId.ESCALATION -> current.escalationEnabled
            },
            initialDays = when (which) {
                AlarmId.REMINDER -> current.reminderDays
                AlarmId.AUTOFILL -> current.autofillDays
                AlarmId.ESCALATION -> current.escalationDays
            },
            initialAction = when (which) {
                AlarmId.REMINDER -> null
                AlarmId.AUTOFILL -> current.autofillPolicy
                AlarmId.ESCALATION -> current.escalationAction
            },
            onDismiss = { editing = null },
            onSave = { time, enabled, days, action ->
                val persist: () -> Unit = {
                    save { latest ->
                        when (which) {
                            AlarmId.REMINDER -> latest.copy(reminderTime = time, reminderEnabled = enabled, reminderDays = days)
                            AlarmId.AUTOFILL -> latest.copy(autofillTime = time, autofillEnabled = enabled, autofillDays = days, autofillPolicy = action)
                            AlarmId.ESCALATION -> latest.copy(escalationTime = time, escalationEnabled = enabled, escalationDays = days, escalationAction = action)
                        }
                    }
                    editing = null
                }
                val previousAction = when (which) {
                    AlarmId.REMINDER -> ScheduleSettings.Policy.REMIND_ONLY
                    AlarmId.AUTOFILL -> current.autofillPolicy
                    AlarmId.ESCALATION -> current.escalationAction
                }
                if (action == ScheduleSettings.Policy.FILL_AND_SUBMIT && previousAction != action) {
                    pendingSubmit = persist
                } else {
                    persist()
                }
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
            title = { Text("Matikan ketiga fitur?") },
            text = {
                Text(
                    "Aplikasi menghentikan Pengingat, Pengisian, dan Peringatan terakhir. " +
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
}

@Composable
internal fun AlarmEditor(
    title: String,
    initialTime: LocalTime,
    initialEnabled: Boolean,
    initialDays: Set<DayOfWeek>,
    initialAction: ScheduleSettings.Policy? = null,
    onDismiss: () -> Unit,
    onSave: (LocalTime, Boolean, Set<DayOfWeek>, ScheduleSettings.Policy) -> Unit,
) {
    var enabled by remember { mutableStateOf(initialEnabled) }
    var days by remember { mutableStateOf(initialDays) }
    var action by remember { mutableStateOf(initialAction ?: ScheduleSettings.Policy.REMIND_ONLY) }
    val editorScrollState = rememberScrollState()
    val timeState = androidx.compose.material3.rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = true,
    )
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.scrollIndicator(editorScrollState).verticalScroll(editorScrollState).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Jam (WIB, 24 jam)", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                androidx.compose.material3.TimeInput(state = timeState)
                Text("Hari untuk fitur ini", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                DayChipsRow(days) { day -> days = if (day in days) days - day else days + day }
                if (days.isEmpty()) Text("Pilih minimal satu hari.", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Aktifkan fitur ini", modifier = Modifier.weight(1f), fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (initialAction != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ActionOption("Isi formulir", action != ScheduleSettings.Policy.REMIND_ONLY) { fill ->
                        action = if (fill) ScheduleSettings.Policy.FILL_ONLY else ScheduleSettings.Policy.REMIND_ONLY
                    }
                    if (action == ScheduleSettings.Policy.REMIND_ONLY) {
                        Text("Aplikasi hanya mengirim notifikasi.", fontSize = 18.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurface)
                    } else {
                        ActionOption("Kirim laporan setelah mengisi", action == ScheduleSettings.Policy.FILL_AND_SUBMIT) { submit ->
                            action = if (submit) ScheduleSettings.Policy.FILL_AND_SUBMIT else ScheduleSettings.Policy.FILL_ONLY
                        }
                        Text(
                            if (action == ScheduleSettings.Policy.FILL_AND_SUBMIT) "Aplikasi mengirim laporan tanpa menunggu Anda." else "Anda perlu mengirim laporan melalui situs.",
                            fontSize = 18.sp,
                            lineHeight = 26.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = days.isNotEmpty(),
                onClick = { onSave(LocalTime.of(timeState.hour, timeState.minute), enabled, days, action) },
            ) { Text("Simpan", fontSize = 18.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal", fontSize = 18.sp) } },
    )
}

private enum class AlarmId { REMINDER, AUTOFILL, ESCALATION }

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
                if (days.size == DayOfWeek.entries.size) "Setiap hari" else days.sortedBy { it.value }.joinToString(", ") { DAY_SHORT.getValue(it) },
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
    onToggle: (DayOfWeek) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (day in DayOfWeek.entries) {
            val on = day in selected
            FilterChip(
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

@Composable
private fun ActionOption(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 8.dp).weight(1f), fontSize = 20.sp, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

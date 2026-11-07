package id.ohmymonev.android.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ohmymonev.android.schedule.AlarmScheduler
import id.ohmymonev.android.schedule.GuardService
import id.ohmymonev.android.schedule.TimeSettingsStore
import id.ohmymonev.core.schedule.ScheduleSettings
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_LETTERS = mapOf(
    DayOfWeek.MONDAY to "S",
    DayOfWeek.TUESDAY to "S",
    DayOfWeek.WEDNESDAY to "R",
    DayOfWeek.THURSDAY to "K",
    DayOfWeek.FRIDAY to "J",
    DayOfWeek.SATURDAY to "S",
    DayOfWeek.SUNDAY to "M",
)
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
 * Jadwal bergaya aplikasi alarm: SATU kartu per alarm (jam besar, hari, sakelar di kartu).
 * Tap kartu membuka lembar edit. Sakelar dan jam tidak lagi terpisah-pisah.
 */
@Composable
fun ScheduleTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { TimeSettingsStore(context) }
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf<ScheduleSettings?>(null) }
    var editing by remember { mutableStateOf<AlarmId?>(null) }
    var confirmSubmitPolicy by remember { mutableStateOf(false) }
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

    fun save(settings: ScheduleSettings) {
        scope.launch {
            store.save(settings)
            AlarmScheduler.armAll(context, settings)
            if (settings.masterEnabled) GuardService.ensure(context) else GuardService.stop(context)
            loaded = settings
            Toast.makeText(context, "Tersimpan", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Jadwal", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Ketuk kartu untuk mengubah jam dan hari.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AlarmCard(
            title = "Pengingat",
            time = current.reminderTime,
            days = current.activeDays,
            enabled = current.reminderEnabled && current.masterEnabled,
            detail = "Pesan lembut sebelum jam isi.",
        ) { editing = AlarmId.REMINDER }

        AlarmCard(
            title = "Isi otomatis",
            time = current.autofillTime,
            days = current.activeDays,
            enabled = current.autofillEnabled && current.masterEnabled,
            detail = "Mesin mengisi formulir Monev.",
        ) { editing = AlarmId.AUTOFILL }

        AlarmCard(
            title = "Peringatan terakhir",
            time = current.escalationTime,
            days = current.activeDays,
            enabled = current.escalationEnabled && current.masterEnabled,
            detail = "Bunyi keras kalau masih kosong.",
        ) { editing = AlarmId.ESCALATION }

        // Kebijakan isi: milik bersama ketiga alarm, satu tempat.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("Cara isi", style = MaterialTheme.typography.titleSmall)
                RadioRow("Hanya ingatkan", current.autofillPolicy == ScheduleSettings.Policy.REMIND_ONLY) {
                    save(current.copy(autofillPolicy = ScheduleSettings.Policy.REMIND_ONLY))
                }
                RadioRow("Isi saja, jangan kirim", current.autofillPolicy == ScheduleSettings.Policy.FILL_ONLY) {
                    save(current.copy(autofillPolicy = ScheduleSettings.Policy.FILL_ONLY))
                }
                RadioRow("Kirim otomatis", current.autofillPolicy == ScheduleSettings.Policy.FILL_AND_SUBMIT) {
                    if (current.autofillPolicy != ScheduleSettings.Policy.FILL_AND_SUBMIT) {
                        confirmSubmitPolicy = true
                    }
                }
                HorizontalDividerLight()
                Text("Kalau malam masih kosong", style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    "Ingatkan saja",
                    current.escalationPolicy == ScheduleSettings.EscalationPolicy.NOTIFY_ONLY,
                ) { save(current.copy(escalationPolicy = ScheduleSettings.EscalationPolicy.NOTIFY_ONLY)) }
                RadioRow(
                    "Isi otomatis terakhir",
                    current.escalationPolicy == ScheduleSettings.EscalationPolicy.AUTO_FILL_LAST_CHANCE,
                ) { save(current.copy(escalationPolicy = ScheduleSettings.EscalationPolicy.AUTO_FILL_LAST_CHANCE)) }
            }
        }
    }

    // Lembar edit per alarm: jam, hari, sakelar alarm ini, sakelar utama.
    editing?.let { which ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(when (which) {
                AlarmId.REMINDER -> "Pengingat"
                AlarmId.AUTOFILL -> "Isi otomatis"
                AlarmId.ESCALATION -> "Peringatan terakhir"
            }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = when (which) {
                            AlarmId.REMINDER -> current.reminderTime.format(HHMM)
                            AlarmId.AUTOFILL -> current.autofillTime.format(HHMM)
                            AlarmId.ESCALATION -> current.escalationTime.format(HHMM)
                        },
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text("Hari aktif", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (day in DayOfWeek.entries) {
                            FilterChip(
                                selected = day in current.activeDays,
                                onClick = {
                                    val next = if (day in current.activeDays) current.activeDays - day else current.activeDays + day
                                    save(current.copy(activeDays = next))
                                },
                                label = { Text(DAY_SHORT[day] ?: "") },
                            )
                        }
                    }
                    HorizontalDividerLight()
                    SwitchRowLocal(
                        "Alarm ini menyala",
                        when (which) {
                            AlarmId.REMINDER -> current.reminderEnabled
                            AlarmId.AUTOFILL -> current.autofillEnabled
                            AlarmId.ESCALATION -> current.escalationEnabled
                        },
                    ) { on ->
                        val next = when (which) {
                            AlarmId.REMINDER -> current.copy(reminderEnabled = on)
                            AlarmId.AUTOFILL -> current.copy(autofillEnabled = on)
                            AlarmId.ESCALATION -> current.copy(escalationEnabled = on)
                        }
                        save(next)
                    }
                    SwitchRowLocal("Semua alarm otomatis", current.masterEnabled) { on ->
                        if (!on) confirmMasterOff = true else save(current.copy(masterEnabled = true))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editing = null }) { Text("Selesai") }
            },
        )
    }

    if (confirmSubmitPolicy) {
        AlertDialog(
            onDismissRequest = { confirmSubmitPolicy = false },
            title = { Text("Nyalakan kirim otomatis?") },
            text = {
                Text(
                    "Aplikasi akan mencentang kotak pernyataan lalu menekan Simpan dan Kirim " +
                        "atas nama kamu. Isi teks tetap berasal dari tulisanmu.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    save(current.copy(autofillPolicy = ScheduleSettings.Policy.FILL_AND_SUBMIT))
                    confirmSubmitPolicy = false
                }) { Text("Nyalakan") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSubmitPolicy = false }) { Text("Batal") }
            },
        )
    }

    if (confirmMasterOff) {
        AlertDialog(
            onDismissRequest = { confirmMasterOff = false },
            title = { Text("Matikan semua alarm otomatis?") },
            text = {
                Text(
                    "Tidak ada pengingat, pengisian, atau peringatan malam. Tombol isi manual tetap bisa dipakai. " +
                        "Notifikasi penjaga juga hilang.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    save(current.copy(masterEnabled = false))
                    confirmMasterOff = false
                }) { Text("Ya, matikan") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMasterOff = false }) { Text("Batal") }
            },
        )
    }
}

private enum class AlarmId { REMINDER, AUTOFILL, ESCALATION }

/** Kartu alarm: jam besar di kiri, hari ringkas, sakelar di kanan. Satu kartu = satu alarm utuh. */
@Composable
private fun AlarmCard(
    title: String,
    time: LocalTime,
    days: Set<DayOfWeek>,
    enabled: Boolean,
    detail: String,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    time.format(HHMM),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (days.size == DayOfWeek.entries.size) "Setiap hari" else days.sorted().joinToString { DAY_SHORT[it] ?: "" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = enabled, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SwitchRowLocal(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HorizontalDividerLight() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

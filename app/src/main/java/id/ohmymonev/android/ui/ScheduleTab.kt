package id.ohmymonev.android.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.ohmymonev.android.schedule.AlarmScheduler
import id.ohmymonev.android.schedule.TimeSettingsStore
import id.ohmymonev.core.schedule.ScheduleSettings
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_LABELS = mapOf(
    DayOfWeek.MONDAY to "Sen",
    DayOfWeek.TUESDAY to "Sel",
    DayOfWeek.WEDNESDAY to "Rab",
    DayOfWeek.THURSDAY to "Kam",
    DayOfWeek.FRIDAY to "Jum",
    DayOfWeek.SATURDAY to "Sab",
    DayOfWeek.SUNDAY to "Min",
)

/** Jadwal: tiga waktu, hari, cara isi. Tanpa paragraf penjelasan. */
@Composable
fun ScheduleTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { TimeSettingsStore(context) }
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf<ScheduleSettings?>(null) }
    var reminderText by remember { mutableStateOf("") }
    var autofillText by remember { mutableStateOf("") }
    var escalationText by remember { mutableStateOf("") }
    var activeDays by remember { mutableStateOf(DayOfWeek.entries.toSet()) }
    var policy by remember { mutableStateOf(ScheduleSettings.Policy.FILL_ONLY) }
    var escalationPolicy by remember {
        mutableStateOf(ScheduleSettings.EscalationPolicy.AUTO_FILL_LAST_CHANCE)
    }
    var confirmSubmitPolicy by remember { mutableStateOf(false) }
    var masterEnabled by remember { mutableStateOf(true) }
    var reminderEnabled by remember { mutableStateOf(true) }
    var autofillEnabled by remember { mutableStateOf(true) }
    var escalationEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val s = store.settingsOnce()
        reminderText = s.reminderTime.format(HHMM)
        autofillText = s.autofillTime.format(HHMM)
        escalationText = s.escalationTime.format(HHMM)
        activeDays = s.activeDays
        policy = s.autofillPolicy
        escalationPolicy = s.escalationPolicy
        masterEnabled = s.masterEnabled
        reminderEnabled = s.reminderEnabled
        autofillEnabled = s.autofillEnabled
        escalationEnabled = s.escalationEnabled
        loaded = s
    }

    val reminderTime = parseHhmm(reminderText)
    val autofillTime = parseHhmm(autofillText)
    val escalationTime = parseHhmm(escalationText)
    val allValid = reminderTime != null && autofillTime != null && escalationTime != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Jadwal", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Jam dan cara isi.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val current = loaded
        if (current == null) {
            Text(
                "Memuat…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SectionLabel("Sakelar")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    SwitchRow(
                        "Alarm otomatis",
                        "Kalau dimatikan, pengingat dan isi otomatis tidak jalan. Tombol manual tetap bisa dipakai.",
                        masterEnabled,
                    ) {
                        masterEnabled = it
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow(
                        "Pengingat",
                        "Notifikasi malam sebelum laporan diisi.",
                        reminderEnabled && masterEnabled,
                    ) {
                        reminderEnabled = it
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow(
                        "Isi formulir otomatis",
                        "Formulir diisi sendiri pada jam isi otomatis.",
                        autofillEnabled && masterEnabled,
                    ) {
                        autofillEnabled = it
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow(
                        "Peringatan sebelum tengah malam",
                        "Bunyi keras kalau laporan masih kosong.",
                        escalationEnabled && masterEnabled,
                    ) {
                        escalationEnabled = it
                    }
                }
            }
            SectionLabel("Jam")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    TimeRow("Pengingat", reminderText, reminderTime != null) { reminderText = it }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TimeRow("Isi otomatis", autofillText, autofillTime != null) { autofillText = it }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TimeRow("Peringatan terakhir", escalationText, escalationTime != null) { escalationText = it }
                }
            }

            SectionLabel("Hari")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (day in DayOfWeek.entries.take(4)) DayChip(day, day in activeDays) { d, on ->
                        activeDays = if (on) activeDays + d else activeDays - d
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (day in DayOfWeek.entries.drop(4)) DayChip(day, day in activeDays) { d, on ->
                        activeDays = if (on) activeDays + d else activeDays - d
                    }
                }
            }

            SectionLabel("Cara isi")
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                RadioRow("Hanya ingatkan", policy == ScheduleSettings.Policy.REMIND_ONLY) {
                    policy = ScheduleSettings.Policy.REMIND_ONLY
                }
                RadioRow("Isi saja, jangan kirim", policy == ScheduleSettings.Policy.FILL_ONLY) {
                    policy = ScheduleSettings.Policy.FILL_ONLY
                }
                RadioRow("Kirim otomatis", policy == ScheduleSettings.Policy.FILL_AND_SUBMIT) {
                    if (policy != ScheduleSettings.Policy.FILL_AND_SUBMIT) confirmSubmitPolicy = true
                }
            }

            SectionLabel("Kalau malam ini masih kosong")
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                RadioRow(
                    "Ingatkan saja",
                    escalationPolicy == ScheduleSettings.EscalationPolicy.NOTIFY_ONLY,
                ) { escalationPolicy = ScheduleSettings.EscalationPolicy.NOTIFY_ONLY }
                RadioRow(
                    "Isi otomatis terakhir",
                    escalationPolicy == ScheduleSettings.EscalationPolicy.AUTO_FILL_LAST_CHANCE,
                ) { escalationPolicy = ScheduleSettings.EscalationPolicy.AUTO_FILL_LAST_CHANCE }
            }

            val interaction = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    val r = reminderTime ?: return@Button
                    val a = autofillTime ?: return@Button
                    val e = escalationTime ?: return@Button
                    scope.launch {
                        val saved = current.copy(
                            reminderTime = r,
                            autofillTime = a,
                            escalationTime = e,
                            activeDays = activeDays,
                            autofillPolicy = policy,
                            escalationPolicy = escalationPolicy,
                            masterEnabled = masterEnabled,
                            reminderEnabled = reminderEnabled,
                            autofillEnabled = autofillEnabled,
                            escalationEnabled = escalationEnabled,
                        )
                        store.save(saved)
                        AlarmScheduler.armAll(context, saved)
                        Toast.makeText(context, "Jadwal disimpan", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = allValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .pressScale(interaction),
                shape = RoundedCornerShape(16.dp),
                interactionSource = interaction,
            ) {
                Text("Simpan jadwal", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    if (confirmSubmitPolicy) {
        AlertDialog(
            onDismissRequest = { confirmSubmitPolicy = false },
            title = { Text("Nyalakan kirim otomatis?") },
            text = {
                Text(
                    "Aplikasi akan mencentang kotak pernyataan lalu menekan Simpan dan Kirim " +
                        "atas nama kamu.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        policy = ScheduleSettings.Policy.FILL_AND_SUBMIT
                        confirmSubmitPolicy = false
                    },
                ) { Text("Nyalakan") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSubmitPolicy = false }) { Text("Batal") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(java.util.Locale("id", "ID")),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TimeRow(
    label: String,
    value: String,
    valid: Boolean,
    onChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            isError = !valid,
            singleLine = true,
            textStyle = TextStyle(textAlign = TextAlign.End, fontSize = MaterialTheme.typography.titleMedium.fontSize),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(104.dp),
            shape = RoundedCornerShape(10.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DayChip(day: DayOfWeek, selected: Boolean, toggle: (DayOfWeek, Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { toggle(day, !selected) },
        label = { Text(DAY_LABELS[day] ?: day.name) },
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

private fun parseHhmm(text: String): LocalTime? {
    val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(text.trim()) ?: return null
    val hour = m.groupValues[1].toInt()
    val minute = m.groupValues[2].toInt()
    if (hour !in 0..23 || minute !in 0..59) return null
    return LocalTime.of(hour, minute)
}

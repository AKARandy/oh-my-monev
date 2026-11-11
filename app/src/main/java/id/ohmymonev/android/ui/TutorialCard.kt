package id.ohmymonev.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tutorial pesta-boot: daftar langkah yang mencalonkan DIRINYA dari keadaan aplikasi yang
 * benar-benar tercapai (bukan tombol "sudah dibaca"). Hilang otomatis saat semua langkah hijau.
 * Pengguna bisa menyembunyikannya lebih awal lewat tautan kecil.
 */
data class TutorialStep(val label: String, val hint: String, val done: Boolean)

@Composable
fun TutorialCard(
    steps: List<TutorialStep>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = steps.count { !it.done }
    if (remaining == 0) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sebelum dipakai", style = MaterialTheme.typography.titleMedium)
                Text(
                    "$remaining langkah lagi",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            steps.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (step.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(18.dp),
                        ) {}
                        if (step.done) {
                            Text(
                                "\u2713",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text(
                            step.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (!step.done) {
                            Text(
                                step.hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            TextButton(onClick = loggedClick("TutorialCard:96", onDismiss), modifier = Modifier.align(Alignment.End)) {
                Text("Sembunyikan", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

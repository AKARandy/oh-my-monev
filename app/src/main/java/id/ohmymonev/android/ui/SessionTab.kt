package id.ohmymonev.android.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.SessionRecord
import id.ohmymonev.android.engine.SessionProbe
import id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE
import java.time.OffsetDateTime

private val OPEN_TEXT = "Sesi aktif."
private val CLOSED_TEXT = "Sesi belum aktif."

/** Sesi: satu tombol, satu status, lalu halaman login memenuhi sisa layar. */
@Composable
fun SessionTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appStore = remember { AppStore(context) }
    var sessionActive by remember { mutableStateOf<Boolean?>(null) }
    var sessionStale by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var lastUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val saved = appStore.sessionStatus()
        if (saved != null) {
            sessionActive = saved.active
            sessionStale = SessionFreshness.isStale(saved.checkedAtISO)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sesi", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Login sekali di bawah. Sesi dipakai saat isi otomatis.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Android tidak mengizinkan aplikasi memakai sesi Chrome. Login dilakukan sekali di sini; setelah itu sesi tersimpan di aplikasi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Aplikasi eksperimen, bukan layanan resmi Kemnaker.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor(sessionActive, checking), CircleShape),
                )
                Column(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .weight(1f),
                ) {
                    Text(
                        text = when {
                            checking -> "Memeriksa…"
                            sessionActive == true && sessionStale -> "Sudah lama, periksa lagi."
                            sessionActive == true -> OPEN_TEXT
                            sessionActive == false -> CLOSED_TEXT
                            else -> "Belum dicek."
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val url = if (sessionActive == false) lastUrl else null
                    url?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = {
                        checking = true
                        SessionProbe(context, DEFAULT_SITE_PROFILE) { active, url ->
                            sessionActive = active
                            sessionStale = false
                            lastUrl = url
                            checking = false
                            appStore.saveSessionStatus(
                                SessionRecord(active, OffsetDateTime.now().toString()),
                            )
                        }.check()
                    },
                    enabled = !checking,
                ) {
                    Text("Cek sesi")
                }
            }
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl(DEFAULT_SITE_PROFILE.evaluasiUrl())
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun dotColor(active: Boolean?, checking: Boolean): Color = when {
    checking -> Color(0xFF8A8F98)
    active == true -> Color(0xFF1E8E3E)
    active == false -> Color(0xFFC5221F)
    else -> Color(0xFFC9CDD4)
}

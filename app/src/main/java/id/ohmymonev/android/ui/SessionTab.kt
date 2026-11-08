package id.ohmymonev.android.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.android.data.SessionSnapshot
import id.ohmymonev.android.engine.SessionWebView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import id.ohmymonev.android.engine.SessionProbe
import id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE

@Composable
fun SessionTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { SessionRepository.get(context) }
    val session by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var browser by remember { mutableStateOf<WebView?>(null) }
    var browserVersion by remember { mutableStateOf(0L) }
    var checking by remember { mutableStateOf(false) }
    var checkNumber by remember { mutableStateOf(0) }
    var checkJob by remember { mutableStateOf<Job?>(null) }

    fun checkSession() {
        val number = ++checkNumber
        checkJob?.cancel()
        checkJob = scope.launch {
            checking = true
            try {
                browser?.let { view ->
                    val version = if (repository.state.value.hasToken) browserVersion else repository.state.value.version
                    SessionWebView.inspect(view)?.token?.let {
                        if (repository.capture(it, version)) browserVersion = repository.state.value.version
                    }
                }
                SessionProbe(context).check()
            } finally {
                if (number == checkNumber) checking = false
            }
        }
    }

    LaunchedEffect(browser) {
        val view = browser ?: return@LaunchedEffect
        val credentials = repository.credentials()
        browserVersion = credentials?.version ?: repository.state.value.version
        SessionWebView.load(view, credentials?.token, DEFAULT_SITE_PROFILE.evaluasiUrl())
        checkSession()
        while (isActive) {
            delay(1_000)
            val version = if (repository.state.value.hasToken) browserVersion else repository.state.value.version
            val page = SessionWebView.inspect(view) ?: continue
            if (page.token != null) {
                if (repository.capture(page.token, version)) {
                    browserVersion = repository.state.value.version
                    checkSession()
                }
                repository.credentials()?.takeIf { it.token == page.token }?.let {
                    browserVersion = it.version
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sesi", style = MaterialTheme.typography.headlineMedium)
        Text("Login di halaman situs. Aplikasi menyimpan login untuk pemeriksaan dan pengisian berikutnya.",
            fontSize = 18.sp, lineHeight = 26.sp)
        SessionStatusCard(session, checking, ::checkSession)
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        isVerticalScrollBarEnabled = true
                        isScrollbarFadingEnabled = false
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.isForMainFrame && request.url.host == "account.kemnaker.go.id" && request.url.path == "/auth/logout") {
                                    scope.launch { repository.credentials()?.let { repository.reject(it) } }
                                }
                                return false
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                android.webkit.CookieManager.getInstance().flush()
                            }
                        }
                        browser = this
                    }
                },
                onRelease = { SessionWebView.release(it, repository, browserVersion) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun SessionStatusCard(session: SessionSnapshot, checking: Boolean, onCheck: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("session-status"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    checking || session.initializing -> "Memeriksa sesi…"
                    session.usable && session.issue != null -> "Status terakhir: aktif."
                    session.usable -> "Sesi aktif."
                    session.hasToken -> "Login siap diperiksa."
                    session.record?.active == true -> "Login perlu dimuat kembali."
                    session.record?.active == false -> "Login diperlukan."
                    else -> "Sesi belum diperiksa."
                },
                fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
            )
            session.issue?.let { Text(it, fontSize = 18.sp, lineHeight = 26.sp) }
            TextButton(onClick = onCheck, enabled = !checking && !session.initializing) {
                Text("Cek sesi", fontSize = 18.sp)
            }
        }
    }
}

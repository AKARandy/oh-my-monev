package id.ohmymonev.android.ui

import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.ohmymonev.android.data.ActionLog
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.android.data.SessionSnapshot
import id.ohmymonev.android.engine.LocationBridge
import id.ohmymonev.android.engine.LocationFailure
import id.ohmymonev.android.engine.SessionProbe
import id.ohmymonev.android.engine.SessionWebView
import id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun SessionTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { SessionRepository.get(context) }
    val session by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var browser by remember { mutableStateOf<WebView?>(null) }
    val locationBridges = remember { mutableMapOf<WebView, LocationBridge>() }
    var browserVersion by remember { mutableStateOf(0L) }
    var afterLogin by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checkNumber by remember { mutableStateOf(0) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var canGoBack by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle, browser) {
        val observer = LifecycleEventObserver { _, _ ->
            val nowResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            resumed = nowResumed
            if (nowResumed) browser?.onResume() else browser?.onPause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = canGoBack) {
        browser?.let { view ->
            if (view.canGoBack()) view.goBack()
            canGoBack = view.canGoBack()
        }
    }

    fun checkSession() {
        ActionLog.record(context, "session.check")
        val number = ++checkNumber
        checkJob?.cancel()
        checkJob = scope.launch {
            checking = true
            try {
                browser?.let { view ->
                    val snapshot = repository.state.value
                    val version = if (snapshot.hasToken || snapshot.signedOut) browserVersion else snapshot.version
                    SessionWebView.inspect(view)?.token?.let {
                        if (repository.capture(it, version, afterLogin = afterLogin)) browserVersion = repository.state.value.version
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
    }

    LaunchedEffect(browser, resumed) {
        val view = browser ?: return@LaunchedEffect
        if (!resumed) return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            val snapshot = repository.state.value
            val version = if (snapshot.hasToken || snapshot.signedOut) browserVersion else snapshot.version
            val page = SessionWebView.inspect(view) ?: continue
            if (page.token != null) {
                if (repository.capture(page.token, version, afterLogin = afterLogin)) {
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
        modifier = modifier.fillMaxSize().imePadding().padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sesi", style = MaterialTheme.typography.headlineMedium)
        Text("Login di halaman situs. Aplikasi menyimpan login untuk pemeriksaan dan pengisian berikutnya.",
            fontSize = 18.sp, lineHeight = 26.sp)
        SessionStatusCard(
            session = session,
            checking = checking,
            onCheck = ::checkSession,
            onReload = browser?.let { view ->
                {
                    pageError = null
                    view.reload()
                }
            },
        )
        pageError?.let { errorMessage ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    errorMessage,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (pageLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            isVerticalScrollBarEnabled = true
                            isScrollbarFadingEnabled = false
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            @Suppress("DEPRECATION")
                            settings.setGeolocationEnabled(true)
                            webChromeClient = SessionWebView.locationClient(ctx)
                            val bridge = LocationBridge(
                                ctx,
                                this,
                                "session-${java.util.UUID.randomUUID()}",
                                deviceAllowed = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
                            )
                            try {
                                bridge.install()
                                locationBridges[this] = bridge
                            } catch (failure: LocationFailure) {
                                bridge.close()
                                ActionLog.record(ctx, "session.location.bridge", "reason=${failure.reason}")
                            } catch (_: Exception) {
                                bridge.close()
                                ActionLog.record(ctx, "session.location.bridge", "reason=unavailable")
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    if (request.isForMainFrame && request.url.host == "account.kemnaker.go.id" && request.url.path == "/auth/logout") {
                                        afterLogin = false
                                        scope.launch { repository.signOut() }
                                    }
                                    return false
                                }
                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    pageLoading = true
                                    pageError = null
                                    canGoBack = view.canGoBack()
                                    val uri = url?.let(android.net.Uri::parse)
                                    if (uri?.scheme == "https" && uri.host == "monev-api.maganghub.kemnaker.go.id" &&
                                        uri.port in listOf(-1, 443) && uri.path == "/api/v1/auth/login/callback"
                                    ) {
                                        browserVersion = repository.state.value.version
                                        afterLogin = true
                                    }
                                }
                                override fun onPageFinished(view: WebView, url: String?) {
                                    pageLoading = false
                                    canGoBack = view.canGoBack()
                                    ActionLog.record(ctx, "session.page.loaded",
                                        "monev=${SessionWebView.trusted(url)}")
                                    CookieManager.getInstance().flush()
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) {
                                        pageLoading = false
                                        pageError = "Halaman gagal dimuat. Periksa koneksi lalu ketuk Muat ulang."
                                    }
                                }
                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    canGoBack = view.canGoBack()
                                }
                            }
                            browser = this
                        }
                    },
                    onRelease = { view ->
                        if (browser === view) browser = null
                        locationBridges.remove(view)?.close()
                        SessionWebView.release(view, repository, browserVersion, afterLogin)
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun SessionStatusCard(
    session: SessionSnapshot,
    checking: Boolean,
    onCheck: () -> Unit,
    onReload: (() -> Unit)? = null,
) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = loggedClick("SessionTab:163", onCheck), enabled = !checking && !session.initializing) {
                    Text("Cek sesi", fontSize = 18.sp)
                }
                if (onReload != null) {
                    TextButton(onClick = loggedClick("SessionTab:reload", onReload)) {
                        Text("Muat ulang", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

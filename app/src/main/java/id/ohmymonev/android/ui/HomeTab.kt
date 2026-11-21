package id.ohmymonev.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.ohmymonev.android.BuildConfig
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.StoredDraft
import id.ohmymonev.android.data.provenanceSummary
import id.ohmymonev.android.engine.BenchStatusSite
import id.ohmymonev.android.engine.PreparedReport
import id.ohmymonev.android.engine.PreparedReportService
import id.ohmymonev.android.engine.SiteStatus
import id.ohmymonev.android.engine.SiteStatusProbe
import id.ohmymonev.core.enginecore.TodayState
import id.ohmymonev.core.schedule.ScheduleSettings
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.validator.DeliveryGate
import id.ohmymonev.android.schedule.AlarmScheduler
import id.ohmymonev.android.schedule.AutoFillService
import id.ohmymonev.android.schedule.ManualReportRequest
import id.ohmymonev.android.schedule.TimeSettingsStore
import id.ohmymonev.core.enginecore.EngineOutcome
import id.ohmymonev.core.enginecore.RunRecord
import id.ohmymonev.core.schedule.ScheduleCalculator
import id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ID_LOCALE = Locale("id", "ID")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", ID_LOCALE)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

@Composable
internal fun HomeTab(
    onOpenSession: () -> Unit = {},
    modifier: Modifier = Modifier,
    sessionRepository: SessionRepository = SessionRepository.get(LocalContext.current),
    checkStatus: suspend (Context, LocalDate) -> SiteStatus = { context, date ->
        SiteStatusProbe(context).checkReminder(date, null)
    },
) {
    val context = LocalContext.current
    val store = remember { TimeSettingsStore(context) }
    val appStore = remember { AppStore(context) }
    val scope = rememberCoroutineScope()
    var exportingLog by remember { mutableStateOf(false) }
    val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            exportingLog = true
            try {
                id.ohmymonev.android.data.ActionLog.record(context, "logs.export")
                val snapshot = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    id.ohmymonev.android.data.ActionLog.snapshot(context)
                } ?: throw java.io.IOException("Log export timed out")
                withContext(Dispatchers.IO) {
                    (context.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("Cannot open log export")).bufferedWriter().use { it.write(snapshot) }
                }
                android.widget.Toast.makeText(context, "Log tersimpan.", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: kotlinx.coroutines.CancellationException) {
                throw kotlinx.coroutines.CancellationException()
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, "Log tidak dapat disimpan.", android.widget.Toast.LENGTH_LONG).show()
            } finally { exportingLog = false }
        }
    }
    val session by sessionRepository.state.collectAsState()
    val confirmingSession = session.initializing || (session.hasToken && session.record == null && session.issue == null)
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var statusJob by remember { mutableStateOf<Job?>(null) }
    var previewRequest by remember { mutableStateOf(0) }
    var statusRequest by remember { mutableStateOf(0) }
    var checkingDate by remember { mutableStateOf<String?>(null) }
    var checkingAfter by remember { mutableStateOf<ManualReportRequest?>(null) }
    var checkedDate by remember { mutableStateOf<String?>(null) }
    var checkedVersion by remember { mutableStateOf<Long?>(null) }
    var checkedAfter by remember { mutableStateOf<ManualReportRequest?>(null) }
    var resumeCount by remember { mutableStateOf(0) }
    var checkedOnResume by remember { mutableStateOf(0) }
    val settings by store.settings.collectAsState(initial = null)

    var todayIso by remember { mutableStateOf(LocalDate.now(ScheduleCalculator.asiaJakarta).toString()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            todayIso = LocalDate.now(ScheduleCalculator.asiaJakarta).toString()
        }
    }
    var locationGranted by remember {
        mutableStateOf(hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    var locationEnabled by remember {
        mutableStateOf(LocationManagerCompat.isLocationEnabled(context.getSystemService(android.location.LocationManager::class.java)))
    }
    var backgroundLocationGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }
    var notificationsOn by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var todayDraft by remember { mutableStateOf<StoredDraft?>(null) }
    var draftValid by remember { mutableStateOf(false) }
    var lastRecord by remember { mutableStateOf<RunRecord?>(null) }
    var running by remember { mutableStateOf(false) }
    var siteStatus by remember { mutableStateOf<SiteStatus?>(null) }
    var checkingStatus by remember { mutableStateOf(false) }
    var tonightSkipped by remember { mutableStateOf(false) }
    var armedState by remember { mutableStateOf<AlarmScheduler.ArmedState?>(null) }
    var pratinjau by remember { mutableStateOf<PreparedReport?>(null) }
    var pratinjauLoading by remember { mutableStateOf(false) }
    var editorExpanded by remember(session.version, todayIso) { mutableStateOf(false) }
    val manual by AutoFillService.manual.collectAsState()
    val manualSending = manual?.sending == true
    var confirmSend by remember { mutableStateOf<ManualReportRequest?>(null) }
    var confirmWithIp by remember { mutableStateOf(false) }
    var recheckedManual by remember { mutableStateOf<ManualReportRequest?>(null) }
    var infoExpanded by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }

    fun refreshPermissions() {
        locationGranted = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        locationEnabled = LocationManagerCompat.isLocationEnabled(context.getSystemService(android.location.LocationManager::class.java))
        backgroundLocationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        id.ohmymonev.android.data.ActionLog.record(context, "permission.result",
            "location=$locationGranted backgroundLocation=$backgroundLocationGranted notifications=$notificationsOn")
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshPermissions() }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }
    var notificationRequested by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationRequested = true
        refreshPermissions()
    }

    fun requestLocation() {
        id.ohmymonev.android.data.ActionLog.record(context, "permission.location.request")
        locationLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ))
    }

    fun requestBackgroundLocation() {
        id.ohmymonev.android.data.ActionLog.record(context, "permission.background_location.request")
        if (!locationGranted) {
            requestLocation()
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                },
            )
        }
    }

    fun requestNotifications() {
        id.ohmymonev.android.data.ActionLog.record(context, "permission.notifications.request")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationRequested &&
            !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }

    fun refreshPratinjau(force: Boolean = false, allowModelRun: Boolean = true) {
        val request = ++previewRequest
        previewJob?.cancel()
        pratinjauLoading = true
        previewJob = scope.launch {
            try {
                val prepared = PreparedReportService(context).prepare(force, allowModelRun)
                if (request == previewRequest) {
                    pratinjau = prepared
                }
            } finally { if (request == previewRequest) pratinjauLoading = false }
        }
    }

    suspend fun refresh() {
        val draft = withContext(Dispatchers.IO) { appStore.todayDraft(todayIso) }
        todayDraft = draft
        // "Tertulis" tidak boleh berarti "siap": draft tersimpan tetap harus lolos aturan situs.
        draftValid = draft != null && DeliveryGate().validate(
            ReportDraft(draft.aktivitas, draft.pelajaran, draft.kendala),
        ).passed
        lastRecord = withContext(Dispatchers.IO) { appStore.records().lastOrNull() }
        tonightSkipped = withContext(Dispatchers.IO) { appStore.isSkipped(todayIso) }
    }

    /** Baca keadaan sesungguhnya dari situs: status hari ini (isi/kosong/libur) + riwayat. */
    fun refreshSiteStatus() {
        val date = LocalDate.now(ScheduleCalculator.asiaJakarta)
        val dateIso = date.toString()
        val completed = AutoFillService.manual.value?.takeIf { !it.sending && it.request.draft.date == dateIso }
        val attempt = completed?.request
        if (checkingStatus && checkingDate == dateIso && checkingAfter === attempt) return
        checkingDate = dateIso
        checkingAfter = attempt
        id.ohmymonev.android.data.ActionLog.record(context, "status.check.start")
        val request = ++statusRequest
        statusJob?.cancel()
        checkingStatus = true
        statusJob = scope.launch {
            try {
                val result = kotlinx.coroutines.withTimeoutOrNull(45_000) {
                    val before = sessionRepository.state.value.version
                    val first = checkStatus(context, date)
                    val current = sessionRepository.state.value
                    if (current.hasToken && current.version != before && first.credentialVersion != current.version) {
                        checkStatus(context, date)
                    } else first
                }
                    ?: SiteStatus(null, TodayState.UNKNOWN, null, emptyList(), "Pemeriksaan terlalu lama. Coba lagi.")
                if (request != statusRequest || date != LocalDate.now(ScheduleCalculator.asiaJakarta)) return@launch
                siteStatus = result
                checkedDate = dateIso
                checkedVersion = result.credentialVersion ?: sessionRepository.state.value.version
                checkedAfter = attempt
                checkedOnResume = resumeCount
                if (completed?.outcome == EngineOutcome.LOCATION_CHECK_FAILS && attempt != null) {
                    val current = sessionRepository.state.value
                    if (result.sessionActive != true || result.account != attempt.account ||
                        result.credentialVersion != current.version || result.today == TodayState.UNKNOWN
                    ) {
                        AutoFillService.requireManualRecheck(attempt, true)
                    } else if (recheckedManual === attempt) {
                        AutoFillService.requireManualRecheck(attempt, false)
                    }
                }
                id.ohmymonev.android.data.ActionLog.record(context, "status.check.result",
                    "session=${result.sessionActive} state=${result.today}")
                val current = withContext(Dispatchers.IO) { store.settingsOnce() }
                armedState = withContext(Dispatchers.IO) { AlarmScheduler.ensureArmed(context, current) }
            }
            finally { if (request == statusRequest) checkingStatus = false }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
                todayIso = LocalDate.now(ScheduleCalculator.asiaJakarta).toString()
                resumeCount++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose { previewJob?.cancel(); statusJob?.cancel() }
    }
    val completedAttempt = manual?.takeIf { !it.sending && it.request.draft.date == todayIso }?.request
    LaunchedEffect(session.version, session.initializing, todayIso, completedAttempt, resumeCount) {
        if (!session.initializing) {
            if (checkedDate != todayIso || checkedVersion != session.version ||
                checkedAfter !== completedAttempt || checkedOnResume != resumeCount
            ) refreshSiteStatus()
            refresh()
        }
    }
    LaunchedEffect(settings) {
        settings?.let { s -> armedState = withContext(Dispatchers.IO) { AlarmScheduler.ensureArmed(context, s) } }
    }
    LaunchedEffect(session.version, session.usable, notificationsOn, todayIso) {
        previewJob?.cancel()
        pratinjau = null
        pratinjauLoading = false
        if (session.usable && notificationsOn) refreshPratinjau(allowModelRun = false)
    }
    LaunchedEffect(running) {
        if (running) {
            delay(15_000)
            refresh()
            if (session.usable && notificationsOn) refreshPratinjau(allowModelRun = false)
            val s = withContext(Dispatchers.IO) { store.settingsOnce() }
            armedState = withContext(Dispatchers.IO) { AlarmScheduler.ensureArmed(context, s) }
            running = false
        }
    }

    fun refreshArmed() {
        armedState = null
        scope.launch {
            // Perbaiki dulu, baru buktikan: "Periksa" tidak pernah membaca keadaan lama.
            val s = withContext(Dispatchers.IO) { store.settingsOnce() }
            armedState = withContext(Dispatchers.IO) { AlarmScheduler.ensureArmed(context, s) }
        }
    }

    val readiness = id.ohmymonev.android.schedule.FeatureReadiness(session.usable, locationGranted, notificationsOn)
    val ready = readiness.ready
    val status = siteStatus?.takeIf {
        checkedDate == todayIso && checkedVersion == session.version
    }
    val account = status?.account
    val visibleDraft = todayDraft?.takeIf { it.date == todayIso &&
        (it.participantId == null || it.participantId == account) }
    val manualResult = manual?.takeIf {
        !it.sending && it.request.draft.date == todayIso &&
            (it.request.account == account ||
                (account == null && it.outcome != EngineOutcome.SUBMITTED && it.request.sessionVersion == session.version))
    }
    val locationRetryReady = manualResult?.outcome == EngineOutcome.LOCATION_CHECK_FAILS &&
        !manualResult.recheckRequired && checkedAfter === manualResult.request
    val attemptedByOtherAccount = completedAttempt != null &&
        completedAttempt.draft == visibleDraft && completedAttempt.account != account
    val maySend = ready && status?.sessionActive == true && status.today == TodayState.UNFILLED &&
        account != null && status.credentialVersion == session.version && !checkingStatus && !attemptedByOtherAccount &&
        !manualSending && (manualResult == null || locationRetryReady ||
            (manualResult.request === recheckedManual && checkedAfter === manualResult.request)) &&
        manualResult?.outcome != EngineOutcome.SUBMITTED

    val scrollState = rememberScrollState()
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .scrollIndicator(scrollState)
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = ZonedDateTime.now(ScheduleCalculator.asiaJakarta).format(DATE_FORMAT).uppercase(ID_LOCALE),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReportStatus(
                status = when {
                    checkingStatus || confirmingSession -> ReportStatusLabel.CHECKING
                    status?.today == TodayState.FILLED -> ReportStatusLabel.SUBMITTED
                    status?.today == TodayState.UNFILLED && status.sessionActive == true -> ReportStatusLabel.MISSING
                    status?.today == TodayState.LIBUR || status?.today == TodayState.LEAVE -> ReportStatusLabel.NOT_REQUIRED
                    status?.sessionActive == false || session.signedOut -> ReportStatusLabel.LOGIN_REQUIRED
                    else -> ReportStatusLabel.UNAVAILABLE
                },
                canRecheck = !checkingStatus && !manualSending && !session.initializing,
                onRecheck = {
                    recheckedManual = manualResult?.request
                    refreshSiteStatus()
                },
            )
            if (!ready && (session.usable || status?.sessionActive == false || session.signedOut)) {
                Text(readiness.missing(), style = MaterialTheme.typography.bodyMedium)
            } else if (!notificationsOn) {
                Text("Aktifkan notifikasi", style = MaterialTheme.typography.bodyMedium)
            }
            if (status?.sessionActive == false || session.signedOut) {
                TextButton(onClick = loggedClick("HomeTab:346", onOpenSession)) { Text("Login ke Monev") }
            }
        }

        if (manualSending) Text("Mengirim…")
        manualResult?.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (account != null && (visibleDraft != null || status?.today == TodayState.UNFILLED)) {
            DraftCard(
                expanded = editorExpanded && !manualSending,
                onExpandedChange = { editorExpanded = it },
                onSaved = { scope.launch { refresh(); refreshPratinjau(true, allowModelRun = false) } },
                savedDraft = visibleDraft,
                account = account,
                editable = ready && !manualSending && status?.today == TodayState.UNFILLED,
            )
            if (visibleDraft != null && draftValid && status?.today == TodayState.UNFILLED) {
                Button(
                    onClick = loggedClick("HomeTab:363") {
                        confirmWithIp = !locationGranted || !locationEnabled
                        confirmSend = ManualReportRequest(visibleDraft, account, session.version)
                    },
                    enabled = maySend && !editorExpanded,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Kirim ke Monev") }
            }
        }
        confirmSend?.let { reviewed ->
            ConfirmManualReport(
                locationFallback = confirmWithIp,
                locationEnabled = locationEnabled,
                enabled = maySend && visibleDraft == reviewed.draft &&
                    account == reviewed.account && session.version == reviewed.sessionVersion,
                onSend = {
                    confirmSend = null
                    recheckedManual = null
                    id.ohmymonev.android.data.ActionLog.record(context, "manual.confirm")
                    AutoFillService.startManual(context, reviewed)
                },
                onEnableLocation = {
                    confirmSend = null
                    val settingsIntent = if (!locationEnabled) {
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    }
                    context.startActivity(settingsIntent)
                },
                onDismiss = { confirmSend = null },
            )
        }

        // Satu kartu persiapan: tutorial dan status digabung; langkah mencentang dari keadaan nyata.
        if (!notificationsOn && !confirmingSession) {
            ReadinessCard(
                notificationsOn = notificationsOn,
                onGrantNotifications = { requestNotifications() },
            )
        }

        PratinjauCard(
            prepared = pratinjau,
            loading = pratinjauLoading,
            onRefresh = { refreshPratinjau(true) },
            refreshEnabled = ready,
            holiday = status?.today == TodayState.LIBUR,
            loadingText = if (settings?.escalationSource == ScheduleSettings.ContentSource.AUTOMATIC) {
                "Membuat draf otomatis…"
            } else {
                "Mencari laporan Hadir…"
            },
        )

        // Ringkasan malam ini: bukti alarm + apa yang akan terjadi + jalan keluar bila ingin mandiri.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ScheduleStatus(armedState, onCheck = { refreshArmed() })

                var confirmSkip by remember { mutableStateOf(false) }
                if (settings?.masterEnabled == true && settings?.escalationEnabled == true && tonightSkipped) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "Dijeda untuk malam ini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(enabled = ready, onClick = loggedClick("HomeTab:428") {
                        scope.launch {
                            withContext(Dispatchers.IO) { appStore.clearTonightSkip() }
                            tonightSkipped = false
                        }
                    }) { Text("Nyalakan lagi") }
                } else if (settings?.masterEnabled == true && settings?.escalationEnabled == true) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(enabled = ready, onClick = loggedClick("HomeTab:436") { confirmSkip = true }) {
                        Text("Jeda malam ini", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (ready && confirmSkip) {
                    AlertDialog(
                        onDismissRequest = { confirmSkip = false },
                        title = { Text("Jeda Pengisian darurat malam ini?") },
                        text = {
                            Text(
                                "Pengingat tetap mengikuti jadwal. Kirim laporan sendiri sebelum 23.59 WIB.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = loggedClick("HomeTab:450") {
                                confirmSkip = false
                                scope.launch {
                                    withContext(Dispatchers.IO) { appStore.setTonightSkip(todayIso) }
                                    tonightSkipped = true
                                    refreshArmed()
                                }
                            }) { Text("Ya, matikan") }
                        },
                        dismissButton = {
                            TextButton(onClick = loggedClick("HomeTab:460") { confirmSkip = false }) { Text("Batal") }
                        },
                    )
                }
            }
        }

        // Tindakan pada tulisan hari ini: hapus hanya bila ada, sebagai tautan kecil.
        if (visibleDraft != null && !manualSending && ready) {
            var confirmDelete by remember { mutableStateOf(false) }
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Hapus tulisan hari ini?") },
                    text = { Text("Aplikasi akan memakai sumber yang dipilih di Pengisian darurat.") },
                    confirmButton = {
                        TextButton(onClick = loggedClick("HomeTab:476") {
                            confirmDelete = false
                            scope.launch {
                                withContext(Dispatchers.IO) { appStore.clearTodayDraft() }
                                refresh()
                                refreshPratinjau(true)
                            }
                        }) { Text("Hapus") }
                    },
                    dismissButton = {
                        TextButton(onClick = loggedClick("HomeTab:486") { confirmDelete = false }) { Text("Batal") }
                    },
                )
            }
            TextButton(
                onClick = loggedClick("HomeTab:491") { confirmDelete = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Hapus tulisan hari ini", color = MaterialTheme.colorScheme.error)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().testTag("last-run"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LastRunRow(lastRecord)
            lastRecord?.provenanceSummary()?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = loggedClick("HomeTab:499") { infoExpanded = !infoExpanded }) { Text("Tentang aplikasi", style = MaterialTheme.typography.bodyMedium) }
        if (infoExpanded) DisclaimerCard()
        if (BuildConfig.DEBUG) {
            TextButton(onClick = loggedClick("HomeTab:502") { diagnosticsExpanded = !diagnosticsExpanded }) {
                Text(if (diagnosticsExpanded) "Tutup alat pengujian" else "Alat pengujian")
            }
            if (diagnosticsExpanded) {
                Text("Uji mesin memakai halaman tiruan. Tes alarm memerlukan Pengisian darurat aktif.", style = MaterialTheme.typography.bodySmall)
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("location-diagnostic"),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Lokasi saat aplikasi tertutup", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Pilih Selalu izinkan untuk pengisian terjadwal.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = loggedClick("HomeTab:513") { requestBackgroundLocation() },
                        ) {
                            Text(
                                when {
                                    !locationGranted -> "Izinkan lokasi akurat"
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !backgroundLocationGranted -> "Izinkan lokasi selalu"
                                    else -> "Buka pengaturan lokasi"
                                },
                            )
                        }
                        Text(
                            "Tidak mengirim laporan. Kunci layar setelah menjadwalkan.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            enabled = locationGranted && backgroundLocationGranted,
                            onClick = loggedClick("HomeTab:520") {
                                AlarmScheduler.scheduleLocationProbe(context)
                            },
                        ) { Text("Uji lokasi 90 detik") }
                    }
                }
                OutlinedButton(
                    onClick = loggedClick("HomeTab:508") { AutoFillService.startBench(context); running = true },
                    enabled = ready && !running,
                ) { Text("Uji mesin") }
                TextButton(enabled = ready && settings?.escalationEnabled == true, onClick = loggedClick("HomeTab:511") { scope.launch { AlarmScheduler.quickFireForTest(context, 90) } }) {
                    Text("Tes alarm 90 detik")
                }
            }
        }

        if (status?.sessionActive == false || session.signedOut) {
            Text(
                "Sesi belum aktif. Buka tab Sesi, login, lalu periksa lagi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (BuildConfig.DEBUG) {
            TextButton(
                enabled = !exportingLog,
                onClick = loggedClick("HomeTab:549") {
                    exportLog.launch("oh-my-monev-debug-${LocalDate.now()}.jsonl")
                },
                modifier = Modifier.testTag("download-logs"),
            ) { Text(if (exportingLog) "Menyimpan log…" else "Download logs") }
        }
        }


    }
}

@Composable
private fun DisclaimerCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Aplikasi eksperimen", style = MaterialTheme.typography.titleSmall)
            Text(
                "Bukan layanan resmi Kemnaker atau MagangHub. Aplikasi ini hanya mengotomatiskan " +
                    "tindakan di akunmu sendiri. Kamu tetap bertanggung jawab atas isi laporan yang " +
                    "terkirim. Uji pengisian sebelum mengizinkan pengiriman otomatis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )


        }
    }
}

internal enum class ReportStatusLabel(val text: String, val color: Color) {
    CHECKING("Memeriksa status…", Color(0xFF0A3D91)),
    SUBMITTED("Sudah terkirim", Color(0xFF17613A)),
    MISSING("Belum terkirim", Color(0xFFAA182E)),
    NOT_REQUIRED("Tidak perlu laporan", Color(0xFF5F6570)),
    LOGIN_REQUIRED("Login untuk melanjutkan.", Color(0xFF634197)),
    UNAVAILABLE("Status belum dapat diperiksa.", Color(0xFF815300)),
}

@Composable
internal fun ReportStatus(status: ReportStatusLabel, canRecheck: Boolean, onRecheck: () -> Unit) {
    Text("Status laporan hari ini", style = MaterialTheme.typography.headlineMedium)
    Text(status.text, color = status.color, style = MaterialTheme.typography.bodyLarge)
    TextButton(onClick = loggedClick("HomeTab:573", onRecheck), enabled = canRecheck) { Text("Cek ulang") }
}

@Composable
internal fun ConfirmManualReport(
    locationFallback: Boolean,
    locationEnabled: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onEnableLocation: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(when {
                !locationFallback -> "Kirim laporan hari ini ke Monev?"
                !locationEnabled -> "Lokasi mati"
                else -> "Lokasi tidak tersedia"
            })
        },
        text = {
            Text(if (locationFallback) {
                "Aktifkan untuk hasil lebih akurat\n\nTetap kirim dengan perkiraan IP?"
            } else {
                "Pastikan draf yang tersimpan sudah benar"
            })
        },
        confirmButton = {
            if (locationFallback) {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = loggedClick("HomeTab:584", onEnableLocation)) { Text("Aktifkan lokasi") }
                    TextButton(enabled = enabled, onClick = loggedClick("HomeTab:583", onSend)) { Text("Kirim dengan IP") }
                    TextButton(onClick = loggedClick("HomeTab:585", onDismiss)) { Text("Batal") }
                }
            } else {
                TextButton(enabled = enabled, onClick = loggedClick("HomeTab:583", onSend)) { Text("Kirim ke Monev") }
            }
        },
        dismissButton = {
            if (!locationFallback) {
                TextButton(onClick = loggedClick("HomeTab:585", onDismiss)) { Text("Batal") }
            }
        },
    )
}

@Composable
private fun SiteStatusCard(
    status: SiteStatus?,
    checking: Boolean,
    onCheck: () -> Unit,
    onCheckBench: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kondisi di situs", style = MaterialTheme.typography.titleMedium)
            when {
                status == null -> Text(
                    "Belum dibaca.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status.sessionActive == null -> Text(
                    status.error ?: "Situs belum dapat diperiksa. Status login terakhir tetap disimpan.",
                    fontSize = 18.sp,
                )
                status.sessionActive == false -> Text(
                    "Sesi habis. Login di tab Sesi, lalu cek lagi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Hari ini", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = when (status.today) {
                                TodayState.FILLED -> "Sudah diisi"
                                TodayState.UNFILLED -> "Belum diisi"
                                TodayState.LIBUR -> "Hari libur, tidak perlu isi"
                                TodayState.LEAVE -> "Tidak hadir, tidak perlu laporan"
                                TodayState.UNKNOWN -> "Tidak jelas"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = when (status.today) {
                                TodayState.UNFILLED -> MaterialTheme.colorScheme.error
                                TodayState.LIBUR, TodayState.LEAVE -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    status.deadline?.let {
                        Text(
                            "Batas $it WIB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (status.days.isNotEmpty()) {
                        Text(
                            "Hari sebelumnya",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (day in status.days.takeLast(3).asReversed()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(day.label, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    day.state,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = loggedClick("HomeTab:672", onCheck), enabled = !checking) {
                    Text(if (checking) "Membaca…" else "Cek situs")
                }
                if (BuildConfig.DEBUG) {
                    TextButton(onClick = loggedClick("HomeTab:676", onCheckBench), enabled = !checking) {
                        Text("Contoh")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadinessCard(
    notificationsOn: Boolean,
    onGrantNotifications: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Persiapan", style = MaterialTheme.typography.titleMedium)
            if (!notificationsOn) {
                ReadyRow("Notifikasi", false, "Aktifkan pengingat", "Izinkan", onGrantNotifications)
            }
        }
    }
}

@Composable
private fun ReadyRow(
    label: String,
    ok: Boolean?,
    detail: String,
    action: String?,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = when (ok) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape,
                ),
        )
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            TextButton(onClick = loggedClick("HomeTab:736", onAction)) { Text(action) }
        }
    }
}

@Composable
private fun LastRunRow(record: RunRecord?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Hasil terakhir",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = record?.let {
                plainOutcome(it) + " · " + attemptTime(it.attemptedAtISO)
            } ?: "Belum pernah jalan.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun plainOutcome(record: RunRecord): String = when (record.outcome) {
    EngineOutcome.SUBMITTED -> "Sudah dikirim"
    EngineOutcome.FILL_OK -> "Sudah diisi, belum dikirim"
    EngineOutcome.LOGIN_REQUIRED -> "Perlu login"
    EngineOutcome.DAY_NOT_FILLABLE -> "Hari ini tidak bisa diisi"
    EngineOutcome.LOCATION_CHECK_FAILS -> "Lokasi tidak terbaca"
    EngineOutcome.READBACK_MISMATCH -> "Isian tidak cocok"
    EngineOutcome.SUBMIT_FAILED -> "Gagal kirim"
    EngineOutcome.RETRYABLE_ERROR -> "Koneksi gagal"
    EngineOutcome.LOADING_TIMEOUT -> "Situs lama terbuka"
    EngineOutcome.UNKNOWN -> "Hasil tidak jelas"
}

private fun attemptTime(iso: String): String = try {
    OffsetDateTime.parse(iso).atZoneSameInstant(ScheduleCalculator.asiaJakarta).format(TIME_FORMAT) + " WIB"
} catch (_: Exception) {
    ""
}

/** Tampilkan jam kebakaran alarm berikutnya dalam zona WIB, teks pendek. */
private fun formatFire(nextFireISO: String?): String {
    if (nextFireISO == null) return "?"
    return try {
        java.time.OffsetDateTime.parse(nextFireISO)
            .atZoneSameInstant(java.time.ZoneOffset.ofHours(7))
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", ID_LOCALE))
    } catch (_: Exception) {
        nextFireISO
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED


@Composable
internal fun ScheduleStatus(state: AlarmScheduler.ArmedState?, onCheck: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("schedule-status"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Jadwal fitur berikutnya", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            when {
                state == null -> "Memuat jadwal…"
                state.nextFireISO == null -> state.source
                else -> "${state.source} · ${formatFire(state.nextFireISO)} WIB"
            },
            fontSize = 20.sp,
            lineHeight = 28.sp,
        )
        if (state?.nextFireISO != null) {
            Text(
                if (state.armed) "Alarm dijadwalkan" else "Alarm belum terkonfirmasi",
                fontSize = 18.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state?.lastError?.let { Text(it, fontSize = 18.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = loggedClick("HomeTab:816", onCheck)) { Text("Periksa jadwal", fontSize = 18.sp) }
    }
}

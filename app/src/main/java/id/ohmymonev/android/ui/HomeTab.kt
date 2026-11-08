package id.ohmymonev.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import id.ohmymonev.android.BuildConfig
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.StoredDraft
import id.ohmymonev.android.engine.BenchStatusSite
import id.ohmymonev.android.engine.PreparedReport
import id.ohmymonev.android.engine.PreparedReportService
import id.ohmymonev.android.engine.SiteStatus
import id.ohmymonev.android.engine.SiteStatusProbe
import id.ohmymonev.core.enginecore.TodayState
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.validator.DeliveryGate
import id.ohmymonev.android.schedule.AlarmScheduler
import id.ohmymonev.android.schedule.AutoFillService
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
fun HomeTab(
    onOpenSession: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { TimeSettingsStore(context) }
    val appStore = remember { AppStore(context) }
    val scope = rememberCoroutineScope()
    val sessionRepository = remember { SessionRepository.get(context) }
    val session by sessionRepository.state.collectAsState()
    val sessionActive = session.record?.active
    val sessionChecked = session.record != null
    val confirmingSession = session.initializing || (session.hasToken && session.record == null && session.issue == null)
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var statusJob by remember { mutableStateOf<Job?>(null) }
    var previewRequest by remember { mutableStateOf(0) }
    var statusRequest by remember { mutableStateOf(0) }
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
    var editorExpanded by remember { mutableStateOf(false) }
    var infoExpanded by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { locationGranted = it }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsOn = it }

    fun refreshPratinjau(force: Boolean = false) {
        val request = ++previewRequest
        previewJob?.cancel()
        pratinjauLoading = true
        previewJob = scope.launch {
            try {
                val prepared = PreparedReportService(context).prepare(force)
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
        val request = ++statusRequest
        statusJob?.cancel()
        checkingStatus = true
        statusJob = scope.launch {
            try {
                siteStatus = SiteStatusProbe(context).check()
                val current = withContext(Dispatchers.IO) { store.settingsOnce() }
                armedState = withContext(Dispatchers.IO) { AlarmScheduler.ensureArmed(context, current) }
            }
            finally { if (request == statusRequest) checkingStatus = false }
        }
    }

    DisposableEffect(Unit) {
        onDispose { previewJob?.cancel(); statusJob?.cancel() }
    }
    LaunchedEffect(session.version, session.initializing, todayIso) {
        if (!session.initializing) {
            refresh()
            refreshSiteStatus()
        }
    }
    LaunchedEffect(settings) {
        settings?.let { s -> armedState = withContext(Dispatchers.IO) { AlarmScheduler.ensureArmed(context, s) } }
    }
    LaunchedEffect(session.version, session.usable, todayIso) {
        previewJob?.cancel()
        pratinjau = null
        pratinjauLoading = false
        if (session.usable) refreshPratinjau()
    }
    LaunchedEffect(running) {
        if (running) {
            delay(15_000)
            refresh()
            if (session.usable) refreshPratinjau()
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

    fun sessionReadyForAutomation(): Boolean = session.usable

    // Setup yang masih kurang: sesi, lokasi, notifikasi. Dipakai tombol utama dan kartu persiapan.
    val needSetup = !sessionReadyForAutomation() || !locationGranted || !notificationsOn
    val noReportRequired = siteStatus?.sessionActive == true &&
        (siteStatus?.today == TodayState.FILLED || siteStatus?.today == TodayState.LIBUR ||
            siteStatus?.today == TodayState.LEAVE)

    val needsDraftCorrection = pratinjau != null && !pratinjauLoading && !pratinjau!!.sourceUnavailable &&
        pratinjau!!.outcome() is id.ohmymonev.core.generator.GenerationOutcome.Failed
    val previewReady = pratinjau?.outcome() is id.ohmymonev.core.generator.GenerationOutcome.Generated
    val scrollState = rememberScrollState()
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .scrollIndicator(scrollState)
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = ZonedDateTime.now(ScheduleCalculator.asiaJakarta).format(DATE_FORMAT).uppercase(ID_LOCALE),
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Laporan hari ini", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = when {
                    confirmingSession -> "Memeriksa login tersimpan…"
                    settings == null -> "Memuat jadwal…"
                    siteStatus?.sessionActive == true && siteStatus?.today == TodayState.FILLED ->
                        "Sudah tercatat"
                    siteStatus?.sessionActive == true && siteStatus?.today == TodayState.LIBUR ->
                        "Hari libur"
                    siteStatus?.sessionActive == true && siteStatus?.today == TodayState.LEAVE ->
                        "Tidak hadir"
                    !sessionReadyForAutomation() && session.hasToken -> "Login tersimpan. Pemeriksaan belum berhasil."
                    !sessionReadyForAutomation() -> "Login untuk melanjutkan."
                    todayDraft != null && !draftValid -> "Perbaiki laporan sebelum mengisi formulir."
                    draftValid -> "Laporan siap diisi ke formulir."
                    else -> "Siapkan laporan hari ini, lalu isi formulir."
                },
                fontSize = 20.sp,
                lineHeight = 28.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val interaction = remember { MutableInteractionSource() }
        // Tombol yang tidak bisa berhasil memang harus tampak mati, bukan sekali tap lalu gagal.
        Button(
            onClick = {
                when {
                    noReportRequired -> onOpenSession()
                    !sessionReadyForAutomation() -> onOpenSession()
                    (todayDraft != null && !draftValid) || needsDraftCorrection -> editorExpanded = true
                    !previewReady -> refreshPratinjau(true)
                    else -> { AutoFillService.start(context); running = true }
                }
            },
            enabled = !running && !pratinjauLoading && !confirmingSession && (noReportRequired || !sessionReadyForAutomation() || !needSetup),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .pressScale(interaction),
            shape = RoundedCornerShape(16.dp),
            interactionSource = interaction,
        ) {
            Text(
                when {
                    running -> "Mengisi…"
                    confirmingSession -> "Memeriksa login…"
                    noReportRequired -> "Lihat di situs Monev"
                    !sessionReadyForAutomation() && session.hasToken -> "Cek sesi tersimpan"
                    !sessionReadyForAutomation() -> "Login ke Monev"
                    needSetup -> "Lengkapi izin di bawah"
                    (todayDraft != null && !draftValid) || needsDraftCorrection -> "Tulis laporan hari ini"
                    !previewReady -> "Perbarui pratinjau"
                    else -> "Isi dan kirim sekarang"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // Satu kartu persiapan: tutorial dan status digabung; langkah mencentang dari keadaan nyata.
        if ((!locationGranted || !notificationsOn) && !confirmingSession) {
            ReadinessCard(
                locationGranted = locationGranted,
                notificationsOn = notificationsOn,
                // Satu fungsi satu tombol: memeriksa sesi hanya ada di tab Sesi.
                onGrantLocation = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                onGrantNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }

        PratinjauCard(
            prepared = pratinjau,
            loading = pratinjauLoading,
            onRefresh = { refreshPratinjau(true) },
            holiday = siteStatus?.today == TodayState.LIBUR,
        )

        DraftCard(
            expanded = editorExpanded,
            onExpandedChange = { editorExpanded = it },
            onSaved = { scope.launch { refresh(); refreshPratinjau(true) } },
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
                if (settings?.masterEnabled == true && tonightSkipped) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "Dijeda untuk malam ini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { appStore.clearTonightSkip() }
                            tonightSkipped = false
                        }
                    }) { Text("Nyalakan lagi") }
                } else if (settings?.masterEnabled == true) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(onClick = { confirmSkip = true }) {
                        Text("Jeda malam ini", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (confirmSkip) {
                    AlertDialog(
                        onDismissRequest = { confirmSkip = false },
                        title = { Text("Matikan otomatis untuk malam ini?") },
                        text = {
                            Text(
                                "Pengisian darurat malam ini tidak akan berjalan. " +
                                    "Kamu mengisi sendiri sebelum 23.59 WIB. Hanya berlaku malam ini.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmSkip = false
                                scope.launch {
                                    withContext(Dispatchers.IO) { appStore.setTonightSkip(todayIso) }
                                    tonightSkipped = true
                                    refreshArmed()
                                }
                            }) { Text("Ya, matikan") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmSkip = false }) { Text("Batal") }
                        },
                    )
                }
            }
        }

        // Tindakan pada tulisan hari ini: hapus hanya bila ada, sebagai tautan kecil.
        if (todayDraft != null) {
            var confirmDelete by remember { mutableStateOf(false) }
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Hapus tulisan hari ini?") },
                    text = { Text("Aplikasi akan memakai laporan Hadir terbaru jika tersedia.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = false
                            scope.launch {
                                withContext(Dispatchers.IO) { appStore.clearTodayDraft() }
                                refresh()
                                refreshPratinjau(true)
                            }
                        }) { Text("Hapus") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) { Text("Batal") }
                    },
                )
            }
            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Hapus tulisan hari ini", color = MaterialTheme.colorScheme.error)
            }
        }

        LastRunRow(lastRecord)
        TextButton(onClick = { infoExpanded = !infoExpanded }) { Text("Tentang aplikasi", fontSize = 18.sp) }
        if (infoExpanded) DisclaimerCard()
        TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) {
            Text(if (diagnosticsExpanded) "Tutup alat pengujian" else "Alat pengujian")
        }
        if (diagnosticsExpanded) {
            Text("Uji mesin memakai halaman tiruan. Tes alarm memakai pengaturan Pengisian dan sesi aktif.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { AutoFillService.startBench(context); running = true },
                enabled = !running,
            ) { Text("Uji mesin") }
            TextButton(onClick = { AlarmScheduler.quickFireForTest(context, 90) }) {
                Text("Tes alarm 90 detik")
            }
        }

        if (sessionChecked && sessionActive == false) {
            Text(
                "Sesi belum aktif. Buka tab Sesi, login, lalu periksa lagi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
                Button(onClick = onCheck, enabled = !checking) {
                    Text(if (checking) "Membaca…" else "Cek situs")
                }
                if (BuildConfig.DEBUG) {
                    TextButton(onClick = onCheckBench, enabled = !checking) {
                        Text("Contoh")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadinessCard(
    locationGranted: Boolean,
    notificationsOn: Boolean,
    onGrantLocation: () -> Unit,
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
            if (!locationGranted) {
                ReadyRow("Izin lokasi", false, "Dibutuhkan situs", "Izinkan", onGrantLocation)
            }
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
            TextButton(onClick = onAction) { Text(action) }
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
    OffsetDateTime.parse(iso).format(TIME_FORMAT)
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
        TextButton(onClick = onCheck) { Text("Periksa jadwal", fontSize = 18.sp) }
    }
}

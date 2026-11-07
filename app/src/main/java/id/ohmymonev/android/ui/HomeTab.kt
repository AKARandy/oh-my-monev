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
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import id.ohmymonev.android.BuildConfig
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.StoredDraft
import id.ohmymonev.android.engine.BenchStatusSite
import id.ohmymonev.android.engine.PastReportReader
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
import id.ohmymonev.core.generator.GenerationOutcome
import id.ohmymonev.core.generator.GeneratorPipeline
import id.ohmymonev.core.schedule.ScheduleCalculator
import id.ohmymonev.core.schedule.ScheduleSettings
import id.ohmymonev.core.siteprofile.DEFAULT_SITE_PROFILE
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    val settings by store.settings.collectAsState(initial = null)

    val todayIso = LocalDate.now(ScheduleCalculator.asiaJakarta).toString()
    var locationGranted by remember {
        mutableStateOf(hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    var notificationsOn by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var sessionActive by remember { mutableStateOf<Boolean?>(null) }
    var sessionChecked by remember { mutableStateOf(false) }
    var todayDraft by remember { mutableStateOf<StoredDraft?>(null) }
    var draftValid by remember { mutableStateOf(false) }
    var sessionStale by remember { mutableStateOf(false) }
    var lastRecord by remember { mutableStateOf<RunRecord?>(null) }
    var running by remember { mutableStateOf(false) }
    var siteStatus by remember { mutableStateOf<SiteStatus?>(null) }
    var checkingStatus by remember { mutableStateOf(false) }
    var tonightSkipped by remember { mutableStateOf(false) }
    var armedState by remember { mutableStateOf<AlarmScheduler.ArmedState?>(null) }
    var pratinjau by remember { mutableStateOf<GenerationOutcome?>(null) }
    var pratinjauLoading by remember { mutableStateOf(false) }
    var pratinjauSource by remember { mutableStateOf<String?>(null) }
    var editorExpanded by remember { mutableStateOf(false) }
    var tutorialHidden by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { locationGranted = it }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsOn = it }

    /**
     * Pratinjau dari LAPORAN ASLI situs: baca laporan kemarin lewat API dalam WebView.
     * Kalau situs terbaca, teks di bawah adalah teks kemarin yang sebenarnya.
     * Kalau tidak (sesi habis, belum ada laporan), pratinjau menolak dengan alasan nyata.
     */
    fun refreshPratinjau() {
        pratinjauLoading = true
        PastReportReader(context) { result ->
            scope.launch {
                if (result.sessionActive && result.draft != null) {
                    sessionActive = true
                    sessionChecked = true
                    sessionStale = false
                    // Laporan asli kemarin -> jalankan generator lanjutan sesungguhnya di atasnya.
                    pratinjau = withContext(Dispatchers.IO) {
                        val seed = appStore.loadSeed()
                        val lastUsed: Map<String, java.time.LocalDate> = appStore.poolState().lastUsed.entries
                            .mapNotNull { (text, iso) ->
                                runCatching { text to java.time.LocalDate.parse(iso) }.getOrNull()
                            }.toMap()
                        val pools = id.ohmymonev.core.generator.PoolBundle(seed.pelajaran, seed.kendala, lastUsed)
                        val previous = id.ohmymonev.core.generator.DatedReport(result.date, result.draft)
                        val history = id.ohmymonev.core.generator.ReportHistory { previous }
                        GeneratorPipeline(history = history, poolBundle = pools)
                            .generate(java.time.LocalDate.parse(todayIso))
                    }
                    pratinjauSource = result.source
                } else {
                    pratinjau = id.ohmymonev.core.generator.GenerationOutcome.Failed(
                        id.ohmymonev.core.generator.FailureReason.NO_SOURCE_MATERIAL,
                        result.source,
                    )
                    pratinjauSource = result.source
                    if (!result.sessionActive) {
                        sessionActive = false
                        sessionChecked = true
                    }
                }
                pratinjauLoading = false
            }
        }.read()
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
        val saved = withContext(Dispatchers.IO) { appStore.sessionStatus() }
        if (saved != null) {
            sessionActive = saved.active
            sessionChecked = true
            // Status sesi tidak boleh mengklaim "Aktif" tanpa batas waktu.
            sessionStale = SessionFreshness.isStale(saved.checkedAtISO)
        }
        refreshPratinjau()
    }

    /** Baca keadaan sesungguhnya dari situs: status hari ini (isi/kosong/libur) + riwayat. */
    fun refreshSiteStatus() {
        checkingStatus = true
        SiteStatusProbe(context, DEFAULT_SITE_PROFILE) { result ->
            siteStatus = result
            checkingStatus = false
            sessionActive = result.sessionActive
            sessionChecked = true
            sessionStale = false
            appStore.saveSessionStatus(
                id.ohmymonev.android.data.SessionRecord(result.sessionActive, OffsetDateTime.now().toString()),
            )
        }.check()
    }

    LaunchedEffect(Unit) {
        refresh()
        refreshSiteStatus()
        // Perbaiki dulu, baru buktikan: buka layar = pastikan alarm terpasang, lalu tampilkan buktinya.
        val s = withContext(Dispatchers.IO) { store.settingsOnce() }
        armedState = withContext(Dispatchers.IO) { AlarmScheduler.ensureArmed(context, s) }
    }
    LaunchedEffect(running) {
        if (running) {
            delay(15_000)
            refresh()
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

    fun sessionReadyForAutomation(): Boolean = sessionActive == true && !sessionStale

    // Setup yang masih kurang: sesi, lokasi, notifikasi. Dipakai tombol utama dan kartu persiapan.
    val needSetup = !sessionReadyForAutomation() || !locationGranted || !notificationsOn

    val scrollState = rememberScrollState()
    // Bukti visual bahwa halaman bisa digulir: bayangan bawah + chevron, hilang setelah menggulir.
    val showScrollHint by remember {
        androidx.compose.runtime.derivedStateOf { scrollState.value < 40 }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = ZonedDateTime.now(ScheduleCalculator.asiaJakarta).format(DATE_FORMAT).uppercase(ID_LOCALE),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Hadir hari ini?", style = MaterialTheme.typography.headlineMedium)
            Text(
                // Keadaan dari situs sendiri (bila sudah pernah dibaca) menimpa asumsi lokal.
                text = when {
                    siteStatus?.sessionActive == true && siteStatus?.today == TodayState.FILLED ->
                        "Sudah absen di situs. Tidak ada yang perlu dilakukan."
                    siteStatus?.sessionActive == true && siteStatus?.today == TodayState.LIBUR ->
                        "Hari libur. Situs tidak meminta laporan hari ini."
                    !sessionReadyForAutomation() -> "Login dulu agar aplikasi bisa mengisi."
                    draftValid -> "Siap. Mesin akan mengisi pukul " + (settings?.autofillTime?.format(TIME_FORMAT) ?: "21:00") + "."
                    todayDraft != null -> "Tulisanmu belum memenuhi aturan situs. Perbaiki sebentar."
                    else -> "Belum disiapkan. Tulis sendiri atau pakai laporan kemarin."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Tutorial: langkah mencentang dirinya dari keadaan nyata, bukan klaim.
        if (!tutorialHidden) {
            TutorialCard(
                steps = listOf(
                    TutorialStep(
                        "Login sekali di tab Sesi",
                        "Buka tab Sesi di bawah, masuk seperti di Chrome.",
                        sessionReadyForAutomation(),
                    ),
                    TutorialStep(
                        "Izinkan lokasi dan notifikasi",
                        "Keduanya wajib: situs membaca lokasi, aplikasi mengingatkan lewat notifikasi.",
                        locationGranted && notificationsOn,
                    ),
                    TutorialStep(
                        "Siapkan tulisan hari ini",
                        "Tulis sendiri di kartu Tulisan hari ini, atau biarkan mesin memakai laporan kemarin.",
                        draftValid || (todayDraft == null && sessionReadyForAutomation()),
                    ),
                    TutorialStep(
                        "Tekan Isi sekarang sekali untuk uji",
                        "Lihat mesin mengisi formulir, lalu kamu yang menekan kirim di situs.",
                        lastRecord != null && lastRecord!!.outcome in listOf(
                            EngineOutcome.FILL_OK, EngineOutcome.SUBMITTED, EngineOutcome.DAY_NOT_FILLABLE,
                        ),
                    ),
                ),
                onDismiss = { tutorialHidden = true },
            )
        }

        if (needSetup || (todayDraft != null && !draftValid)) {
            ReadinessCard(
                sessionActive = sessionActive,
                sessionStale = sessionStale,
                locationGranted = locationGranted,
                notificationsOn = notificationsOn,
                draftReady = draftValid,
                draftInvalid = todayDraft != null && !draftValid,
                // Satu fungsi satu tombol: memeriksa sesi hanya ada di tab Sesi.
                // Dari sini pengguna diarahkan ke sana, bukan diperiksa ulang di sini.
                onOpenSession = onOpenSession,
                onGrantLocation = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                onGrantNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onOpenEditor = { editorExpanded = true },
            )
        }

        val interaction = remember { MutableInteractionSource() }
        // Tombol yang tidak bisa berhasil memang harus tampak mati, bukan sekali tap lalu gagal.
        Button(
            onClick = { AutoFillService.start(context, lastChance = false); running = true },
            enabled = !running && !needSetup,
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
                    !sessionReadyForAutomation() -> "Isi sekarang (login dulu)"
                    else -> "Isi sekarang"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // Baris sekunder: buka situs + uji mesin. Menulis laporan pindah ke kartu Tulisan hari ini.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onOpenSession,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text("Buka situs", maxLines = 1)
            }
            OutlinedButton(
                onClick = { AutoFillService.startBench(context); running = true },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text("Uji mesin", maxLines = 1)
            }
        }

        DraftCard(
            expanded = editorExpanded,
            onExpandedChange = { editorExpanded = it },
            onSaved = { scope.launch { refresh() } },
        )

        // Ringkasan malam ini: bukti alarm + apa yang akan terjadi + jalan keluar bila ingin mandiri.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Malam ini", style = MaterialTheme.typography.titleMedium)

                // BUKTI, bukan klaim: status alarm yang benar-benar terdaftar di OS.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Status alarm", style = MaterialTheme.typography.bodyMedium)
                        val armed = armedState
                        Text(
                            text = when {
                                armed == null -> "Memeriksa…"
                                armed.armed -> "TERPASANG · " + formatFire(armed.nextFireISO) + " · " + armedLabel(armed.source)
                                else -> "TIDAK TERPASANG (" + armed.source + ")"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                armed == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                armed.armed -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                        armed?.lastError?.takeIf { !armed.armed }?.let {
                            Text(
                                "Sistem menolak: $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextButton(onClick = { refreshArmed() }) { Text("Periksa") }
                }

                settings?.let { s ->
                    Text(
                        "Pengingat " + s.reminderTime.format(TIME_FORMAT) +
                            " · Mesin mengisi " + s.autofillTime.format(TIME_FORMAT) +
                            " · Peringatan " + s.escalationTime.format(TIME_FORMAT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (s.autofillPolicy == ScheduleSettings.Policy.FILL_AND_SUBMIT) {
                            "Mode kirim otomatis: kotak pernyataan ikut dicentang."
                        } else {
                            "Mode isi saja: formulir terisi, kamu yang menekan kirim."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                var confirmSkip by remember { mutableStateOf(false) }
                if (tonightSkipped) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "Otomatisasi malam ini dimatikan. Isi manual sebelum 23.59 WIB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { appStore.clearTonightSkip() }
                            tonightSkipped = false
                        }
                    }) { Text("Nyalakan lagi") }
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(onClick = { confirmSkip = true }) {
                        Text("Isi sendiri malam ini (matikan otomatis)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (confirmSkip) {
                    AlertDialog(
                        onDismissRequest = { confirmSkip = false },
                        title = { Text("Matikan otomatis untuk malam ini?") },
                        text = {
                            Text(
                                "Pengingat, pengisian, dan peringatan malam ini tidak akan berjalan. " +
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
                    text = { Text("Kalau malam tiba tanpa tulisan, mesin akan memakai laporan kemarin (bila ada).") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = false
                            scope.launch {
                                withContext(Dispatchers.IO) { appStore.clearTodayDraft() }
                                refresh()
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

        TextButton(
            onClick = { AlarmScheduler.quickFireForTest(context, 90) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Tes alarm 90 detik", color = MaterialTheme.colorScheme.primary)
        }

        PratinjauCard(
            outcome = pratinjau,
            loading = pratinjauLoading,
            source = pratinjauSource,
            onRefresh = { refreshPratinjau() },
        )

        LastRunRow(lastRecord)
        DisclaimerCard()
        if (sessionChecked && sessionActive == false) {
            Text(
                "Sesi belum aktif. Buka tab Sesi, login, lalu periksa lagi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        }

        // Petunjuk gulir: bayangan + chevron di tepi bawah, hilang begitu pengguna menggulir.
        androidx.compose.animation.AnimatedVisibility(
            visible = showScrollHint,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                                ),
                            ),
                        ),
                )
                Text(
                    "v",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
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
                    "terkirim. Uji dulu dengan mode isi saja sebelum menyalakan kirim otomatis.",
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
                !status.sessionActive -> Text(
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
                                TodayState.UNKNOWN -> "Tidak jelas"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = when (status.today) {
                                TodayState.UNFILLED -> MaterialTheme.colorScheme.error
                                TodayState.LIBUR -> MaterialTheme.colorScheme.onSurfaceVariant
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
private fun ScheduleSummary(settings: ScheduleSettings) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimeSlot("Pengingat", settings.reminderTime, Modifier.weight(1f))
            VerticalDivider(modifier = Modifier.height(28.dp), color = MaterialTheme.colorScheme.outlineVariant)
            TimeSlot("Isi otomatis", settings.autofillTime, Modifier.weight(1f))
            VerticalDivider(modifier = Modifier.height(28.dp), color = MaterialTheme.colorScheme.outlineVariant)
            TimeSlot("Peringatan", settings.escalationTime, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TimeSlot(label: String, time: LocalTime, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = time.format(TIME_FORMAT),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadinessCard(
    sessionActive: Boolean?,
    sessionStale: Boolean,
    locationGranted: Boolean,
    notificationsOn: Boolean,
    draftReady: Boolean,
    draftInvalid: Boolean,
    onOpenSession: () -> Unit,
    onGrantLocation: () -> Unit,
    onGrantNotifications: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Siap dipakai?", style = MaterialTheme.typography.titleMedium)
            ReadyRow(
                label = "Sesi login",
                ok = if (sessionActive == true && sessionStale) null else sessionActive,
                detail = when {
                    sessionActive == true && sessionStale -> "Sudah lama, periksa lagi"
                    sessionActive == true -> "Aktif"
                    sessionActive == false -> "Belum login"
                    else -> "Belum dicek"
                },
                // Satu fungsi satu tombol: pemeriksaan sesi hanya hidup di tab Sesi.
                action = "Login",
                onAction = onOpenSession,
            )
            ReadyRow(
                label = "Izin lokasi",
                ok = locationGranted,
                detail = if (locationGranted) "Diberikan" else "Dibutuhkan situs",
                action = if (locationGranted) null else "Izinkan",
                onAction = onGrantLocation,
            )
            ReadyRow(
                label = "Notifikasi",
                ok = notificationsOn,
                detail = if (notificationsOn) "Menyala" else "Mati",
                action = if (notificationsOn) null else "Izinkan",
                onAction = onGrantNotifications,
            )
            ReadyRow(
                label = "Laporan hari ini",
                ok = !draftInvalid && draftReady,
                detail = when {
                    draftInvalid -> "Belum sesuai aturan situs"
                    draftReady -> "Tertulis"
                    else -> "Belum ditulis"
                },
                action = if (draftReady && !draftInvalid) null else "Tulis",
                onAction = onOpenEditor,
            )
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
        Text(
            label,
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** Label ramah untuk sumber alarm yang terbaca dari OS. */
private fun armedLabel(source: String): String = when (source) {
    id.ohmymonev.core.schedule.ScheduleCalculator.KEY_REMINDER -> "pengingat"
    id.ohmymonev.core.schedule.ScheduleCalculator.KEY_AUTOFILL -> "pengisian"
    id.ohmymonev.core.schedule.ScheduleCalculator.KEY_ESCALATION -> "peringatan"
    else -> source
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

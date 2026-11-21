package id.ohmymonev.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import id.ohmymonev.android.ui.scrollIndicator
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import id.ohmymonev.android.schedule.TimeSettingsStore
import id.ohmymonev.android.ui.AlarmEditor
import id.ohmymonev.android.ui.AppRoot
import id.ohmymonev.android.ui.OhMyMonevTheme
import id.ohmymonev.android.ui.ScheduleTab
import id.ohmymonev.android.ui.SessionStatusCard
import id.ohmymonev.android.ui.PratinjauCard
import id.ohmymonev.android.data.SessionSnapshot
import id.ohmymonev.android.data.SessionRecord
import id.ohmymonev.core.generator.GenerationOutcome
import id.ohmymonev.core.model.GenerationTier
import id.ohmymonev.core.model.ReportDraft
import id.ohmymonev.core.schedule.ScheduleSettings
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Render-only fixtures: no taps, typing, or scrolling. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class UiRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String, readyText: String, fontScale: Float = 1f, captureCards: Boolean = false, captureTag: String? = null, masterEnabled: Boolean = true, source: ScheduleSettings.ContentSource = ScheduleSettings.ContentSource.TEMPLATE, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        val context = ApplicationProvider.getApplicationContext<Context>()
        id.ohmymonev.android.schedule.WorkDaysStore(context).save(org.json.JSONObject().put("id", "fixture")
            .put("off_days", org.json.JSONArray().put("SUNDAY")))
        runBlocking {
            TimeSettingsStore(context).save(ScheduleSettings(
                masterEnabled = masterEnabled,
                reminderDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                escalationDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                escalationSource = source,
            ))
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OhMyMonevTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) {
                            if (captureTag == null) content()
                            else Box(Modifier.fillMaxWidth().padding(20.dp)) { content() }
                        }
                    }
                }
            }
        }
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText(readyText, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        if (captureTag == "automatic-preview") {
            for (label in listOf("Rincian", "Tutup rincian", "Tutup laporan", "Lihat laporan")) {
                compose.onAllNodesWithText(label).assertCountEquals(0)
            }
        }
        if (!masterEnabled || name == "locked-schedule") {
            compose.onNodeWithTag("master-toggle").assertIsNotEnabled()
            for (title in listOf("Pengingat", "Pengisian darurat")) {
                compose.onNodeWithTag("alarm-toggle-$title").assertIsNotEnabled()
                compose.onNodeWithTag("alarm-edit-$title").assertIsNotEnabled()
            }
        }
        if (captureTag == "download-logs" || captureTag == "last-run") compose.onNodeWithTag(captureTag).performScrollTo()
        val root = requireNotNull(File(requireNotNull(System.getProperty("user.dir"))).parentFile)
        val output = File(root, "screenshots/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        if (captureTag == null) compose.onAllNodes(isRoot()).onLast().captureRoboImage(output.absolutePath)
        else compose.onNodeWithTag(captureTag).captureRoboImage(output.absolutePath)
        if (captureCards) {
            listOf("Pengingat", "Pengisian darurat").forEachIndexed { index, title ->
                compose.onNodeWithTag("alarm-card-$title")
                    .captureRoboImage(File(root, "screenshots/forge-card-${index + 1}${if (masterEnabled) "" else "-disabled"}${if (fontScale > 1f) "-large-text" else ""}.png").absolutePath)
            }
            compose.onNodeWithTag("template-card")
                .captureRoboImage(File(root, "screenshots/forge-template-card${if (masterEnabled) "" else "-disabled"}${if (fontScale > 1f) "-large-text" else ""}.png").absolutePath)
        }
    }

    private fun signedOutHome(name: String, fontScale: Float = 1f) {
        id.ohmymonev.android.data.SessionTokenStore(ApplicationProvider.getApplicationContext()).clear(signedOut = true)
        capture(name, "Login untuk", fontScale) { AppRoot() }
    }

    @Test fun home() = signedOutHome("forge-home")
    @Test @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun schedule() = capture("forge-schedule", "Aktifkan semua fitur") { ScheduleTab() }
    @Test @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun scheduleExplained() = capture("forge-schedule-explained", "Aktifkan semua fitur", captureCards = true) { ScheduleTab() }
    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun scheduleLargeText() = capture("forge-schedule-large-text", "Aktifkan semua fitur", 1.3f) { ScheduleTab() }
    @Test fun timeEditor() = capture("forge-time-editor", "Jam (WIB") {
        AlarmEditor(
            title = "Pengingat", initialTime = LocalTime.of(20, 0), initialEnabled = true,
            initialDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            onDismiss = {}, onSave = { _, _, _, _ -> },
        )
    }
    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun timeEditorLargeText() = capture("forge-time-editor-large-text", "Jam (WIB", 1.3f) {
        AlarmEditor(
            title = "Pengingat", initialTime = LocalTime.of(20, 0), initialEnabled = true,
            initialDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            onDismiss = {}, onSave = { _, _, _, _ -> },
        )
    }
    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun homeLargeText() = signedOutHome("forge-home-large-text", 1.3f)
    @Test fun emergencyEditorTemplate() = capture("forge-emergency-editor", "Jam (WIB") {
        AlarmEditor(
            title = "Pengisian darurat", initialTime = LocalTime.of(22, 30), initialEnabled = true,
            initialDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            initialSource = ScheduleSettings.ContentSource.TEMPLATE, templateReady = true,
            onDismiss = {}, onSave = { _, _, _, _ -> },
        )
    }
    @Test fun emergencyEditorAutomatic() = capture("forge-emergency-editor-automatic", "Jam (WIB") {
        AlarmEditor(
            title = "Pengisian darurat", initialTime = LocalTime.of(22, 30), initialEnabled = true,
            initialDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            initialSource = ScheduleSettings.ContentSource.AUTOMATIC,
            onDismiss = {}, onSave = { _, _, _, _ -> },
        )
    }
    @Test @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun emergencyEditorDownloadModel() = capture("forge-emergency-editor-download", "Unduh file otomatis") {
        AlarmEditor(
            title = "Pengisian darurat", initialTime = LocalTime.of(22, 30), initialEnabled = true,
            initialDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            initialSource = ScheduleSettings.ContentSource.AUTOMATIC, autoReady = false,
            onDownloadModel = {},
            onDismiss = {}, onSave = { _, _, _, _ -> },
        )
    }
    @Test fun templateDialog() = capture("forge-template-dialog", "Teks ini menjadi isi laporan") {
        id.ohmymonev.android.ui.TemplateEditor(
            current = null, clearBlocked = false,
            onDismiss = {}, onSave = {}, onClear = {},
        )
    }

    @Test fun sessionRememberedDuringNetworkFailure() = capture("forge-session-remembered", "Status terakhir: aktif.", captureTag = "session-status") {
        SessionStatusCard(SessionSnapshot(SessionRecord(true, "2026-11-07T10:00:00+07:00"), hasToken = true,
            issue = "Aplikasi belum dapat menghubungi situs. Status login terakhir tidak berubah."), false, {})
    }

    @Test fun sessionActuallyExpired() = capture("forge-session-expired", "Login diperlukan.", captureTag = "session-status") {
        SessionStatusCard(SessionSnapshot(SessionRecord(false, "2026-11-07T10:00:00+07:00"), issue = "Sesi berakhir. Login kembali."), false, {})
    }

    private fun preview(issues: List<String> = emptyList()): id.ohmymonev.android.engine.PreparedReport {
        val original = id.ohmymonev.android.engine.SourceReport("participant", "2026-11-06", "PRESENT", "SUBMITTED",
            "- Memeriksa kelengkapan data penerbangan untuk laporan mingguan.\n- Menguji hasil pemeriksaan dengan dokumen pendukung.\n- Menyusun catatan hasil pemeriksaan untuk tim.",
            "Memahami urutan pemeriksaan kelengkapan data dan cara mencatat hasil pemeriksaan agar setiap perubahan dapat ditelusuri kembali.",
            "Sebagian data masih memerlukan pemeriksaan lanjutan karena rincian sumber belum lengkap dan perlu dibandingkan dengan dokumen pendukung.")
        val source = if (issues.isEmpty()) original else original.copy(aktivitas = original.aktivitas.replace("Memeriksa", "Membikin"))
        val generated = id.ohmymonev.core.generator.ContinuationRuleEngine().bullets(source.aktivitas)?.joinToString("\n") { "- $it" }
        return id.ohmymonev.android.engine.PreparedReport("2026-11-08", "participant", source.date, "Hadir · ${source.label()}",
            if (issues.isEmpty()) id.ohmymonev.android.data.StoredDraft("2026-11-08", requireNotNull(generated), source.pelajaran, source.kendala) else null,
            issues, sourceReport = source, sourceReadComplete = true)
    }

    @Test @Config(qualifiers = "w411dp-h1800dp-xhdpi")
    fun automaticPreviewReady() = capture("forge-preview-ready", "Pratinjau", captureTag = "automatic-preview") {
        PratinjauCard(preview(), false, {})
    }
    @Test @Config(qualifiers = "w411dp-h1800dp-xhdpi")
    fun sourceSurvivesFailedGeneration() = capture("forge-preview-needs-edit", "Buka pratinjau", captureTag = "automatic-preview") {
        PratinjauCard(preview(listOf("Butir 1: Membikin ringkasan data.")), false, {})
    }
    @Test fun missingSource() = capture("forge-preview-empty", "Belum ada laporan Hadir", captureTag = "automatic-preview") {
        PratinjauCard(id.ohmymonev.android.engine.PreparedReport("2026-11-08", "participant", "", "", null,
            listOf("Belum ada laporan Hadir yang dapat digunakan"), sourceReadComplete = true), false, {})
    }
    @Test fun templateMissingReasonIsVisible() = capture("forge-preview-template-missing", "Templat belum dibuat", captureTag = "automatic-preview") {
        PratinjauCard(id.ohmymonev.android.engine.PreparedReport("2026-11-08", "participant", "", "", null,
            listOf("Templat belum dibuat. Buka tab Jadwal, kartu Templat laporan."), sourceReadComplete = true), false, {})
    }
    @Test fun unavailableSource() = capture("forge-preview-unavailable", "Situs belum dapat dihubungi", captureTag = "automatic-preview") {
        PratinjauCard(id.ohmymonev.android.engine.PreparedReport("2026-11-08", "participant", "", "", null,
            listOf("Situs belum dapat dihubungi. Coba Perbarui kembali."), sourceUnavailable = true), false, {})
    }
    @Test @Config(qualifiers = "w360dp-h2200dp-xhdpi")
    fun previewLargeText() = capture("forge-preview-large-text", "Pratinjau", 1.3f, captureTag = "automatic-preview") {
        PratinjauCard(preview(), false, {}, holiday = true)
    }
    @Test @Config(qualifiers = "w411dp-h1800dp-xhdpi")
    fun fullSourceDetails() = capture("forge-preview-source-full", "Laporan sumber", captureTag = "report-details") {
        id.ohmymonev.android.ui.ReportDetails(requireNotNull(preview().sourceReport).report(), "Laporan sumber")
    }

    @Test @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun scheduleAutomaticDescriptions() = capture(
        "forge-schedule-automatic",
        "Aktifkan semua fitur",
        source = ScheduleSettings.ContentSource.AUTOMATIC,
    ) { ScheduleTab() }

    @Test @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun scheduleDisabled() = capture("forge-schedule-disabled", "Aktifkan semua fitur", captureCards = true, masterEnabled = false) { ScheduleTab() }

    @Test @Config(qualifiers = "w360dp-h3200dp-xhdpi")
    fun scheduleDisabledLargeText() = capture("forge-schedule-disabled-large-text", "Aktifkan semua fitur", 1.3f, captureCards = true, masterEnabled = false) { ScheduleTab() }

    @Test fun configuredSchedule() = capture("forge-schedule-status", "Jadwal fitur berikutnya", captureTag = "schedule-status") {
        id.ohmymonev.android.ui.ScheduleStatus(
            id.ohmymonev.android.schedule.AlarmScheduler.ArmedState(true, "2026-11-07T15:30:00Z", "Pengisian darurat"), {})
    }
    @Test fun disabledScheduleStatus() = capture("forge-schedule-status-disabled", "Semua fitur nonaktif", captureTag = "schedule-status") {
        id.ohmymonev.android.ui.ScheduleStatus(
            id.ohmymonev.android.schedule.AlarmScheduler.ArmedState(false, null, "Semua fitur nonaktif"), {})
    }
    @Test @Config(qualifiers = "w411dp-h1800dp-xhdpi")
    fun holidayPreview() = capture("forge-preview-holiday", "Hari libur", captureTag = "automatic-preview") {
        PratinjauCard(preview(), false, {}, holiday = true)
    }
    @Test @Config(qualifiers = "w360dp-h3200dp-xhdpi")
    fun scheduleLargeTextExplained() = capture("forge-schedule-explained-large-text", "Aktifkan semua fitur", 1.3f, captureCards = true) { ScheduleTab() }


    @androidx.compose.runtime.Composable
    private fun ScrollFixture(initial: Int) {
        val scroll = rememberScrollState(initial)
        Column(Modifier.fillMaxWidth().height(440.dp).scrollIndicator(scroll).verticalScroll(scroll).padding(20.dp)) {
            repeat(25) { Text("Baris laporan ${it + 1}", fontSize = 20.sp, modifier = Modifier.padding(bottom = 20.dp)) }
        }
    }
    @Test fun scrollbarTop() = capture("forge-scrollbar-top", "Baris laporan") { ScrollFixture(0) }
    @Test fun scrollbarMiddle() = capture("forge-scrollbar-middle", "Baris laporan") { ScrollFixture(600) }
    @Test fun scrollbarBottom() = capture("forge-scrollbar-bottom", "Baris laporan") { ScrollFixture(100000) }

    @Test @Config(qualifiers = "w411dp-h1800dp-xhdpi")
    fun paragraphSourceIsVisibleOnceWithoutOpeningAnything() {
        val paragraph = "Mengikuti apel akhir minggu. Menjadwalkan pengolahan otomatis pada Windows agar pemeriksaan bulanan berjalan sesuai jadwal. Memperbarui panduan operasi agar sesuai kondisi terbaru."
        val original = preview(listOf("Butir 1: $paragraph"))
        capture("forge-preview-paragraph", "Pratinjau laporan", captureTag = "preview-page") {
            id.ohmymonev.android.ui.PreviewPage(original.copy(sourceReport = original.sourceReport!!.copy(aktivitas = paragraph)), {})
        }
        compose.onAllNodesWithText(paragraph).assertCountEquals(1)
        compose.onAllNodesWithText("Butir 1: $paragraph").assertCountEquals(0)
        org.junit.Assert.assertEquals("Butir 1 belum dapat diolah otomatis.", id.ohmymonev.android.ui.previewIssueSummary(listOf("Periksa aktivitas berikut:\nButir 1: $paragraph")))
    }

    @Test fun separateTimeTyping() = capture("forge-time-typing", "Menit") {
        id.ohmymonev.android.ui.TimeWheels("05", "00", {}, {})
    }

    @Test @Config(qualifiers = "w411dp-h1000dp-xhdpi")
    fun wheelBoundaryAtLargeSystemText() = capture("forge-wheel-boundary", "Hari untuk fitur ini", 2f) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            id.ohmymonev.android.ui.TimeWheels("16", "00", {}, {})
            Text("Hari untuk fitur ini", fontSize = 20.sp, modifier = Modifier.padding(top = 20.dp))
        }
    }

    @Test
    fun templateChecksDeliveryRulesAfterDateReplacement() {
        val detail = "Saya memeriksa urutan pemrosesan data dan mencatat hasil pemeriksaan untuk tim yang memelihara aplikasi."
        val activities = "- Memeriksa data peserta pada {tanggal} sebelum memperbarui daftar.\n" +
            "- Menulis hasil pemeriksaan untuk tim yang menangani daftar.\n" +
            "- Memperbaiki data peserta yang belum sesuai dengan catatan."
        val issues = id.ohmymonev.android.ui.templateIssues(activities, detail, detail)
        org.junit.Assert.assertTrue("Unexpected issues: $issues", issues.isEmpty())
        org.junit.Assert.assertTrue(
            id.ohmymonev.android.ui.templateIssues(detail, detail, detail).isEmpty(),
        )
        org.junit.Assert.assertFalse(
            id.ohmymonev.android.ui.templateIssues(activities, "Terlalu pendek", detail).isEmpty(),
        )
    }

    @Test fun manualDraftProof() = capture("manual-draft-proof", "Simpan draf") {
        id.ohmymonev.android.ui.DraftCard(
            expanded = true, onExpandedChange = {}, onSaved = {},
            savedDraft = null, account = "fixture", editable = true,
        )
    }

    @Test fun manualConfirmationProof() = capture("manual-confirmation-proof-v2", "Kirim laporan hari ini ke Monev?") {
        id.ohmymonev.android.ui.ConfirmManualReport(
            locationFallback = false, locationEnabled = true, enabled = true,
            onSend = {}, onEnableLocation = {}, onDismiss = {},
        )
    }

    @Test fun manualLocationDisabledConfirmationProof() = capture(
        "manual-location-disabled-confirmation", "Lokasi mati",
    ) {
        id.ohmymonev.android.ui.ConfirmManualReport(
            locationFallback = true, locationEnabled = false, enabled = true,
            onSend = {}, onEnableLocation = {}, onDismiss = {},
        )
    }.also {
        compose.onNodeWithText("Aktifkan lokasi untuk hasil lebih akurat. Pengiriman dengan perkiraan IP belum tentu berhasil.\n\nTetap kirim dengan perkiraan IP?").assertExists()
        compose.onNodeWithText("Aktifkan lokasi").assertExists()
        compose.onNodeWithText("Kirim dengan IP").assertExists()
        compose.onNodeWithText("Batal").assertExists()
    }

    @Test fun manualLocationUnavailableConfirmationProof() = capture(
        "manual-location-unavailable-confirmation", "Lokasi tidak tersedia",
    ) {
        id.ohmymonev.android.ui.ConfirmManualReport(
            locationFallback = true, locationEnabled = true, enabled = true,
            onSend = {}, onEnableLocation = {}, onDismiss = {},
        )
    }

    @Test fun expandedLogProvenanceProof() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "provenance-log-${UUID.randomUUID()}").apply { mkdirs() }
        val fixtureContext = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
        }
        id.ohmymonev.android.data.AppStore(fixtureContext).appendRecord(
            id.ohmymonev.core.enginecore.RunRecord(
                date = "2026-11-12",
                attemptedAtISO = "2026-11-12T21:00:00+07:00",
                outcome = id.ohmymonev.core.enginecore.EngineOutcome.SUBMITTED,
                attempt = 1,
                trigger = "EMERGENCY",
                contentSource = "TEMPLATE",
                sourceDate = "2026-11-12",
                reusedPreview = true,
                locationSource = "IP",
                fallbackReason = "disabled",
                lastStage = "submit",
                verification = "CONFIRMED",
            ),
        )
        capture("expanded-log-provenance", "Catatan") {
            CompositionLocalProvider(LocalContext provides fixtureContext) {
                id.ohmymonev.android.ui.LogTab()
            }
        }
        compose.onNodeWithText("Lihat rincian").performClick()
        compose.onNodeWithText("Tanggal sumber").assertExists()
        compose.onNodeWithText("Alasan IP").assertExists()
        compose.onNodeWithText("Terkonfirmasi").assertExists()
        compose.waitForIdle()
        val root = requireNotNull(File(requireNotNull(System.getProperty("user.dir"))).parentFile)
        compose.onAllNodes(isRoot()).onLast().captureRoboImage(File(root, "screenshots/expanded-log-provenance.png").absolutePath)
    }

    @Test fun homeProvenanceSummaryProof() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "provenance-home-${UUID.randomUUID()}").apply { mkdirs() }
        val fixtureContext = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
        }
        id.ohmymonev.android.data.AppStore(fixtureContext).appendRecord(
            id.ohmymonev.core.enginecore.RunRecord(
                date = "2026-11-12",
                attemptedAtISO = "2026-11-12T21:00:00+07:00",
                outcome = id.ohmymonev.core.enginecore.EngineOutcome.SUBMITTED,
                attempt = 1,
                trigger = "EMERGENCY",
                contentSource = "TEMPLATE",
                locationSource = "IP",
                verification = "CONFIRMED",
            ),
        )
        capture("home-provenance-summary", "Templat Anda", captureTag = "last-run") {
            val activityContext = LocalContext.current
            val screenContext = androidx.compose.runtime.remember(activityContext) {
                object : ContextWrapper(activityContext) { override fun getFilesDir() = directory }
            }
            CompositionLocalProvider(LocalContext provides screenContext) {
                id.ohmymonev.android.ui.HomeTab()
            }
        }
        compose.onNodeWithText("Perkiraan lokasi IP", substring = true).assertExists()
        compose.onNodeWithText("Tercatat di Monev", substring = true).assertExists()
    }

    @Test fun manualResultProof() = capture("report-status-colors", "Sudah terkirim") {
        Column(Modifier.padding(20.dp)) {
            for (status in id.ohmymonev.android.ui.ReportStatusLabel.entries) {
                id.ohmymonev.android.ui.ReportStatus(status, true, {})
            }
        }
    }


    @Test fun indoT5DownloadProof() = capture("indot5-download-confirmation", "Unduh file mode otomatis?") {
        id.ohmymonev.android.ui.ModelActionDialog(false, {}, {})
    }

    @Test fun indoT5DeleteProof() = capture("indot5-delete-confirmation", "Hapus file mode otomatis?") {
        id.ohmymonev.android.ui.ModelActionDialog(true, {}, {})
    }

    @Test fun lockedScheduleProof() = capture("locked-schedule", "Buka Beranda atau Sesi") {
        ScheduleTab()
    }


    @Test fun stableEmergencyDisabledProof() {
        org.junit.Assume.assumeFalse(id.ohmymonev.android.BuildConfig.DEBUG)
        id.ohmymonev.android.schedule.readySession(ApplicationProvider.getApplicationContext<Context>())
        capture("stable-emergency-disabled", "Pengisian darurat belum tersedia.") { ScheduleTab() }
        compose.onNodeWithTag("alarm-toggle-Pengisian darurat").assertIsNotEnabled()
        compose.onNodeWithTag("alarm-edit-Pengisian darurat").assertIsNotEnabled()
        compose.onNodeWithTag("alarm-toggle-Pengingat").assertIsEnabled()
    }


    @Test fun diagnosticEmergencyEnabledControlsProof() {
        org.junit.Assume.assumeTrue(id.ohmymonev.android.BuildConfig.DEBUG)
        id.ohmymonev.android.schedule.readySession(ApplicationProvider.getApplicationContext<Context>())
        capture("diagnostic-emergency-controls", "Pengisian darurat") { ScheduleTab() }
        compose.onNodeWithTag("alarm-toggle-Pengisian darurat").assertIsEnabled()
        compose.onNodeWithTag("alarm-edit-Pengisian darurat").assertIsEnabled()
    }


    @Test fun downloadLogsProof() = capture("download-logs-button", "Download logs", captureTag = "download-logs") {
        id.ohmymonev.android.ui.HomeTab()
    }

    @Test @Config(qualifiers = "w411dp-h1800dp-xhdpi")
    fun templatePreviewIsVisible() {
        val draftText = "Menyiapkan dan memeriksa kelengkapan dokumen kegiatan harian peserta secara terstruktur."
        val prepared = id.ohmymonev.android.engine.PreparedReport(
            date = "2026-11-08",
            participantId = "participant",
            sourceDate = "",
            source = "Templat Anda",
            draft = id.ohmymonev.android.data.StoredDraft("2026-11-08", draftText, draftText, draftText, "participant"),
            issues = emptyList(),
            templateKey = "TEMPLATE:1",
            sourceReadComplete = true,
        )
        capture("forge-preview-template", "Templat Anda", captureTag = "automatic-preview") {
            PratinjauCard(prepared, false, {})
        }
        compose.onNodeWithText("Buka pratinjau").assertExists()
    }

    @Test @Config(qualifiers = "w411dp-h1400dp-xhdpi")
    fun emergencyCardDeleteFileProof() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        id.ohmymonev.android.schedule.readySession(app)
        val modelDir = File(app.filesDir, "model").apply { mkdirs() }
        try {
            for (name in id.ohmymonev.android.data.ModelStore.REQUIRED_FILES) {
                val content = if (name == "META.json") """{"version":"${id.ohmymonev.android.data.ModelStore.VERSION}"}""" else "ok"
                File(modelDir, name).writeText(content)
            }
            capture("schedule-delete-in-card", "Hapus file otomatis") { ScheduleTab() }
            compose.onNodeWithTag("alarm-action-Pengisian darurat").assertIsEnabled()
        } finally {
            modelDir.deleteRecursively()
        }
    }

}

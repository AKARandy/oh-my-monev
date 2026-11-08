package id.ohmymonev.android

import android.app.Application
import android.content.Context
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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    private fun capture(name: String, readyText: String, fontScale: Float = 1f, captureCards: Boolean = false, captureTag: String? = null, masterEnabled: Boolean = true, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking {
            TimeSettingsStore(context).save(ScheduleSettings(
                masterEnabled = masterEnabled,
                reminderDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                autofillDays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
                escalationDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
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
        if (!masterEnabled) {
            compose.onNodeWithTag("master-toggle").assertIsEnabled()
            for (title in listOf("Pengingat", "Pengisian", "Peringatan terakhir")) {
                compose.onNodeWithTag("alarm-toggle-$title").assertIsNotEnabled()
                compose.onNodeWithTag("alarm-edit-$title").assertIsNotEnabled()
            }
        }
        val root = requireNotNull(File(requireNotNull(System.getProperty("user.dir"))).parentFile)
        val output = File(root, "screenshots/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        if (captureTag == null) compose.onAllNodes(isRoot()).onLast().captureRoboImage(output.absolutePath)
        else compose.onNodeWithTag(captureTag).captureRoboImage(output.absolutePath)
        if (captureCards) {
            listOf("Pengingat", "Pengisian", "Peringatan terakhir").forEachIndexed { index, title ->
                compose.onNodeWithTag("alarm-card-$title")
                    .captureRoboImage(File(root, "screenshots/forge-card-${index + 1}${if (masterEnabled) "" else "-disabled"}${if (fontScale > 1f) "-large-text" else ""}.png").absolutePath)
            }
        }
    }

    @Test fun home() = capture("forge-home", "Login untuk") { AppRoot() }
    @Test fun schedule() = capture("forge-schedule", "Aktifkan semua fitur") { ScheduleTab() }
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
    fun homeLargeText() = capture("forge-home-large-text", "Login untuk", 1.3f) { AppRoot() }
    @Test fun fillingEditor() = capture("forge-filling-editor", "Jam (WIB") {
        AlarmEditor(
            title = "Pengisian", initialTime = LocalTime.of(21, 0), initialEnabled = true,
            initialDays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
            initialAction = ScheduleSettings.Policy.FILL_ONLY,
            onDismiss = {}, onSave = { _, _, _, _ -> },
        )
    }
    @Test fun finalWarningEditor() = capture("forge-final-warning-editor", "Jam (WIB") {
        AlarmEditor(
            title = "Peringatan terakhir", initialTime = LocalTime.of(22, 30), initialEnabled = true,
            initialDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            initialAction = ScheduleSettings.Policy.REMIND_ONLY,
            onDismiss = {}, onSave = { _, _, _, _ -> },
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

    @Test fun automaticPreviewReady() = capture("forge-preview-ready", "Pratinjau", captureTag = "automatic-preview") {
        PratinjauCard(preview(), false, {})
    }
    @Test fun sourceSurvivesFailedGeneration() = capture("forge-preview-needs-edit", "Draf perlu diperiksa", captureTag = "automatic-preview") {
        PratinjauCard(preview(listOf("Butir 1: Membikin ringkasan data.")), false, {})
    }
    @Test fun missingSource() = capture("forge-preview-empty", "Belum ada laporan Hadir", captureTag = "automatic-preview") {
        PratinjauCard(id.ohmymonev.android.engine.PreparedReport("2026-11-08", "participant", "", "", null,
            listOf("Belum ada laporan Hadir yang dapat digunakan"), sourceReadComplete = true), false, {})
    }
    @Test fun unavailableSource() = capture("forge-preview-unavailable", "Sumber belum terbaca", captureTag = "automatic-preview") {
        PratinjauCard(id.ohmymonev.android.engine.PreparedReport("2026-11-08", "participant", "", "", null,
            listOf("Situs belum dapat dihubungi. Coba Perbarui kembali."), sourceUnavailable = true), false, {})
    }
    @Test @Config(qualifiers = "w360dp-h1100dp-xhdpi")
    fun previewLargeText() = capture("forge-preview-large-text", "Pratinjau", 1.3f, captureTag = "automatic-preview") {
        PratinjauCard(preview(), false, {}, holiday = true)
    }
    @Test @Config(qualifiers = "w411dp-h1800dp-xhdpi")
    fun fullSourceDetails() = capture("forge-preview-source-full", "Laporan sumber", captureTag = "report-details") {
        id.ohmymonev.android.ui.ReportDetails(requireNotNull(preview().sourceReport).report(), "Laporan sumber")
    }

    @Test @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun scheduleDisabled() = capture("forge-schedule-disabled", "Aktifkan semua fitur", captureCards = true, masterEnabled = false) { ScheduleTab() }

    @Test @Config(qualifiers = "w360dp-h3200dp-xhdpi")
    fun scheduleDisabledLargeText() = capture("forge-schedule-disabled-large-text", "Aktifkan semua fitur", 1.3f, captureCards = true, masterEnabled = false) { ScheduleTab() }

    @Test fun configuredSchedule() = capture("forge-schedule-status", "Jadwal fitur berikutnya", captureTag = "schedule-status") {
        id.ohmymonev.android.ui.ScheduleStatus(
            id.ohmymonev.android.schedule.AlarmScheduler.ArmedState(true, "2026-11-07T14:00:00Z", "Pengisian"), {})
    }
    @Test fun disabledScheduleStatus() = capture("forge-schedule-status-disabled", "Semua fitur nonaktif", captureTag = "schedule-status") {
        id.ohmymonev.android.ui.ScheduleStatus(
            id.ohmymonev.android.schedule.AlarmScheduler.ArmedState(false, null, "Semua fitur nonaktif"), {})
    }
    @Test fun holidayPreview() = capture("forge-preview-holiday", "Hari libur", captureTag = "automatic-preview") {
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
}

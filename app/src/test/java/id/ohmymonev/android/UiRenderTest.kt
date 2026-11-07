package id.ohmymonev.android

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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

/** Render-only fixtures: no taps, typing, scrolling, or interaction assertions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class UiRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String, readyText: String, fontScale: Float = 1f, captureCards: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking {
            TimeSettingsStore(context).save(ScheduleSettings(
                reminderDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                autofillDays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
                escalationDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            ))
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OhMyMonevTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText(readyText, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val root = requireNotNull(File(requireNotNull(System.getProperty("user.dir"))).parentFile)
        val output = File(root, "screenshots/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage(output.absolutePath)
        if (captureCards) {
            listOf("Pengingat", "Pengisian", "Peringatan terakhir").forEachIndexed { index, title ->
                compose.onNodeWithTag("alarm-card-$title")
                    .captureRoboImage(File(root, "screenshots/forge-card-${index + 1}.png").absolutePath)
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

}

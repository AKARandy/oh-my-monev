package id.ohmymonev.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import id.ohmymonev.android.ui.AppRoot
import id.ohmymonev.android.ui.HomeTab
import id.ohmymonev.android.ui.LogTab
import id.ohmymonev.android.ui.OhMyMonevTheme
import id.ohmymonev.android.ui.ScheduleTab
import id.ohmymonev.android.ui.SessionTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * JVM screenshot capture of the real Compose UI (Robolectric + Roborazzi), so the app's
 * appearance can be verified without an emulator/device.
 *
 * Output: PNG files under <repo-root>/screenshots/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotCaptureTest {

    @get:Rule
    val compose = createComposeRule()

    private fun target(name: String): String {
        val root = File(System.getProperty("user.dir")).parentFile ?: File(System.getProperty("user.dir"))
        val dir = File(root, "screenshots")
        dir.mkdirs()
        return File(dir, "$name.png").absolutePath
    }

    private fun capture(name: String, waitForText: String? = null, content: @Composable () -> Unit) {
        compose.setContent {
            OhMyMonevTheme {
                content()
            }
        }
        compose.waitForIdle()
        if (waitForText != null) {
            // DataStore emission is asynchronous; give the first frame the data it needs.
            runCatching {
                compose.waitUntil(timeoutMillis = 8_000) {
                    compose.onAllNodesWithText(waitForText, substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(target(name))
        println("SCREENSHOT-WRITTEN: ${target(name)}")
    }

    @Test
    fun appRoot() {
        capture("00-app-root", waitForText = "Hari ini") { AppRoot() }
    }

    @Test
    fun beranda() {
        capture("01-beranda", waitForText = "Pengingat") { HomeTab() }
    }

    @Test
    fun jadwal() {
        capture("02-jadwal", waitForText = "Simpan jadwal") { ScheduleTab() }
    }

    @Test
    fun log() {
        capture("03-log") { LogTab() }
    }

    @Test
    fun sesi() {
        capture("04-sesi") { SessionTab() }
    }
}

package id.ohmymonev.android.diagnostics

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class DiagnosticsScreenTest {
    @Test fun recoveryScreenNeedsNoSessionOrAutomation() {
        val activity = Robolectric.buildActivity(DiagnosticsActivity::class.java).setup()
        val info = activity.get().packageManager.getActivityInfo(android.content.ComponentName(activity.get(), DiagnosticsActivity::class.java), 0)
        org.junit.Assert.assertTrue(info.processName.endsWith(":diagnostics"))
        val root = File(System.getProperty("user.dir")).parentFile
        activity.get().window.decorView.captureRoboImage(File(root, "screenshots/anr-diagnostics.png").absolutePath)
        activity.pause().stop().destroy()
    }
}

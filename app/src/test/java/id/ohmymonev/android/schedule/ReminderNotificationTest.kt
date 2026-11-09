package id.ohmymonev.android.schedule

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReminderNotificationTest {
    @Test fun rendersActualNotificationTemplatesAndAlarmControls() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val notifications = ReminderNotifications(app)
        val output = File(System.getProperty("user.dir"), "build/reminder-proof").apply { mkdirs() }

        fun capture(name: String, title: String, detail: String, alarm: Boolean = false, normal: Boolean = false) {
            assertTrue(notifications.post(name, title, detail, alarm, normal))
            val notification = manager.activeNotifications.single().notification
            assertEquals(title, notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            if (alarm) {
                assertNull(notification.fullScreenIntent)
                assertEquals(listOf("Hentikan", "Tunda 10 menit", "Buka situs"),
                    notification.actions.map { it.title.toString() })
            }
            val builder = Notification.Builder.recoverBuilder(app, notification)
            val view = (builder.createBigContentView() ?: builder.createContentView())
                .apply(app, FrameLayout(app))
            val width = (385 * app.resources.displayMetrics.density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, width, view.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, view.measuredHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            view.draw(canvas)
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            notifications.cancel(name)
        }

        capture("submitted", "Laporan hari ini sudah terkirim.",
            "Tidak perlu mengisi lagi. Buka laporan untuk memeriksa isinya.", normal = true)
        capture("missing", "Laporan hari ini belum terkirim.",
            "Buka situs dan kirim laporan sebelum 23.59 WIB.", alarm = true)
        capture("offline", "Status laporan belum dapat diperiksa.",
            "Tidak ada koneksi internet. Aplikasi akan memeriksa lagi saat internet tersedia.")
        capture("delayed", "Laporan 8 November 2026 belum terkirim.", "Periksa laporan di situs.")
    }
}

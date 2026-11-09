package id.ohmymonev.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import id.ohmymonev.android.ui.AppRoot

/** Host layar utama; seluruh UI ada di package ui (tab Beranda/Jadwal/Log). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openReminderSite(intent)
        setContent {
            MaterialTheme {
                AppRoot()
            }
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openReminderSite(intent)
    }

    private fun openReminderSite(intent: android.content.Intent) {
        val key = intent.getStringExtra("reminder_site") ?: return
        intent.removeExtra("reminder_site")
        id.ohmymonev.android.schedule.ReminderController.stopFromActivity(this, key)
        val site = android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://monev.maganghub.kemnaker.go.id/"))
        try {
            startActivity(site)
        } catch (_: android.content.ActivityNotFoundException) {
            // Keep the app open when no browser is installed.
        }
    }
}

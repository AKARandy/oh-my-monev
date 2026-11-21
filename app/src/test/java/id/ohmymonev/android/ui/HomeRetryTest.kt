package id.ohmymonev.android.ui

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import id.ohmymonev.android.data.AppStore
import id.ohmymonev.android.data.SessionRepository
import id.ohmymonev.android.data.SessionTokenStore
import id.ohmymonev.android.data.StoredDraft
import id.ohmymonev.android.engine.SiteStatus
import id.ohmymonev.android.schedule.AutoFillService
import id.ohmymonev.android.schedule.ManualReportRequest
import id.ohmymonev.core.enginecore.EngineOutcome
import id.ohmymonev.core.enginecore.TodayState
import id.ohmymonev.core.schedule.ScheduleCalculator
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class HomeRetryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun freshStatusUnlocksTheOriginalDraftAcrossTabEntryAndResume() {
        val f = Fixture()
        f.mount()
        f.waitForCheck(1)
        proof("beranda-checking")
        f.response.get().complete(f.unfilled)
        f.waitForSend()
        proof("beranda-recovered")

        val afterFailure = CompletableDeferred<SiteStatus>()
        compose.runOnIdle {
            f.response.set(afterFailure)
            f.failLocation()
        }
        f.waitForCheck(2)
        compose.onNodeWithText("Kirim ke Monev").assertIsNotEnabled()
        afterFailure.complete(f.unfilled)
        f.waitForSend()
        assertEquals(f.draft, AppStore(f.app).todayDraft(f.draft.date))
        compose.onNodeWithText("Kirim ke Monev").performScrollTo()
        proof("retry-same-draft")

        compose.runOnIdle { f.showHome.value = false }
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText("Status laporan hari ini").fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle { f.showHome.value = true }
        f.waitForSend()
        assertEquals(3, f.calls.get())
        f.resume()
        f.waitForCheck(4)
        f.waitForSend()
        assertEquals(4, f.calls.get())
        assertEquals(f.draft, AppStore(f.app).todayDraft(f.draft.date))
        compose.onNodeWithText("Kirim ke Monev").performScrollTo().performClick()
        compose.onNodeWithText("Kirim dengan IP").assertIsEnabled()
        proof("ip-confirmation")
    }

    @Test fun failedVerificationRequiresRecheckAndOtherAccountsCannotRetry() {
        val f = Fixture()
        f.response.get().complete(f.unfilled)
        f.mount()
        f.waitForSend()
        compose.runOnIdle {
            f.response.set(CompletableDeferred(f.unfilled.copy(today = TodayState.UNKNOWN)))
            f.failLocation()
        }
        f.waitForCheck(2)
        compose.waitUntil(8_000) { AutoFillService.manual.value?.recheckRequired == true }
        compose.runOnIdle { f.response.set(CompletableDeferred(f.unfilled)) }
        f.resume()
        f.waitForCheck(3)
        compose.onNodeWithText("Kirim ke Monev").assertIsNotEnabled()
        compose.onNodeWithText("Cek ulang").performClick()
        f.waitForSend()

        compose.runOnIdle { f.response.set(CompletableDeferred(f.unfilled.copy(today = TodayState.FILLED))) }
        f.resume()
        f.waitForCheck(5)
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Sudah terkirim").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("Kirim ke Monev").fetchSemanticsNodes().size)

        compose.runOnIdle { f.response.set(CompletableDeferred(f.unfilled.copy(account = "other"))) }
        f.resume()
        f.waitForCheck(6)
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Belum terkirim").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("Kirim ke Monev").fetchSemanticsNodes().size)
        assertEquals(f.draft, AppStore(f.app).todayDraft(f.draft.date))
    }

    private fun proof(name: String) {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val output = File(root, "build/omnmonev-proofs/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage(output.absolutePath)
    }

    private inner class Fixture {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val draft = StoredDraft(
            LocalDate.now(ScheduleCalculator.asiaJakarta).toString(),
            "Mencatat hasil pemeriksaan dokumen dan meninjau kembali data yang sudah masuk. ".repeat(2),
            "Saya belajar mencocokkan catatan harian dengan sumber data yang dipakai oleh tim. ".repeat(2),
            "Ada beberapa data yang masih perlu diperiksa. Saya mencatatnya untuk pembahasan berikutnya. ".repeat(2),
            "participant",
        )
        private val repository = SessionRepository(app, SessionTokenStore(app) { SecretKeySpec(ByteArray(16), "AES") })
        val request = runBlocking {
            repository.credentials()
            repository.capture("fixture", repository.state.value.version)
            repository.confirm(requireNotNull(repository.credentials()))
            ManualReportRequest(draft, "participant", repository.state.value.version)
        }
        val unfilled = SiteStatus(true, TodayState.UNFILLED, null, emptyList(), account = request.account,
            credentialVersion = request.sessionVersion)
        val response = AtomicReference(CompletableDeferred<SiteStatus>())
        val calls = AtomicInteger()
        val showHome = mutableStateOf(true)

        fun mount() {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            AppStore(app).saveTodayDraft(draft)
            compose.setContent {
                OhMyMonevTheme {
                    if (showHome.value) HomeTab(sessionRepository = repository, checkStatus = { _, _ ->
                        val pending = response.get()
                        calls.incrementAndGet()
                        pending.await()
                    })
                }
            }
        }

        fun failLocation() {
            AutoFillService.startManual(app, request)
            AutoFillService.manualFinished(request, "Lokasi jaringan tidak tersedia.", EngineOutcome.LOCATION_CHECK_FAILS)
        }

        fun waitForCheck(count: Int) = compose.waitUntil(8_000) { calls.get() >= count }

        fun waitForSend() {
            compose.waitUntil(8_000) {
                compose.onAllNodesWithText("Kirim ke Monev").fetchSemanticsNodes().any { node ->
                    !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                }
            }
            compose.onNodeWithText("Kirim ke Monev").assertIsEnabled()
        }

        fun resume() {
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }
}

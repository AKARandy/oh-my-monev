package id.ohmymonev.android

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import id.ohmymonev.android.data.ReportTemplate
import id.ohmymonev.android.data.StoredDraft
import id.ohmymonev.android.engine.PreparedReport
import id.ohmymonev.android.ui.AlarmEditor
import id.ohmymonev.android.ui.DraftCard
import id.ohmymonev.android.ui.OhMyMonevTheme
import id.ohmymonev.android.ui.PratinjauCard
import id.ohmymonev.android.ui.RecordRow
import id.ohmymonev.android.ui.TemplateEditor
import id.ohmymonev.core.enginecore.EngineOutcome
import id.ohmymonev.core.enginecore.RunRecord
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1200dp-xhdpi")
class UiInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun templateModePratinjauOpensPreviewPageWithoutCrash() {
        val aktivitas = "Menyusun laporan aktivitas harian dari templat dengan rincian yang lengkap dan jelas."
        val pelajaran = "Mempelajari keteraturan pelaporan harian menggunakan templat yang terstruktur."
        val kendala = "Tidak ada kendala berarti dalam menjalankan tugas harian hari ini."
        val prepared = PreparedReport(
            date = "2026-11-08",
            participantId = "participant",
            sourceDate = "",
            source = "Templat Anda",
            draft = StoredDraft("2026-11-08", aktivitas, pelajaran, kendala, "participant"),
            issues = emptyList(),
            templateKey = "TEMPLATE:abc",
            sourceReadComplete = true,
        )
        compose.setContent {
            OhMyMonevTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PratinjauCard(prepared = prepared, loading = false, onRefresh = {})
                }
            }
        }
        compose.onNodeWithText("Templat Anda").assertExists()
        compose.onNodeWithText("Buka pratinjau").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("preview-page").assertExists()
        compose.onNodeWithText("Pratinjau laporan").assertExists()
        compose.onNodeWithText(pelajaran).assertExists()
        compose.onNodeWithText(kendala).assertExists()
    }

    @Test
    fun draftCardPreservesUnsavedEditsWhenSavedDraftUpdates() {
        var saved by mutableStateOf<StoredDraft?>(
            StoredDraft("2026-11-08", "Awal aktivitas", "Awal pelajaran", "Awal kendala", "participant"),
        )
        compose.setContent {
            OhMyMonevTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DraftCard(
                        expanded = true,
                        onExpandedChange = {},
                        onSaved = {},
                        savedDraft = saved,
                        account = "participant",
                        editable = true,
                        todayIso = "2026-11-08",
                    )
                }
            }
        }
        compose.onNodeWithText("Awal aktivitas").performTextInput(" tambahan pengguna")
        compose.waitForIdle()

        saved = StoredDraft("2026-11-08", "Tersimpan dari luar", "Pelajaran luar", "Kendala luar", "participant")
        compose.waitForIdle()

        compose.onAllNodesWithText("Tersimpan dari luar").fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("Unsaved edit should not be overwritten by savedDraft update", it.isEmpty())
        }
        compose.onNodeWithText("tambahan pengguna", substring = true).assertExists()
    }

    @Test
    fun alarmEditorDisablesSaveWhenOnlyOffDaysAreSelected() {
        compose.setContent {
            OhMyMonevTheme {
                AlarmEditor(
                    title = "Pengingat",
                    initialTime = LocalTime.of(20, 0),
                    initialEnabled = true,
                    initialDays = setOf(DayOfWeek.SUNDAY),
                    profileDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                    onDismiss = {},
                    onSave = { _, _, _, _ -> },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Simpan").assertIsNotEnabled()
    }

    @Test
    fun templateEditorPromptsConfirmationBeforeDeletingTemplate() {
        var cleared = false
        val body = "Menjalankan kegiatan harian dan mencatat rincian pekerjaan sesuai target mingguan tim."
        compose.setContent {
            OhMyMonevTheme {
                TemplateEditor(
                    current = ReportTemplate(body, body, body),
                    clearBlocked = false,
                    onDismiss = {},
                    onSave = {},
                    onClear = { cleared = true },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Hapus").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertFalse("onClear should wait for confirmation dialog", cleared)
        compose.onNodeWithText("Hapus templat laporan?").assertExists()
        compose.onAllNodesWithText("Hapus").let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].performClick()
        }
        compose.waitForIdle()
        org.junit.Assert.assertTrue("Confirmed delete should invoke onClear", cleared)
    }

    @Test
    fun logTabRecordWithoutProvenanceDetailsIsNotClickable() {
        val record = RunRecord(
            date = "2026-11-12",
            attemptedAtISO = "2026-11-12T20:00:00+07:00",
            outcome = EngineOutcome.DAY_NOT_FILLABLE,
            attempt = 1,
        )
        compose.setContent {
            OhMyMonevTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    RecordRow(record)
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Lihat rincian").fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue(it.isEmpty())
        }
        compose.onNodeWithText("2026-11-12").assertHasNoClickAction()
    }
}

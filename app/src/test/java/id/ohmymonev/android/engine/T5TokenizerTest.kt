package id.ohmymonev.android.engine

import id.ohmymonev.android.engine.t5.T5Tokenizer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tokenizer Kotlin dibandingkan token id asli yang dihasilkan tokenizer Python
 * (referensi: .tools/neural-test/tokenizer-reference.json). Tes hanya berjalan
 * bila tokenizer.json masih ada di laptop (dipertahankan untuk pengembangan).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class T5TokenizerTest {
    private val tokenizerJson = File("../.tools/neural-test/models/indot5-base-paraphrase/tokenizer.json")

    private fun tokenizer(): T5Tokenizer {
        assertTrue("tokenizer.json tidak ditemukan — unduh model dulu", tokenizerJson.isFile)
        return T5Tokenizer.fromJson(tokenizerJson.readText())
    }

    @Test fun `ids match the python tokenizer`() {
        val t = tokenizer()
        val cases = listOf(
            "Mengikuti apel akhir minggu." to listOf(15702, 14015, 276, 1488, 3, 1),
            "Memperbarui panduan operasi agar sesuai kondisi terbaru" to
                listOf(24223, 3866, 43, 11039, 1032, 438, 721, 993, 3142, 1),
            "Melakukan pengujian Windows Task Scheduler dengan interval lima menit, membuat data " +
                "dummy untuk bulan lain, mengelola database melalui DBeaver, serta mengikuti " +
                "sosialisasi UU Satu Data Indonesia." to listOf(
                787, 13995, 7837, 2329, 9621, 149, 4339, 478, 13798, 19, 18165, 594, 1406, 8, 160,
                898, 2889, 11653, 21, 146, 66, 8, 3901, 16669, 162, 222, 3988, 7, 1499, 8, 116,
                655, 20665, 4349, 3441, 5207, 55, 3, 1,
            ),
        )
        for ((text, expected) in cases) {
            assertEquals(expected, t.encode(text))
        }
    }

    @Test fun `decode returns the original sentence`() {
        val t = tokenizer()
        val sentence = "Memperbarui panduan operasi agar sesuai kondisi terbaru"
        val ids = t.encode(sentence)
        assertTrue(t.decode(ids).endsWith(sentence))
    }

    @Test fun `utf8 byte fallback preserves non-ascii punctuation and ascii fallback never emits pad`() {
        val vocabEntries = org.json.JSONArray()
            .put(org.json.JSONArray().put("<pad>").put(0.0))
            .put(org.json.JSONArray().put("</s>").put(0.0))
            .put(org.json.JSONArray().put("▁").put(-1.0))
            .put(org.json.JSONArray().put("a").put(-1.0))
            .put(org.json.JSONArray().put("b").put(-1.0))
        for (b in 0..255) {
            val hex = "<0x" + b.toString(16).uppercase().padStart(2, '0') + ">"
            vocabEntries.put(org.json.JSONArray().put(hex).put(-10.0))
        }
        val added = org.json.JSONArray()
            .put(org.json.JSONObject().put("id", 0).put("content", "<pad>"))
            .put(org.json.JSONObject().put("id", 1).put("content", "</s>"))
        val json = org.json.JSONObject()
            .put("added_tokens", added)
            .put("model", org.json.JSONObject().put("vocab", vocabEntries))
            .toString()
        val synthetic = T5Tokenizer.fromJson(json)
        val input = "a—b “c” •"
        val ids = synthetic.encode(input)
        assertTrue(ids.size > 5)
        assertTrue(ids.dropLast(1).none { it == 0 })
        assertEquals(input, synthetic.decode(ids))
    }
}

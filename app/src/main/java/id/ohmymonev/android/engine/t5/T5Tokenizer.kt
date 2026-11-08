package id.ohmymonev.android.engine.t5

import org.json.JSONObject

/**
 * Tokenizer T5 (SentencePiece unigram) minimal untuk teks Latin, dimuat dari
 * tokenizer.json. Encoding = Viterbi atas kosakata dengan byte-fallback;
 * decoding = potongan digabung, "▁" menjadi spasi. Cukup untuk input laporan
 * beraksara Latin; divalidasi terhadap token id yang dihasilkan tokenizer Python.
 */
class T5Tokenizer private constructor(
    private val pieceScores: Map<String, Pair<Int, Double>>,
    private val eosId: Int,
    private val padId: Int,
) {

    fun encode(text: String): List<Int> {
        val normalized = PIECE_SPACE + text.replace(" ", PIECE_SPACE)
        val best = DoubleArray(normalized.length + 1) { Double.NEGATIVE_INFINITY }
        val back = arrayOfNulls<Int>(normalized.length + 1)
        best[0] = 0.0
        for (i in normalized.indices) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            var end = i
            while (end < normalized.length && end - i <= MAX_PIECE_CHARS) {
                val candidate = normalized.substring(i, end + 1)
                val entry = pieceScores[candidate]
                if (entry != null && best[end + 1] < best[i] + entry.second) {
                    best[end + 1] = best[i] + entry.second
                    back[end + 1] = i
                }
                end++
            }
            // byte-fallback: karakter tunggal tanpa potongan tetap maju satu byte
            if (back[i + 1] == null) {
                val entry = pieceScores[bytePieceOf(normalized[i])]
                if (entry != null && best[i + 1] < best[i] + entry.second) {
                    best[i + 1] = best[i] + entry.second
                    back[i + 1] = i
                }
            }
        }
        val pieces = mutableListOf<String>()
        var cursor = normalized.length
        while (cursor > 0) {
            val start = back[cursor] ?: break
            pieces.add(normalized.substring(start, cursor))
            cursor = start
        }
        pieces.reverse()
        val ids = pieces.map { piece -> pieceScores[piece]?.first ?: padId }
        return ids + eosId
    }

    fun decode(ids: List<Int>, skipSpecialTokens: Boolean = true): String {
        val reverse = HashMap<Int, String>()
        for ((piece, entry) in pieceScores) reverse[entry.first] = piece
        val builder = StringBuilder()
        for (id in ids) {
            if (skipSpecialTokens && (id == eosId || id == padId)) continue
            val piece = reverse[id] ?: continue
            if (piece.startsWith("<0x") && piece.endsWith(">")) {
                val byte = piece.removePrefix("<0x").removeSuffix(">").toInt(16)
                builder.append(byte.toChar())
            } else {
                builder.append(piece)
            }
        }
        return builder.toString().replace(PIECE_SPACE, " ").trimStart(' ')
    }

    private fun bytePieceOf(ch: Char): String =
        "<0x" + ch.code.toString(16).uppercase().padStart(2, '0') + ">"

    companion object {
        private const val PIECE_SPACE = "▁"
        private const val MAX_PIECE_CHARS = 48

        /** Memuat tokenizer.json; hanya butuh model.vocab + id khusus. */
        fun fromJson(json: String): T5Tokenizer {
            val root = JSONObject(json)
            val model = root.getJSONObject("model")
            val vocab = model.getJSONArray("vocab")
            val scores = HashMap<String, Pair<Int, Double>>(vocab.length() * 2)
            for (i in 0 until vocab.length()) {
                val entry = vocab.getJSONArray(i)
                scores[entry.getString(0)] = i to entry.getDouble(1)
            }
            return T5Tokenizer(scores, eosId(root), padId(root))
        }

        private fun eosId(root: JSONObject): Int =
            root.optJSONArray("added_tokens")?.let { added ->
                (0 until added.length()).map { added.getJSONObject(it) }
                    .firstOrNull { it.optString("content") == "</s>" }?.optInt("id")
            } ?: 1

        private fun padId(root: JSONObject): Int =
            root.optJSONArray("added_tokens")?.let { added ->
                (0 until added.length()).map { added.getJSONObject(it) }
                    .firstOrNull { it.optString("content") == "<pad>" }?.optInt("id")
            } ?: 0
    }
}

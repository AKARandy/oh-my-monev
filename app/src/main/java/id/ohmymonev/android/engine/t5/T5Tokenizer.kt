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
    private val idToPiece: Map<Int, String> = HashMap<Int, String>(pieceScores.size * 2).apply {
        for ((piece, entry) in pieceScores) put(entry.first, piece)
    }

    fun encode(text: String): List<Int> {
        val normalized = PIECE_SPACE + text.replace(" ", PIECE_SPACE)
        val best = DoubleArray(normalized.length + 1) { Double.NEGATIVE_INFINITY }
        val back = arrayOfNulls<Int>(normalized.length + 1)
        val backIds = arrayOfNulls<List<Int>>(normalized.length + 1)
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
                    backIds[end + 1] = listOf(entry.first)
                }
                end++
            }
            // byte-fallback UTF-8: karakter (atau surrogate pair) tanpa potongan tetap maju lewat token <0xNN>
            val stepLen = if (Character.isHighSurrogate(normalized[i]) &&
                i + 1 < normalized.length && Character.isLowSurrogate(normalized[i + 1])
            ) 2 else 1
            if (back[i + stepLen] == null) {
                val utf8Bytes = normalized.substring(i, i + stepLen).toByteArray(Charsets.UTF_8)
                val byteEntries = mutableListOf<Pair<Int, Double>>()
                for (b in utf8Bytes) {
                    val entry = pieceScores[bytePieceOf(b.toInt() and 0xFF)] ?: break
                    byteEntries.add(entry)
                }
                if (byteEntries.size == utf8Bytes.size) {
                    val totalScore = byteEntries.sumOf { it.second }
                    if (best[i + stepLen] < best[i] + totalScore) {
                        best[i + stepLen] = best[i] + totalScore
                        back[i + stepLen] = i
                        backIds[i + stepLen] = byteEntries.map { it.first }
                    }
                } else {
                    best[i + stepLen] = best[i] - 100.0
                    back[i + stepLen] = i
                    backIds[i + stepLen] = listOf(padId)
                }
            }
        }
        val chunks = mutableListOf<List<Int>>()
        var cursor = normalized.length
        while (cursor > 0) {
            val start = back[cursor] ?: break
            chunks.add(backIds[cursor] ?: listOf(padId))
            cursor = start
        }
        chunks.reverse()
        val ids = chunks.flatten()
        return ids + eosId
    }

    fun decode(ids: List<Int>, skipSpecialTokens: Boolean = true): String {
        val builder = StringBuilder()
        val byteBuffer = java.io.ByteArrayOutputStream()
        fun flushBytes() {
            if (byteBuffer.size() > 0) {
                builder.append(String(byteBuffer.toByteArray(), Charsets.UTF_8))
                byteBuffer.reset()
            }
        }
        for (id in ids) {
            if (skipSpecialTokens && (id == eosId || id == padId)) continue
            val piece = idToPiece[id] ?: continue
            if (piece.length == 6 && piece.startsWith("<0x") && piece.endsWith(">")) {
                val parsed = piece.substring(3, 5).toIntOrNull(16)
                if (parsed != null) {
                    byteBuffer.write(parsed)
                    continue
                }
            }
            flushBytes()
            builder.append(piece)
        }
        flushBytes()
        return builder.toString().replace(PIECE_SPACE, " ").trimStart(' ')
    }

    private fun bytePieceOf(byteVal: Int): String =
        "<0x" + byteVal.toString(16).uppercase().padStart(2, '0') + ">"

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

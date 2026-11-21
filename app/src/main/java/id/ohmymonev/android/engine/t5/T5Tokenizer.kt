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
    private val maxPieceChars: Int = pieceScores.keys.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
    private val idToPiece: Map<Int, String> = HashMap<Int, String>(pieceScores.size * 2).apply {
        for ((piece, entry) in pieceScores) put(entry.first, piece)
    }

    fun encode(text: String): List<Int> {
        val normalized = PIECE_SPACE + text.replace(" ", PIECE_SPACE)
        val n = normalized.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val back = IntArray(n + 1) { -1 }
        val backToken = IntArray(n + 1) { -1 }
        var backMulti: HashMap<Int, IntArray>? = null
        best[0] = 0.0
        for (i in 0 until n) {
            val baseScore = best[i]
            if (baseScore == Double.NEGATIVE_INFINITY) continue
            val limit = minOf(n, i + maxPieceChars)
            for (endExclusive in (i + 1)..limit) {
                val entry = pieceScores[normalized.substring(i, endExclusive)] ?: continue
                val score = baseScore + entry.second
                if (best[endExclusive] < score) {
                    best[endExclusive] = score
                    back[endExclusive] = i
                    backToken[endExclusive] = entry.first
                    backMulti?.remove(endExclusive)
                }
            }
            // byte-fallback UTF-8: karakter (atau surrogate pair) tanpa potongan tetap maju lewat token <0xNN>
            val stepLen = if (Character.isHighSurrogate(normalized[i]) &&
                i + 1 < n && Character.isLowSurrogate(normalized[i + 1])
            ) 2 else 1
            val nextIdx = i + stepLen
            if (back[nextIdx] == -1) {
                val utf8Bytes = normalized.substring(i, nextIdx).toByteArray(Charsets.UTF_8)
                var totalScore = 0.0
                var mappedCount = 0
                val mappedIds = IntArray(utf8Bytes.size)
                for (k in utf8Bytes.indices) {
                    val entry = pieceScores[bytePieceOf(utf8Bytes[k].toInt() and 0xFF)] ?: break
                    mappedIds[k] = entry.first
                    totalScore += entry.second
                    mappedCount++
                }
                if (mappedCount == utf8Bytes.size) {
                    val score = baseScore + totalScore
                    if (best[nextIdx] < score) {
                        best[nextIdx] = score
                        back[nextIdx] = i
                        if (mappedIds.size == 1) {
                            backToken[nextIdx] = mappedIds[0]
                            backMulti?.remove(nextIdx)
                        } else {
                            backToken[nextIdx] = -1
                            (backMulti ?: HashMap<Int, IntArray>().also { backMulti = it })[nextIdx] = mappedIds
                        }
                    }
                } else {
                    best[nextIdx] = baseScore - 100.0
                    back[nextIdx] = i
                    backToken[nextIdx] = padId
                    backMulti?.remove(nextIdx)
                }
            }
        }
        val ids = ArrayList<Int>(n + 1)
        var cursor = n
        while (cursor > 0) {
            val start = back[cursor]
            if (start < 0) break
            val multi = backMulti?.get(cursor)
            if (multi != null) {
                for (k in multi.indices.reversed()) ids.add(multi[k])
            } else {
                ids.add(backToken[cursor].let { if (it >= 0) it else padId })
            }
            cursor = start
        }
        ids.reverse()
        ids.add(eosId)
        return ids
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
            var eosId = 1
            var padId = 0
            root.optJSONArray("added_tokens")?.let { added ->
                for (i in 0 until added.length()) {
                    val token = added.getJSONObject(i)
                    when (token.optString("content")) {
                        "</s>" -> eosId = token.optInt("id", 1)
                        "<pad>" -> padId = token.optInt("id", 0)
                    }
                }
            }
            return T5Tokenizer(scores, eosId, padId)
        }
    }
}

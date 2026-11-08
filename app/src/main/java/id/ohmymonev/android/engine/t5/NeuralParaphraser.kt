package id.ohmymonev.android.engine.t5

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/** Mesin parafrasa per kalimat untuk mode Otomatis. Implementasi nyata memakai ONNX di perangkat. */
interface NeuralParaphraser {
    /** Mengembalikan parafrasa untuk setiap kalimat masukan, urutan tetap. */
    suspend fun paraphrase(sentences: List<String>): List<String>

    fun close()

    companion object {
        /** Ekor halusinasi khas IndoT5: "(...)" tempelan di akhir kalimat dibuang. */
        fun stripTrailingParenthetical(text: String): String =
            text.replace(Regex("\\s*\\([^()]*\\)\\s*$"), "").trim()
    }
}

/** Implementasi ONNX Runtime: T5 fp16 (encoder + decoder + decoder-with-past) dari folder model. */
class OrtNeuralParaphraser private constructor(
    private val environment: OrtEnvironment,
    private val modelDir: File,
    private val tokenizer: T5Tokenizer,
    threads: Int,
) : NeuralParaphraser {

    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    private val encoder by lazy { environment.createSession(File(modelDir, "encoder_model.onnx").absolutePath, options) }
    private val decoderFirst by lazy { environment.createSession(File(modelDir, "decoder_model.onnx").absolutePath, options) }
    private val decoderRest by lazy { environment.createSession(File(modelDir, "decoder_with_past_model.onnx").absolutePath, options) }
    private val startId = 0L
    private val eosId = 1L

    override suspend fun paraphrase(sentences: List<String>): List<String> =
        sentences.map { paraphraseOne(it) }

    private fun paraphraseOne(sentence: String): String {
        val prompt = "paraphrase: $sentence </s>"
        val ids = tokenizer.encode(prompt).map { it.toLong() }
        val inputIds = OnnxTensor.createTensor(environment, java.nio.LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, ids.size.toLong()))
        val mask = OnnxTensor.createTensor(environment, java.nio.LongBuffer.wrap(LongArray(ids.size) { 1L }), longArrayOf(1, ids.size.toLong()))
        val hidden = (encoder.run(mapOf("input_ids" to inputIds, "attention_mask" to mask))[0] as OnnxTensor)
        val retained = mutableListOf(inputIds, mask, hidden)
        val transient = mutableListOf<OnnxTensor>()
        val tokens = mutableListOf<Long>()
        val past = LinkedHashMap<String, OnnxTensor>()
        var lastToken = startId
        try {
            var step = 0
            while (step <= 96) {
                val sess = if (step == 0) decoderFirst else decoderRest
                val feeds = HashMap<String, OnnxTensor>()
                val stepIds = OnnxTensor.createTensor(environment, java.nio.LongBuffer.wrap(longArrayOf(lastToken)), longArrayOf(1, 1))
                retained.add(stepIds)
                feeds["input_ids"] = stepIds
                feeds["encoder_attention_mask"] = mask
                if (step == 0) feeds["encoder_hidden_states"] = hidden
                past.forEach { (key, tensor) -> feeds[key] = tensor }
                val result = sess.run(feeds)
                val names = sess.outputNames.toList()
                val logits = result[0] as OnnxTensor
                transient.add(logits)
                val vocab = logits.info.shape[2].toInt()
                val buffer: java.nio.FloatBuffer = logits.floatBuffer
                var bestId = 0L
                var best = Float.NEGATIVE_INFINITY
                for (v in 0 until vocab) {
                    val score = buffer.get(v)
                    if (score > best) { best = score; bestId = v.toLong() }
                }
                if (bestId == eosId) break
                tokens.add(bestId)
                lastToken = bestId
                for (i in 1 until names.size) {
                    val name = names[i]
                    val tensor = result[i] as OnnxTensor
                    if (step == 0) {
                        // presents lengkap (encoder + decoder) dipertahankan untuk semua langkah.
                        val key = name.replaceFirst("present.", "past_key_values.")
                        past[key] = tensor
                        retained.add(tensor)
                    } else if (".decoder." in name) {
                        // presents decoder diperbarui tiap langkah; presents encoder tetap dari langkah 1.
                        val key = name.replaceFirst("present.", "past_key_values.")
                        past[key]?.let { old -> transient.add(old) }
                        past[key] = tensor
                    } else {
                        transient.add(tensor)
                    }
                }
                step++
            }
        } finally {
            transient.forEach { runCatching { it.close() } }
        }
        val text = tokenizer.decode(tokens.map { it.toInt() })
        retained.forEach { runCatching { it.close() } }
        return NeuralParaphraser.stripTrailingParenthetical(text)
    }

    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoderFirst.close() }
        runCatching { decoderRest.close() }
        runCatching { options.close() }
    }

    companion object {
        /** Null bila model belum diunduh. Pemanggil bertanggung jawab menutup instance. */
        fun createOrNull(modelDir: File): OrtNeuralParaphraser? {
            val required = listOf("encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx", "tokenizer.json")
            if (required.any { !File(modelDir, it).isFile }) return null
            val tokenizer = T5Tokenizer.fromJson(File(modelDir, "tokenizer.json").readText())
            return OrtNeuralParaphraser(
                OrtEnvironment.getEnvironment(), modelDir, tokenizer,
                threads = maxOf(1, Runtime.getRuntime().availableProcessors() - 2),
            )
        }
    }
}

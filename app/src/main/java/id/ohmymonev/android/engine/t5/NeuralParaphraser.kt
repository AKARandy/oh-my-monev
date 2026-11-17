package id.ohmymonev.android.engine.t5

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import kotlinx.coroutines.*

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
    private val encoderDelegate = lazy { environment.createSession(File(modelDir, "encoder_model.onnx").absolutePath, options) }
    private val decoderFirstDelegate = lazy { environment.createSession(File(modelDir, "decoder_model.onnx").absolutePath, options) }
    private val decoderRestDelegate = lazy { environment.createSession(File(modelDir, "decoder_with_past_model.onnx").absolutePath, options) }
    private val encoder get() = encoderDelegate.value
    private val decoderFirst get() = decoderFirstDelegate.value
    private val decoderRest get() = decoderRestDelegate.value
    private val startId = 0L
    private val eosId = 1L

    override suspend fun paraphrase(sentences: List<String>): List<String> =
        withContext(Dispatchers.Default) { sentences.map { ensureActive(); paraphraseOne(it) } }

    private suspend fun paraphraseOne(sentence: String): String {
        val prompt = "paraphrase: $sentence </s>"
        val ids = tokenizer.encode(prompt).map { it.toLong() }
        val inputIds = OnnxTensor.createTensor(environment, java.nio.LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, ids.size.toLong()))
        val results = mutableListOf<OrtSession.Result>()
        val retained = mutableListOf(inputIds)
        val tokens = mutableListOf<Long>()
        val past = LinkedHashMap<String, OnnxTensor>()
        var lastToken = startId
        try {
            val mask = OnnxTensor.createTensor(environment, java.nio.LongBuffer.wrap(LongArray(ids.size) { 1L }), longArrayOf(1, ids.size.toLong())).also { retained += it }
            val encoded = encoder.run(mapOf("input_ids" to inputIds, "attention_mask" to mask)).also { results += it }
            val hidden = encoded[0] as OnnxTensor
            var step = 0
            while (step <= 96) {
                currentCoroutineContext().ensureActive()
                val sess = if (step == 0) decoderFirst else decoderRest
                val feeds = HashMap<String, OnnxTensor>()
                val stepIds = OnnxTensor.createTensor(environment, java.nio.LongBuffer.wrap(longArrayOf(lastToken)), longArrayOf(1, 1))
                retained.add(stepIds)
                feeds["input_ids"] = stepIds
                feeds["encoder_attention_mask"] = mask
                if (step == 0) feeds["encoder_hidden_states"] = hidden
                past.forEach { (key, tensor) -> feeds[key] = tensor }
                val result = sess.run(feeds).also { results += it }
                val names = sess.outputNames.toList()
                val logits = result[0] as OnnxTensor
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
                    } else if (".decoder." in name) {
                        // presents decoder diperbarui tiap langkah; presents encoder tetap dari langkah 1.
                        val key = name.replaceFirst("present.", "past_key_values.")
                        past[key] = tensor
                    }
                }
                if (results.size > 3) results.removeAt(2).close()
                step++
            }
            return NeuralParaphraser.stripTrailingParenthetical(tokenizer.decode(tokens.map { it.toInt() }))
        } finally {
            results.forEach { runCatching { it.close() } }
            retained.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        if (encoderDelegate.isInitialized()) runCatching { encoder.close() }
        if (decoderFirstDelegate.isInitialized()) runCatching { decoderFirst.close() }
        if (decoderRestDelegate.isInitialized()) runCatching { decoderRest.close() }
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

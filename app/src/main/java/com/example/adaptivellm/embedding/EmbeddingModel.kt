package com.example.adaptivellm.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

/**
 * Sentence embedding модель для семантического поиска фактов (Stage 3, Phase 1).
 *
 * Модель — `deepvk/USER2-small` (ModernBERT, ~34M параметров, 384-dim embedding),
 * INT8-квантизированная. **Tokenizer вшит в ONNX граф** через
 * `onnxruntime-extensions` (см. `scripts/embed_tokenizer_into_onnx.py` для
 * процедуры re-export'а). Это значит модель принимает строку напрямую и
 * выдаёт embedding без отдельного tokenizer-вызова.
 *
 * Pooling: CLS (config: `"classifier_pooling": "cls"`), результат L2-нормализован
 * для cosine similarity == dot product.
 *
 * Используется как singleton — модель loadится один раз в [initialize], затем
 * [encode] thread-safe (ONNX Runtime session re-entrant). Загрузка ~200-500 мс
 * на CPU, inference на одну строку ~10-50 мс.
 *
 * **Жизненный цикл**:
 *   - `initialize(context)` — bootstrap (после DB init, до retrieval ops)
 *   - `encode(text)` — каждый поиск по фактам, fact extraction
 *   - `shutdown()` — onCleared
 *
 * Asset: `embedding/model_with_tokenizer.onnx` (~36 MB). При первом запуске
 * копируется в filesDir, потому что ONNX Runtime требует file path.
 */
object EmbeddingModel {

    private const val TAG = "EmbeddingModel"
    private const val ASSETS_DIR = "embedding"
    private const val MODEL_FILE = "model_with_tokenizer.onnx"
    private const val EMBEDDING_DIM = 384

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Volatile
    private var initialized = false

    val isInitialized: Boolean get() = initialized

    /**
     * Загружает модель. Идемпотентно — повторные вызовы — no-op. Synchronized
     * чтобы concurrent callers не loadили одновременно.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val t0 = System.currentTimeMillis()
        try {
            val modelFile = copyAssetIfNeeded(context, MODEL_FILE)

            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // КРИТИЧНО: регистрируем custom op библиотеку onnxruntime-extensions
                // — она содержит tokenizer ops которые вшиты в наш ONNX граф. Без
                // этого session.run упадёт с "Unknown op: HfBertTokenizer" или подобным.
                registerCustomOpLibrary(OrtxPackage.getLibraryPath())
                // INT8 quantized → CPU execution provider стабильный на Android.
                setIntraOpNumThreads(2)
            }
            session = env!!.createSession(modelFile.absolutePath, opts)

            initialized = true
            Log.i(TAG, "EmbeddingModel initialized in ${System.currentTimeMillis() - t0}ms " +
                       "(model=${modelFile.length() / 1024} KB)")
        } catch (e: Exception) {
            Log.e(TAG, "EmbeddingModel init failed", e)
            shutdown()
            throw e
        }
    }

    /**
     * Возвращает 384-dim L2-нормализованный embedding текста. Thread-safe.
     *
     * Модель принимает строку напрямую (tokenizer вшит в граф). Output —
     * last_hidden_state shape [1, seq_len, 384]; берём CLS токен (index 0).
     *
     * @throws IllegalStateException если модель не инициализирована
     */
    fun encode(text: String): FloatArray {
        val session = this.session ?: error("EmbeddingModel not initialized — call initialize() first")
        val env = this.env ?: error("EmbeddingModel not initialized — call initialize() first")

        // String input как 1-element string tensor (ORT extensions требуют это для
        // BatchedDecoder / HfBertTokenizer ops).
        val inputTensor = OnnxTensor.createTensor(env, arrayOf(text))

        try {
            // Имя input'а определяется при tokenizer ONNX генерации — стандартно
            // "input_text" или "text". При re-export видно в логе скрипта.
            val inputName = session.inputNames.firstOrNull()
                ?: error("Session has no inputs")
            session.run(mapOf(inputName to inputTensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = result.get(0).value as Array<Array<FloatArray>>
                val cls = raw[0][0]  // CLS token, [384]
                return l2Normalize(cls)
            }
        } finally {
            inputTensor.close()
        }
    }

    /** Cosine similarity между двумя ужé L2-нормализованными векторами = dot product. */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "size mismatch: ${a.size} vs ${b.size}" }
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    @Synchronized
    fun shutdown() {
        runCatching { session?.close() }
        // OrtEnvironment singleton-style, не закрываем явно
        session = null
        env = null
        initialized = false
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum).toFloat()
        if (norm < 1e-12f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
    }

    /**
     * Копирует asset в filesDir/embedding/. ONNX Runtime требует file path
     * (а не байты), поэтому копирование разовая операция при первом запуске.
     * Проверка по размеру — если файл уже копирован, skip.
     */
    private fun copyAssetIfNeeded(context: Context, assetName: String): File {
        val outDir = File(context.filesDir, ASSETS_DIR).apply { mkdirs() }
        val outFile = File(outDir, assetName)
        context.assets.open("$ASSETS_DIR/$assetName").use { input ->
            val expectedSize = input.available().toLong()
            if (outFile.exists() && outFile.length() == expectedSize) {
                return outFile
            }
            context.assets.open("$ASSETS_DIR/$assetName").use { reopened ->
                FileOutputStream(outFile).use { out ->
                    reopened.copyTo(out)
                }
            }
        }
        return outFile
    }
}

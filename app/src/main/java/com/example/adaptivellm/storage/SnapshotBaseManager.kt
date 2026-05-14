package com.example.adaptivellm.storage

import android.content.Context
import android.util.Log
import com.example.adaptivellm.BuildConfig
import com.example.adaptivellm.inference.InferenceEngine
import java.io.File
import java.security.MessageDigest

/**
 * Управление файлом `snapshot_base.bin` — KV state в положении `[system_only]`,
 * сериализованный через `llama_state_seq_save_file`. Работает только на
 * Strategy A (см. architecture.md § Backend compatibility); caller обязан
 * сам проверить [InferenceEngine.supportsStatePersist] перед использованием.
 *
 * **Зачем нужно**: при создании нового чата или переключении между чатами
 * нужно вернуть KV в состояние «только system prompt декодирован». Без
 * snapshot_base каждый такой переход делает `setSystemPrompt` который
 * декодирует system prompt заново (~2-3 сек на A80 для 50 токенов). С
 * snapshot_base — load файла (~50 мс).
 *
 * **Хранение**: два файла рядом в `filesDir`:
 *   - `snapshot_base.bin` — сериализованный KV state
 *   - `snapshot_base.meta` — текстовый header с identity полями для
 *     валидации; одна строка формата `v1|<modelKey>|<systemPromptHash>|<nCtx>|<versionCode>`
 *
 * **Invalidation** автоматическая через сравнение метаданных при load:
 *   - Изменился system prompt в коде → systemPromptHash другой
 *   - Сменилась модель → modelKey другой
 *   - APK update → versionCode другой (proxy для llama.cpp build hash)
 *   - Изменился контекст (RAM tier) → nCtx другой
 * Любое из этих → load возвращает false, caller fall-back'ает на
 * setSystemPrompt + recreate snapshot.
 */
class SnapshotBaseManager(context: Context) {

    companion object {
        private const val TAG = "SnapshotBase"
        private const val META_VERSION = "v1"
    }

    private val binFile: File = File(context.filesDir, "snapshot_base.bin")
    private val metaFile: File = File(context.filesDir, "snapshot_base.meta")

    /**
     * Считывает существующий snapshot_base в engine KV. Перед этим валидирует
     * metadata против текущих параметров.
     *
     * @return true если snapshot валиден и загружен; false если файл отсутствует,
     *         metadata не совпадает (тогда файлы автоматически удаляются), или
     *         native load failed.
     */
    suspend fun load(
        engine: InferenceEngine,
        systemPrompt: String,
        modelKey: String,
        nCtx: Int,
    ): Boolean {
        if (!binFile.exists() || !metaFile.exists()) {
            Log.i(TAG, "load: file(s) missing — bin=${binFile.exists()}, meta=${metaFile.exists()}")
            return false
        }
        val expected = buildMeta(systemPrompt, modelKey, nCtx)
        val actual = runCatching { metaFile.readText().trim() }.getOrNull()
        if (actual != expected) {
            Log.w(TAG, "load: metadata mismatch — invalidating snapshot " +
                       "(expected='$expected', actual='$actual')")
            invalidate()
            return false
        }
        val rc = engine.stateLoadFile(binFile.absolutePath)
        if (rc != 0) {
            Log.w(TAG, "load: stateLoadFile failed (rc=$rc) — invalidating")
            invalidate()
            return false
        }
        // КРИТИЧНО: snapshot_base содержит ТОЛЬКО system prompt, поэтому
        // g_system_pos должен равняться g_current_pos (= n_loaded). Без этого
        // shift_context / proactive_reset оперируют с g_system_pos=0, что
        // ломает логику (system tokens могут быть удалены при shift или
        // re-decoded поверх loaded state).
        engine.setSystemPos(engine.getCurrentPos())
        Log.i(TAG, "load: snapshot_base loaded (${binFile.length()} bytes), system_pos=${engine.getCurrentPos()}")
        return true
    }

    /**
     * Сохраняет текущий engine KV state в snapshot_base.bin + пишет metadata.
     * Caller гарантирует что KV в этот момент = `[system_only]` (обычно сразу
     * после `engine.setSystemPrompt`).
     *
     * Idempotent: если файлы уже существуют с правильной metadata — no-op
     * (не перезаписываем — это лишняя disk I/O).
     *
     * @return true если файлы валидны после вызова (либо уже были, либо
     *         только что созданы); false при ошибке save.
     */
    suspend fun save(
        engine: InferenceEngine,
        systemPrompt: String,
        modelKey: String,
        nCtx: Int,
    ): Boolean {
        val expected = buildMeta(systemPrompt, modelKey, nCtx)
        if (binFile.exists() && metaFile.exists()) {
            val actual = runCatching { metaFile.readText().trim() }.getOrNull()
            if (actual == expected) {
                Log.i(TAG, "save: existing snapshot is valid — skipping write")
                return true
            }
        }
        val rc = engine.stateSaveFile(binFile.absolutePath)
        if (rc != 0) {
            Log.e(TAG, "save: stateSaveFile failed (rc=$rc)")
            // Очищаем metadata чтобы load в будущем не попытался использовать
            // невалидный bin без metadata совпадения
            metaFile.delete()
            return false
        }
        runCatching { metaFile.writeText(expected) }
            .onFailure {
                Log.e(TAG, "save: metaFile write failed", it)
                // Если bin сохранился но metadata нет — bin неюзабелен
                binFile.delete()
                return false
            }
        Log.i(TAG, "save: snapshot_base saved (${binFile.length()} bytes), meta='$expected'")
        return true
    }

    /** Удаляет оба файла (bin + meta). Используется при явной инвалидации. */
    fun invalidate() {
        if (binFile.exists()) binFile.delete()
        if (metaFile.exists()) metaFile.delete()
        Log.i(TAG, "invalidate: snapshot_base files deleted")
    }

    private fun buildMeta(systemPrompt: String, modelKey: String, nCtx: Int): String {
        val promptHash = sha256(systemPrompt).take(16) // 16 hex чарактеров достаточно для identity
        return "$META_VERSION|$modelKey|$promptHash|$nCtx|${BuildConfig.VERSION_CODE}"
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

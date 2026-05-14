package com.example.adaptivellm.storage

import android.content.Context
import android.util.Log
import com.example.adaptivellm.BuildConfig
import com.example.adaptivellm.inference.InferenceEngine
import java.io.File

/**
 * Управление per-chat KV cache файлами. Каждый чат на Strategy A может иметь
 * сохранённый KV state `[system + summary + messages]` в файле
 * `filesDir/chats/<chatId>/kv_cache.bin` + sidecar `.meta`.
 *
 * **Жизненный цикл**:
 * 1. Сохранение при выходе из чата (backToChatList / goBackToSetup): caller
 *    вызывает [save], KV сохраняется атомарно с UPDATE chats в БД.
 * 2. Загрузка при входе в чат (selectChatInternal): caller вызывает [load]
 *    перед replay; если success — replay не нужен (KV восстановлен), нужен
 *    только tail re-decode для сообщений добавленных после save (типично 0).
 * 3. Удаление при удалении чата: [invalidate] вызывается из ChatRepository.deleteChat.
 *
 * **Metadata-формат** (одна строка):
 *   `v1|<systemPromptTokens>|<modelKey>|<nCtx>|<versionCode>`
 * - systemPromptTokens — критично; после load восстанавливаем g_system_pos
 *   через [InferenceEngine.setSystemPos] чтобы shift_context работал
 * - modelKey, nCtx, versionCode — invalidation triggers (см. SnapshotBaseManager)
 *
 * **Strategy B** не использует этот менеджер; caller должен сам проверить
 * [InferenceEngine.supportsStatePersist] перед вызовами.
 */
class ChatKvCacheManager(context: Context) {

    companion object {
        private const val TAG = "ChatKvCache"
        private const val META_VERSION = "v1"
    }

    private val chatsRoot: File = File(context.filesDir, "chats")

    init {
        chatsRoot.mkdirs()
    }

    private fun chatDir(chatId: Long): File = File(chatsRoot, chatId.toString())
    private fun binFile(chatId: Long): File = File(chatDir(chatId), "kv_cache.bin")
    private fun metaFile(chatId: Long): File = File(chatDir(chatId), "kv_cache.meta")

    /**
     * Сохраняет текущий engine KV state в файл. Caller гарантирует:
     * - engine содержит state именно этого chatId (после selectChat либо
     *   серии sendMessage в нём)
     * - [systemPromptTokens] — это значение g_system_pos на момент сохранения
     *   (обычно фиксированное со времени snapshot_base.load в начале сессии)
     *
     * @return true если bin+meta написаны успешно
     */
    suspend fun save(
        engine: InferenceEngine,
        chatId: Long,
        systemPromptTokens: Int,
        modelKey: String,
        nCtx: Int,
    ): Boolean {
        chatDir(chatId).mkdirs()
        val rc = engine.stateSaveFile(binFile(chatId).absolutePath)
        if (rc != 0) {
            Log.e(TAG, "save: stateSaveFile failed (rc=$rc) for chatId=$chatId")
            // Если bin не записался — meta тоже не пишем (чтобы load не использовал orphan'а)
            return false
        }
        val meta = buildMeta(systemPromptTokens, modelKey, nCtx)
        runCatching { metaFile(chatId).writeText(meta) }
            .onFailure {
                Log.e(TAG, "save: meta write failed for chatId=$chatId", it)
                binFile(chatId).delete()
                return false
            }
        Log.i(TAG, "save: chatId=$chatId, ${binFile(chatId).length()} bytes, meta='$meta'")
        return true
    }

    /**
     * Загружает KV state в engine. Валидирует metadata; при mismatch удаляет
     * файлы и возвращает null.
     *
     * @return systemPromptTokens из metadata (caller использует для valid state
     *         tracking); null если файлы отсутствуют, metadata mismatch, или
     *         native load failed.
     */
    suspend fun load(
        engine: InferenceEngine,
        chatId: Long,
        modelKey: String,
        nCtx: Int,
    ): Int? {
        val bin = binFile(chatId)
        val meta = metaFile(chatId)
        if (!bin.exists() || !meta.exists()) {
            Log.i(TAG, "load: file(s) missing for chatId=$chatId — bin=${bin.exists()}, meta=${meta.exists()}")
            return null
        }
        val parsed = runCatching { parseMeta(meta.readText().trim()) }.getOrNull()
        if (parsed == null) {
            Log.w(TAG, "load: meta parse failed for chatId=$chatId — invalidating")
            invalidate(chatId)
            return null
        }
        if (parsed.modelKey != modelKey || parsed.nCtx != nCtx ||
            parsed.versionCode != BuildConfig.VERSION_CODE) {
            Log.w(TAG, "load: metadata mismatch for chatId=$chatId " +
                       "(expected model=$modelKey nCtx=$nCtx ver=${BuildConfig.VERSION_CODE}, " +
                       "got model=${parsed.modelKey} nCtx=${parsed.nCtx} ver=${parsed.versionCode}) " +
                       "— invalidating")
            invalidate(chatId)
            return null
        }
        val rc = engine.stateLoadFile(bin.absolutePath)
        if (rc != 0) {
            Log.w(TAG, "load: stateLoadFile failed (rc=$rc) for chatId=$chatId — invalidating")
            invalidate(chatId)
            return null
        }
        // CRITICAL: восстанавливаем g_system_pos чтобы shift_context знал
        // границу system / history частей в загруженном KV.
        engine.setSystemPos(parsed.systemPromptTokens)
        Log.i(TAG, "load: chatId=$chatId loaded (${bin.length()} bytes, " +
                   "system_pos=${parsed.systemPromptTokens}, current_pos=${engine.getCurrentPos()})")
        return parsed.systemPromptTokens
    }

    /** Удаляет bin+meta файлы конкретного чата. Idempotent. */
    fun invalidate(chatId: Long) {
        val bin = binFile(chatId)
        val meta = metaFile(chatId)
        val deletedBin = bin.exists() && bin.delete()
        val deletedMeta = meta.exists() && meta.delete()
        // Удаляем директорию чата если она пустая (косметика, не критично)
        val dir = chatDir(chatId)
        if (dir.exists() && dir.list()?.isEmpty() == true) dir.delete()
        if (deletedBin || deletedMeta) {
            Log.i(TAG, "invalidate: chatId=$chatId, bin_deleted=$deletedBin, meta_deleted=$deletedMeta")
        }
    }

    private data class Meta(
        val systemPromptTokens: Int,
        val modelKey: String,
        val nCtx: Int,
        val versionCode: Int,
    )

    private fun buildMeta(systemPromptTokens: Int, modelKey: String, nCtx: Int): String =
        "$META_VERSION|$systemPromptTokens|$modelKey|$nCtx|${BuildConfig.VERSION_CODE}"

    private fun parseMeta(s: String): Meta? {
        val parts = s.split("|")
        if (parts.size != 5 || parts[0] != META_VERSION) return null
        return Meta(
            systemPromptTokens = parts[1].toIntOrNull() ?: return null,
            modelKey = parts[2],
            nCtx = parts[3].toIntOrNull() ?: return null,
            versionCode = parts[4].toIntOrNull() ?: return null,
        )
    }
}

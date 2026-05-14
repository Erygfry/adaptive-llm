package com.example.adaptivellm.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistent cache для результата [InferenceEngine.statePersistTest]. Тест
 * стоит десятки секунд (на A80 ~52 сек), но verdict стабилен пока модель,
 * backend и build llama.cpp не меняются. Кэш позволяет прогнать тест **один
 * раз** на первый запуск приложения (или после обновления APK / смены модели),
 * затем мгновенно возвращать сохранённое значение.
 *
 * Контракт ключа — [backend] (например "CPU", "Vulkan + CPU") — реализация
 * сама добавляет model+build hash в полный SharedPreferences key.
 */
interface PersistTestCache {
    fun get(backend: String): Boolean?
    fun put(backend: String, value: Boolean)
}

interface InferenceEngine {

    val state: StateFlow<State>

    /**
     * Load model into memory.
     * @param path путь к .gguf файлу
     * @param nGpuLayers -1 = всё на GPU (Vulkan), 0 = всё на CPU
     * @param nCtx размер контекста (6144 / 8192 / 16384). 0 = native default fallback.
     *             Должен соответствовать deviceProfile.ramTier (см. MainViewModel.contextSizeFor).
     *
     * ⚠️ Не использовать напрямую в paired sequence с setSystemPrompt — race с unloadModel
     * может вклиниться между ними. Используй loadModelWithSystemPrompt вместо этого
     * для обычного flow «загрузить модель и сразу выставить prompt».
     */
    suspend fun loadModel(path: String, nGpuLayers: Int = 0, nCtx: Int = 0)
    suspend fun setSystemPrompt(prompt: String)

    /**
     * Атомарно загружает модель и выставляет system prompt в одной операции на
     * llamaDispatcher. Защищает от race condition где другая операция (например
     * unloadModel при back-navigation во время загрузки) может вклиниться между
     * loadModel и setSystemPrompt и привести к SIGSEGV из-за освобождённых
     * g_chat_templates / g_context.
     *
     * Поведение: при ошибке loadModel или setSystemPrompt — выбрасывает RuntimeException,
     * state переходит в Error. State.ModelLoaded выставляется только при успехе обоих.
     */
    suspend fun loadModelWithSystemPrompt(
        path: String,
        nGpuLayers: Int = 0,
        nCtx: Int = 0,
        prompt: String,
        persistTestPath: String? = null,
        persistTestCache: PersistTestCache? = null,
    )

    /**
     * Результат последнего [statePersistTest] прогона. null = тест ещё не проводился
     * (или модель не загружена), true/false = Strategy A / B detected.
     * Сбрасывается в null при unloadModel.
     *
     * Используется callers'ами (Stage 2 chat lifecycle) чтобы решить: писать ли
     * kv_cache.bin при выходе из чата (Strategy A) или skip (Strategy B).
     */
    val supportsStatePersist: StateFlow<Boolean?>

    /**
     * Cooperatively cancels an in-progress model load. Sets a native flag which
     * llama.cpp's progress_callback checks periodically — when next checked, load
     * aborts and `loadModelWithSystemPrompt` throws `CancellationException`.
     *
     * Synchronous, fast (just sets a flag). No-op if no load is in progress.
     * Caller should typically follow up with `unloadModel()` to clean up any
     * partial state (though after cancel there's nothing to clean — model wasn't
     * fully created).
     */
    fun cancelLoading()

    /**
     * Decodes a historical message (already-completed turn) into KV cache without
     * triggering generation. Used during chat switching to replay conversation
     * history so the model has context when user continues the dialog.
     *
     * Каждое сообщение форматируется через template legacy path и декодируется
     * sequentially после [setSystemPrompt]. Порядок вызовов должен совпадать с
     * порядком сообщений в чате: user, assistant, user, assistant, ...
     *
     * @param role "user" или "assistant" — для каждой role своя обёртка в template
     * @param text plain text сообщения (для assistant — clean без `<think>...</think>`)
     * @return 0 на успех; отрицательное значение на ошибку (-1=модель не загружена,
     *         -2=недостаточно места в context, -3=decode failed)
     */
    suspend fun addMessageToHistory(role: String, text: String): Int
    fun sendMessage(message: String, maxTokens: Int = 4096): Flow<String>
    fun getSystemInfo(): String
    fun getBackendName(): String
    fun stopGeneration()
    fun shutdown()
    // 0 = AUTO, 1 = ALWAYS, 2 = NEVER
    fun setThinkingMode(mode: Int)
    fun getCurrentPos(): Int
    fun wasThinkingDisabled(): Boolean
    suspend fun unloadModel()

    /**
     * Сохраняет текущий KV state (sequence 0) в файл. Используется для:
     *   - snapshot_base.bin (bootstrap, после первого setSystemPrompt)
     *   - per-chat kv_cache.bin (chat exit, eviction Этап D.4)
     *
     * @param path абсолютный путь, файл будет перезаписан если существует
     * @return 0 success, иначе error code (1 = model not loaded, 2 = save failed).
     *         Stage 2: caller обрабатывает retry × 2 с экспоненциальным бэкоффом —
     *         см. architecture.md § Обработка ошибок при load/save KV cache.
     */
    suspend fun stateSaveFile(path: String): Int

    /**
     * Загружает KV state из файла, восстанавливая sequence 0 в context. После
     * успешного load g_token_history и g_current_pos синхронизированы с
     * содержимым файла. Caller обычно делает tail re-decode для сообщений
     * после kv_cache_last_message_id.
     *
     * @return 0 success, иначе error code (1 = model not loaded, 2 = read failed
     *         / invalid file). Stage 2: caller обрабатывает retry + fallback на
     *         re-decode при final неудаче.
     */
    suspend fun stateLoadFile(path: String): Int

    /**
     * Выставляет внутренний `g_system_pos` (количество токенов в начале KV
     * cache занятых system prompt'ом). Нужно вызывать после [stateLoadFile]
     * чтобы shift_context / proactive_reset знали границу system части.
     *
     * Примеры использования:
     *   - После load snapshot_base.bin (только system) → setSystemPos(getCurrentPos())
     *   - После load per-chat kv_cache.bin → setSystemPos(savedSystemTokenCount)
     *
     * Без вызова после load: shift_context может удалить system prompt из KV,
     * proactive_reset — re-decode system поверх загруженного state.
     */
    suspend fun setSystemPos(pos: Int)

    /**
     * Self-test для определения поддержки KV state persistence на текущем backend'е.
     * Декодирует тестовый prompt, делает save → clear → load → re-decode probe,
     * сравнивает cosine логитов. Если ≥ 0.999 → backend сохраняет state корректно
     * (Strategy A). Иначе → Strategy B (skip file persistence, full re-decode at
     * chat entry).
     *
     * ⚠️ Wipes current KV state. Caller должен вызвать setSystemPrompt после.
     * В рамках bootstrap flow это делается до setSystemPrompt автоматически
     * через [loadModelWithSystemPrompt] с persistTestPath != null.
     *
     * @param tempPath путь к временному файлу (filesDir/state_persist_test.bin),
     *                 удаляется после теста
     * @return DoubleArray размера 3: [cosine, save_seconds, load_seconds].
     *         cosine = NaN при ошибке (decode/save/load failed).
     */
    suspend fun statePersistTest(tempPath: String): DoubleArray

    sealed class State {
        data object Idle : State()
        data object Initializing : State()
        data object Ready : State()
        data object LoadingModel : State()
        data object ModelLoaded : State()
        data object Processing : State()
        data object Generating : State()
        data class Error(val message: String) : State()
    }
}

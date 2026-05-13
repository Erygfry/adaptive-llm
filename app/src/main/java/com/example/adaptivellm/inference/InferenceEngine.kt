package com.example.adaptivellm.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
    )

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

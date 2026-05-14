package com.example.adaptivellm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.adaptivellm.analytics.AnalyticsLogger
import com.example.adaptivellm.device.DeviceDetector
import com.example.adaptivellm.device.DeviceProfile
import com.example.adaptivellm.device.RamTier
import com.example.adaptivellm.BuildConfig
import com.example.adaptivellm.inference.InferenceEngine
import com.example.adaptivellm.inference.InferenceEngineImpl
import com.example.adaptivellm.inference.PersistTestCache
import com.example.adaptivellm.model.DownloadService
import com.example.adaptivellm.model.DownloadState
import com.example.adaptivellm.model.ModelCatalog
import com.example.adaptivellm.model.ModelDownloader
import com.example.adaptivellm.model.ModelSelector
import com.example.adaptivellm.model.ModelVariant
import com.example.adaptivellm.storage.ChatRepository
import com.example.adaptivellm.storage.MemoryDatabaseHelper
import com.example.adaptivellm.storage.SnapshotBaseManager
import com.example.adaptivellm.update.ApkInstaller
import android.content.Context
import com.example.adaptivellm.update.ReleaseInfo
import com.example.adaptivellm.update.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val thinkingText: String = "",
)

sealed class AppScreen {
    data object Setup : AppScreen()
    data object Download : AppScreen()
    data object ChatList : AppScreen()
    data object Chat : AppScreen()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val SYSTEM_PROMPT =
            "You are a helpful, concise assistant running on a mobile device. " +
            "Keep answers short and to the point unless the user asks for detail. " +
            "Use markdown formatting for structure when helpful. " +
            "Reply in the same language the user writes in."

        private const val PREFS_NAME = "adaptive_llm_prefs"
        private const val KEY_VULKAN_CRASHED = "vulkan_crashed"
        // Minimum RAM to attempt Vulkan (model + KV cache + system overhead)
        private const val MIN_RAM_FOR_VULKAN_MB = 8_000L

        /**
         * Максимум одновременных чатов. Лимит готовит почву под Stage 4+
         * KV cache persistence — каждый чат будет хранить kv_cache.bin размером
         * ~100-200 MB (см. ChatRepository / architecture.md). При >10 чатах
         * disk usage пробивает разумные 1-2 GB, особенно на mid-range устройствах.
         * Пользователь видит счётчик X/10 в TopAppBar и кнопку "+" disabled при 10/10.
         */
        const val MAX_CHATS = 10

        /**
         * Имя файла под temp persist test (Stage 2). Stored в filesDir, удаляется
         * нативным кодом после теста.
         */
        private const val PERSIST_TEST_FILE = "state_persist_test.bin"

        /**
         * Параметры polling'а в [ensureModelLoaded]. На A80 raw prefill ~6 t/s,
         * а persist test декодит 238 токенов (~40 сек). Плюс модель load (~10 сек)
         * + setSystemPrompt (~5 сек) + overhead. Worst case = ~60 сек. Берём
         * запас 3× = 180 сек чтобы покрыть и более медленные устройства / диски.
         * Polling exit'ит мгновенно при ModelLoaded — overhead только когда
         * реально что-то медленное идёт.
         */
        private const val LOAD_POLL_ITERATIONS = 1800
        private const val LOAD_POLL_TICK_MS = 100L
    }

    /** Абсолютный путь к temp файлу persist test'а (Stage 2). */
    private val persistTestPath: String
        get() = File(getApplication<Application>().filesDir, PERSIST_TEST_FILE).absolutePath

    /**
     * SharedPreferences-кэш для результата persist test'а. Ключ включает модель
     * (имя файла + размер) + APK versionCode (proxy для llama.cpp build hash).
     * Backend добавляется при get/put чтобы один и тот же чат на разных
     * backend'ах кэшировался отдельно (Vulkan toggle / CPU fallback).
     */
    private fun persistTestCacheFor(model: ModelVariant): PersistTestCache {
        val prefs = getApplication<Application>()
            .getSharedPreferences("persist_test_cache", Context.MODE_PRIVATE)
        // model.fileName + model.fileSizeMb + appVersionCode = (модель, build) identity.
        // При обновлении APK versionCode меняется → все cache keys инвалидируются.
        val modelKey = "${model.fileName}_${model.fileSizeMb}MB_v${BuildConfig.VERSION_CODE}"
        return object : PersistTestCache {
            override fun get(backend: String): Boolean? =
                prefs.getString("${modelKey}_${backend}", null)?.toBooleanStrictOrNull()
            override fun put(backend: String, value: Boolean) {
                prefs.edit().putString("${modelKey}_${backend}", value.toString()).apply()
            }
        }
    }

    /** Variant для cache key'а в loadModelFromPath (нет ModelVariant, берём файл). */
    private fun persistTestCacheForFile(path: String): PersistTestCache {
        val sizeMb = (File(path).length() / (1024 * 1024)).toInt()
        val modelKey = modelKeyForFile(path, sizeMb)
        val prefs = getApplication<Application>()
            .getSharedPreferences("persist_test_cache", Context.MODE_PRIVATE)
        return object : PersistTestCache {
            override fun get(backend: String): Boolean? =
                prefs.getString("${modelKey}_${backend}", null)?.toBooleanStrictOrNull()
            override fun put(backend: String, value: Boolean) {
                prefs.edit().putString("${modelKey}_${backend}", value.toString()).apply()
            }
        }
    }

    /**
     * Идентификатор модели для cache keys (persist test, snapshot_base) — должен
     * меняться при смене модели или APK update. Не включает backend (тот добавляется
     * отдельно где нужно).
     */
    private fun modelKeyForVariant(variant: ModelVariant): String =
        "${variant.fileName}_${variant.fileSizeMb}MB"

    private fun modelKeyForFile(path: String, sizeMb: Int): String =
        "${File(path).name}_${sizeMb}MB"

    /**
     * Возвращает modelKey текущей загруженной модели — приоритет local gguf
     * (если выставлен) над selectedModel (downloaded variant).
     */
    private fun currentModelKey(): String {
        val localPath = _localGgufPath
        return if (localPath != null) {
            val sizeMb = (File(localPath).length() / (1024 * 1024)).toInt()
            modelKeyForFile(localPath, sizeMb)
        } else {
            modelKeyForVariant(_selectedModel.value)
        }
    }

    /**
     * После успешного loadModelWithSystemPrompt — KV в state [system_only].
     * Сохраняем этот state в snapshot_base.bin (Strategy A only). Невалидные
     * существующие snapshot'ы автоматически перезатираются.
     */
    private suspend fun ensureSnapshotBaseSaved(modelKey: String) {
        if (engine.supportsStatePersist.value != true) return
        val saved = snapshotBase.save(
            engine = engine,
            systemPrompt = SYSTEM_PROMPT,
            modelKey = modelKey,
            nCtx = nCtx,
        )
        if (!saved) {
            android.util.Log.w("MainViewModel", "snapshot_base save failed — chat switching will use re-decode")
        }
    }

    val engine: InferenceEngine = InferenceEngineImpl.getInstance(application)
    private val snapshotBase = SnapshotBaseManager(application)

    private val downloader = ModelDownloader(application)
    private val analyticsLogger = AnalyticsLogger(application)

    // Device profile
    val deviceProfile: DeviceProfile = DeviceDetector.detect(application)
    val recommendedModel: ModelVariant = ModelSelector.recommend(deviceProfile)
    val compatibleModels = ModelSelector.compatibleModels(deviceProfile)
    /** Context size that engine will be (or is) loaded with — based on device's RAM tier. */
    val nCtx: Int = contextSizeFor(deviceProfile.ramTier)

    // Set of filenames that are already downloaded (reactive)
    private val _downloadedModels = MutableStateFlow(
        ModelCatalog.variants
            .filter { downloader.isDownloaded(it) }
            .map { it.fileName }
            .toSet()
    )
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels.asStateFlow()

    // Navigation
    private val _screen = MutableStateFlow<AppScreen>(AppScreen.Setup)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    // Selected model
    private val _selectedModel = MutableStateFlow(recommendedModel)
    val selectedModel: StateFlow<ModelVariant> = _selectedModel.asStateFlow()

    // Download state
    private val _downloadState = MutableStateFlow<DownloadState?>(null)
    val downloadState: StateFlow<DownloadState?> = _downloadState.asStateFlow()

    // Chat
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /**
     * Флаг "пользователь нажал stop". Ставится в stopGeneration / backToChatList /
     * goBackToSetup, сбрасывается в начале каждого sendMessage. Используется в
     * post-generation блоке чтобы отличить "cancel during thinking → пустой ответ"
     * от "model wanted to reply that way" — в первом случае подставляется
     * placeholder вместо пустого bubble'а.
     */
    @Volatile
    private var cancelRequested = false

    private val _thinkingMode = MutableStateFlow(0)
    val thinkingMode: StateFlow<Int> = _thinkingMode.asStateFlow()

    private val _tokensPerSecond = MutableStateFlow(0f)
    val tokensPerSecond: StateFlow<Float> = _tokensPerSecond.asStateFlow()

    private val _backendInfo = MutableStateFlow("")
    val backendInfo: StateFlow<String> = _backendInfo.asStateFlow()

    private val _totalTokens = MutableStateFlow(0)
    val totalTokens: StateFlow<Int> = _totalTokens.asStateFlow()

    // Actual loaded model name (not necessarily the recommended one)
    private val _loadedModelName = MutableStateFlow("")
    val loadedModelName: StateFlow<String> = _loadedModelName.asStateFlow()

    /**
     * Путь к локально-pushed .gguf файлу (через adb, для dev/testing). Если задан —
     * приоритетнее чем downloaded variants при ensureModelLoaded.
     */
    private var _localGgufPath: String? = null

    // Chat list (Stage 1 — multi-chat). Текущий выбранный чат через _currentChatId;
    // когда null — пользователь на ChatList screen'е. _messages выше отражает
    // сообщения именно текущего чата.
    private val _chats = MutableStateFlow<List<ChatRepository.ChatInfo>>(emptyList())
    val chats: StateFlow<List<ChatRepository.ChatInfo>> = _chats.asStateFlow()

    private val _currentChatId = MutableStateFlow<Long?>(null)
    val currentChatId: StateFlow<Long?> = _currentChatId.asStateFlow()

    /**
     * Когда true — идёт переключение чата (replay истории в engine). Блокирует
     * UI ввод чтобы пользователь не нажал send пока KV не готов.
     */
    private val _isSwitchingChat = MutableStateFlow(false)
    val isSwitchingChat: StateFlow<Boolean> = _isSwitchingChat.asStateFlow()

    /**
     * Job текущего [selectChatInternal] — нужен чтобы пользователь мог прервать
     * долгий replay через back-button (см. [backToChatList]). Кооперативная
     * отмена: cancel поднимает CancellationException на ближайшем suspend point
     * (между addMessageToHistory'ами или внутри ensureModelLoaded's delay loop).
     * Native call в процессе выполнения дожмётся до конца (≤1 сек обычно).
     */
    private var chatSwitchJob: kotlinx.coroutines.Job? = null

    // Update
    private val _availableUpdate = MutableStateFlow<ReleaseInfo?>(null)
    val availableUpdate: StateFlow<ReleaseInfo?> = _availableUpdate.asStateFlow()

    // null = idle, true = checking, false = checked (no update)
    private val _updateCheckState = MutableStateFlow<Boolean?>(null)
    val updateCheckState: StateFlow<Boolean?> = _updateCheckState.asStateFlow()

    init {
        // Initialize memory database (Stage 0 — schema + sqlite-vec ready). Запускается
        // независимо от chat flow. Если упадёт — log + ничего не делаем; chat
        // продолжит работать без памяти (degraded mode).
        // После успешной init загружаем список чатов в _chats.
        viewModelScope.launch {
            try {
                MemoryDatabaseHelper.initialize(application)
                refreshChats()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "MemoryDatabase init failed — memory features disabled", e)
            }
        }

        // Find the best downloaded model from the catalog (respects Vulkan, etc.)
        val downloadedVariant = ModelCatalog.variants
            .filter { downloader.isDownloaded(it) }
            .sortedWith(
                compareByDescending<ModelVariant> { it.parameterSize }
                    .thenByDescending { it.quantization }
            )
            .firstOrNull()

        if (downloadedVariant != null) {
            // Stage 1: после init идём на ChatList БЕЗ загрузки модели — пользователь
            // может зайти просто чтобы удалить старые чаты или посмотреть список.
            // Модель загружается lazy при первом входе в чат (см. ensureModelLoaded).
            _screen.value = AppScreen.ChatList
            _selectedModel.value = downloadedVariant
        } else {
            // Fallback: check for any .gguf pushed manually via adb (dev/testing only)
            val localGguf = findLocalGguf(application)
            if (localGguf != null) {
                _screen.value = AppScreen.ChatList
                _localGgufPath = localGguf
            }
        }

        analyticsLogger.logSessionStart()

        // Check for app updates on startup (silent — no "up to date" feedback)
        checkForUpdates(silent = true)
    }

    /**
     * Look for any .gguf file already present in the app's files directory.
     * Useful for testing via: adb push model.gguf /data/local/tmp/
     * then: adb shell "run-as com.example.adaptivellm cp /data/local/tmp/model.gguf ./files/"
     */
    private fun findLocalGguf(application: Application): String? {
        val filesDir = application.filesDir
        return filesDir.listFiles()
            ?.filter { it.name.endsWith(".gguf") && it.length() > 1_000_000 }
            ?.maxByOrNull { it.length() }
            ?.absolutePath
    }

    /**
     * Load model directly from an absolute path (for manual testing).
     */
    fun loadModelFromPath(path: String, nGpuLayers: Int = 0) {
        val fileName = File(path).name
        _loadedModelName.value = ModelCatalog.variants
            .firstOrNull { it.fileName == fileName }?.displayName
            ?: fileName.removeSuffix(".gguf")
        viewModelScope.launch {
            try {
                // Атомарная загрузка — закрывает race с unloadModel при back-navigation.
                engine.loadModelWithSystemPrompt(
                    path = path,
                    nGpuLayers = nGpuLayers,
                    nCtx = contextSizeFor(deviceProfile.ramTier),
                    prompt = SYSTEM_PROMPT,
                    persistTestPath = persistTestPath,
                    persistTestCache = persistTestCacheForFile(path),
                )
                val sizeMb = (File(path).length() / (1024 * 1024)).toInt()
                ensureSnapshotBaseSaved(modelKeyForFile(path, sizeMb))
                _backendInfo.value = engine.getBackendName()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // User cancelled load via goBackToSetup — silent, no UI error.
                throw e
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    "Error loading model: ${e.message}", isUser = false
                )
            }
        }
    }

    fun selectModel(variant: ModelVariant) {
        _selectedModel.value = variant
    }

    private var downloadCollectJob: kotlinx.coroutines.Job? = null

    fun startDownload() {
        val model = _selectedModel.value
        if (downloader.isDownloaded(model)) {
            // Stage 1: после выбора модели идём на ChatList (а не сразу в Chat).
            // Модель НЕ грузится здесь — lazy load при первом входе в чат.
            _screen.value = AppScreen.ChatList
            return
        }

        _screen.value = AppScreen.Download
        _downloadState.value = null

        // Launch foreground service for background download
        DownloadService.start(getApplication(), model)

        // Observe service state
        downloadCollectJob?.cancel()
        downloadCollectJob = viewModelScope.launch {
            DownloadService.state.collect { state ->
                _downloadState.value = state
                if (state is DownloadState.Complete) {
                    refreshDownloadedModels()
                    // После завершения download → ChatList (не Chat). Загрузка модели
                    // отложена до первого реального входа в чат.
                    _screen.value = AppScreen.ChatList
                }
            }
        }
    }

    private fun shouldUseVulkan(): Boolean {
        if (!deviceProfile.hasVulkan) return false
        if (deviceProfile.totalRamMb < MIN_RAM_FOR_VULKAN_MB) return false
        // Check if Vulkan crashed on a previous launch
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_VULKAN_CRASHED, false)) return false
        return true
    }

    /**
     * Размер контекста по RAM tier (см. architecture.md, § Параметры по группам контекста).
     * Тиры откалиброваны на reported usable RAM, не nominal — соответствие см.
     * DeviceProfile.kt. Размеры kv_cache.bin (q4_0 Qwen3.5 hybrid):
     *   - MID (~5 GB reported, 6 GB nominal)       → 6144 ctx, ~104 MB KV
     *   - UPPER_MID (~7 GB reported, 8 GB nominal) → 8192 ctx, ~122 MB KV
     *   - HIGH (~10+ GB reported, 12+ GB nominal)  → 16384 ctx, ~195 MB KV
     *   - LOW / VERY_LOW                           → 6144 floor (модели всё равно
     *     не грузятся через ModelCatalog.minRamMb, но валидный fallback на всякий случай)
     */
    private fun contextSizeFor(tier: RamTier): Int = when (tier) {
        RamTier.HIGH -> 16384
        RamTier.UPPER_MID -> 8192
        RamTier.MID -> 6144
        RamTier.LOW, RamTier.VERY_LOW -> 6144
    }

    private fun loadModel(variant: ModelVariant) {
        _loadedModelName.value = variant.displayName
        val useVulkan = shouldUseVulkan()

        viewModelScope.launch {
            val path = downloader.getLocalPath(variant)
            val success = tryLoadModel(path, variant, useVulkan)
            if (!success && useVulkan) {
                // Vulkan failed — retry on CPU
                val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_VULKAN_CRASHED, true).apply()
                tryLoadModel(path, variant, useVulkan = false)
            }
        }
    }

    /**
     * Гарантирует что модель загружена в engine. Если уже загружена — мгновенно
     * возвращает. Иначе запускает загрузку. Используется как prerequisite перед
     * операциями требующими engine (selectChat / createNewChat / sendMessage).
     *
     * Stage 1: lazy load — модель не грузится при init'е если пользователь сразу
     * заходит на ChatList (например для удаления чатов). Загружается только при
     * первом реальном входе в чат.
     *
     * Возвращает true если модель готова, false если попытка загрузки провалилась.
     */
    private suspend fun ensureModelLoaded(): Boolean {
        // Если уже loaded — мгновенно возвращаем
        if (engine.state.value is InferenceEngine.State.ModelLoaded) {
            return true
        }
        // Если уже идёт загрузка — ждём её завершения
        if (engine.state.value is InferenceEngine.State.LoadingModel ||
            engine.state.value is InferenceEngine.State.Processing) {
            // Простой polling — в текущей архитектуре loadModel не возвращает Job который
            // можно join'ить, проще подождать state change.
            for (i in 0 until LOAD_POLL_ITERATIONS) {
                kotlinx.coroutines.delay(LOAD_POLL_TICK_MS)
                if (engine.state.value is InferenceEngine.State.ModelLoaded) return true
                if (engine.state.value is InferenceEngine.State.Error) return false
                if (engine.state.value is InferenceEngine.State.Ready) break // загрузка отменена
            }
        }
        // Триггерим загрузку
        val localPath = _localGgufPath
        if (localPath != null) {
            loadModelFromPath(localPath)
        } else {
            val variant = _selectedModel.value
            loadModel(variant)
        }
        // Ждём результат
        for (i in 0 until LOAD_POLL_ITERATIONS) {
            kotlinx.coroutines.delay(LOAD_POLL_TICK_MS)
            val state = engine.state.value
            if (state is InferenceEngine.State.ModelLoaded) return true
            if (state is InferenceEngine.State.Error) return false
        }
        return false
    }

    private suspend fun tryLoadModel(path: String, variant: ModelVariant, useVulkan: Boolean): Boolean {
        return try {
            if (useVulkan) {
                val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_VULKAN_CRASHED, true).apply()
            }
            // Атомарная загрузка модели + system prompt в одной операции на llamaDispatcher.
            // Защищает от race condition с unloadModel при back-navigation во время
            // загрузки модели — без этого SIGSEGV в common_chat_templates_was_explicit
            // (см. crash log от 2026-05-12 19:57: g_chat_templates освобождён в unload
            // между loadModel и setSystemPrompt, потом setSystemPrompt падает).
            engine.loadModelWithSystemPrompt(
                path = path,
                nGpuLayers = if (useVulkan) -1 else 0,
                nCtx = contextSizeFor(deviceProfile.ramTier),
                prompt = SYSTEM_PROMPT,
                persistTestPath = persistTestPath,
                persistTestCache = persistTestCacheFor(variant),
            )
            if (useVulkan) {
                val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_VULKAN_CRASHED, false).apply()
            }
            // KV сейчас в [system_only] — момент чтобы сохранить snapshot_base.
            // На Strategy B no-op; на A — pisat/skip если файл уже валиден.
            ensureSnapshotBaseSaved(modelKeyForVariant(variant))
            _backendInfo.value = engine.getBackendName()
            analyticsLogger.logModelLoaded(
                modelName = variant.displayName,
                modelSizeMb = variant.fileSizeMb,
                backend = engine.getBackendName(),
                nThreads = deviceProfile.cpuCores,
                nBigCores = deviceProfile.cpuCores,
                quantization = variant.quantization,
            )
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // User cancelled load via goBackToSetup. Не считаем ошибкой — ни UI message,
            // ни analytics error. КРИТИЧНО: если Vulkan crash flag был выставлен
            // pre-emptively выше (для real-crash detection), нужно его сбросить —
            // cancel ≠ crash, Vulkan по-прежнему работоспособен. Без этого следующий
            // запуск откажется от Vulkan навсегда.
            if (useVulkan) {
                val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_VULKAN_CRASHED, false).apply()
            }
            throw e
        } catch (e: Exception) {
            analyticsLogger.logModelLoadError(
                modelName = variant.displayName,
                error = e.message ?: "unknown",
                backend = if (useVulkan) "Vulkan" else "CPU",
            )
            _messages.value = _messages.value + ChatMessage(
                "Error loading model: ${e.message}", isUser = false
            )
            false
        }
    }

    fun sendMessage(text: String) {
        val chatId = _currentChatId.value
        if (chatId == null) {
            android.util.Log.w("MainViewModel", "sendMessage: no current chat — ignoring")
            return
        }
        if (text.isBlank() || _isGenerating.value || _isSwitchingChat.value) return

        _messages.value = _messages.value + ChatMessage(text, isUser = true)
        _isGenerating.value = true
        cancelRequested = false

        viewModelScope.launch {
            // Persist user message в БД сразу (до начала generation). Это значит
            // что даже если пользователь cancel'нёт mid-generation — его reply
            // сохранён (он видит его в UI, и в DB совпадает). Assistant сохраняется
            // только после успешного завершения generation.
            val isFirstMessage = _messages.value.count { it.isUser } == 1
            try {
                val userMsgId = ChatRepository.addMessage(chatId, "user", text)
                android.util.Log.i("MainViewModel",
                    "Persisted user message: chatId=$chatId, msgId=$userMsgId, len=${text.length}, " +
                    "isFirstMsg=$isFirstMessage")
                // Если это первое user сообщение в чате — сделаем auto-title из него
                if (isFirstMessage) {
                    val title = text.take(50).trim().ifBlank { "Чат" }
                    ChatRepository.updateTitle(chatId, title)
                    refreshChats()
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Failed to persist user message", e)
                // Продолжаем — user message в памяти, generation работает; degraded mode.
            }

            val responseBuilder = StringBuilder()
            var tokenCount = 0
            val startTime = System.currentTimeMillis()
            val batteryBefore = analyticsLogger.getBatteryPercent()

            _messages.value = _messages.value + ChatMessage("", isUser = false)

            val thinkRegex = Regex("<think>([\\s\\S]*?)</think>\\s*")
            // Check if thinking was auto-disabled due to low context
            val thinkingActive = _thinkingMode.value == 1 && !engine.wasThinkingDisabled()

            var finalDisplayText = ""
            engine.sendMessage(text).collect { token ->
                responseBuilder.append(token)
                tokenCount++

                val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                if (elapsed > 0) {
                    _tokensPerSecond.value = tokenCount / elapsed
                }

                // Split thinking from visible response
                val raw = responseBuilder.toString()
                val thinkMatch = thinkRegex.find(raw)
                val thinkContent: String
                val displayText: String
                if (thinkMatch != null) {
                    // AUTO mode: model included both <think>...</think>
                    thinkContent = thinkMatch.groupValues[1].trim()
                    displayText = raw.replace(thinkRegex, "").trim()
                } else if (raw.contains("<think>")) {
                    // AUTO mode: <think> started but not yet closed
                    thinkContent = raw.substringAfter("<think>").trim()
                    displayText = ""
                } else if (thinkingActive && raw.contains("</think>")) {
                    // ALWAYS mode: opening <think> is in the prompt, not in generated text
                    thinkContent = raw.substringBefore("</think>").trim()
                    displayText = raw.substringAfter("</think>").trim()
                } else if (thinkingActive && !raw.contains("</think>")) {
                    // ALWAYS mode: still generating thinking (no </think> yet)
                    thinkContent = raw.trim()
                    displayText = ""
                } else {
                    thinkContent = ""
                    displayText = raw.trim()
                }
                finalDisplayText = displayText

                val updated = _messages.value.toMutableList()
                updated[updated.lastIndex] = ChatMessage(
                    text = displayText,
                    isUser = false,
                    thinkingText = thinkContent,
                )
                _messages.value = updated
            }

            _isGenerating.value = false
            _totalTokens.value = engine.getCurrentPos()

            // Cancel-during-thinking placeholder: если пользователь нажал stop, и
            // финальный visible-текст пустой (модель сгенерировала только thinking
            // без любого внешнего ответа), показываем заглушку в UI и сохраняем её
            // как assistant message. Иначе пользователь увидит пустой bubble после
            // re-open чата — confusing.
            if (cancelRequested && finalDisplayText.isBlank()) {
                finalDisplayText = "(сообщение отменено)"
                val updated = _messages.value.toMutableList()
                if (updated.isNotEmpty() && !updated.last().isUser) {
                    updated[updated.lastIndex] = ChatMessage(
                        text = finalDisplayText,
                        isUser = false,
                        thinkingText = updated.last().thinkingText,
                    )
                    _messages.value = updated
                }
            }

            // Persist assistant message (только clean text без <think>). На cancel
            // вытяжка finalDisplayText до точки прерывания, на успех — полный ответ.
            // Если ничего не сгенерилось и cancel не был запрошен (т.е. модель просто
            // выдала пустой ответ) — не сохраняем (пустые ответы бесполезны).
            if (finalDisplayText.isNotBlank()) {
                try {
                    val assistantMsgId = ChatRepository.addMessage(chatId, "assistant", finalDisplayText)
                    android.util.Log.i("MainViewModel",
                        "Persisted assistant message: chatId=$chatId, msgId=$assistantMsgId, " +
                        "len=${finalDisplayText.length}, tokens=$tokenCount, cancelled=$cancelRequested")
                } catch (e: Exception) {
                    android.util.Log.w("MainViewModel", "Failed to persist assistant message", e)
                }
            } else {
                android.util.Log.i("MainViewModel",
                    "Assistant message NOT persisted (blank finalDisplayText, cancelRequested=$cancelRequested)")
            }
            refreshChats() // обновляет last_active_at в UI

            // Restore thinking mode if it was auto-disabled due to low context
            if (engine.wasThinkingDisabled()) {
                engine.setThinkingMode(_thinkingMode.value)
            }

            // Log analytics
            val durationMs = System.currentTimeMillis() - startTime
            analyticsLogger.logGenerationComplete(
                modelName = _loadedModelName.value,
                tokensPerSecond = _tokensPerSecond.value,
                tokenCount = tokenCount,
                durationMs = durationMs,
                backend = _backendInfo.value,
                thinkingMode = _thinkingMode.value,
            )
            analyticsLogger.logBatteryDrain(
                modelName = _loadedModelName.value,
                batteryBefore = batteryBefore,
                batteryAfter = analyticsLogger.getBatteryPercent(),
                tokenCount = tokenCount,
            )
        }
    }

    fun goBackToSetup() {
        viewModelScope.launch {
            // Order matters: оба cancel-сигнала ставятся ПЕРЕД unloadModel, чтобы:
            //   - Если идёт model load → progress_callback видит флаг → abort в течение
            //     миллисекунд → llamaDispatcher освобождается → unloadModel запускается
            //     почти сразу (вместо 5-10 сек ожидания полной загрузки модели).
            //   - Если идёт generation → cancelGeneration флаг видит loop в sendMessage
            //     flow → выход + cancelAndCleanKV → unloadModel запускается дальше.
            cancelRequested = true
            engine.cancelLoading()        // flag для model load
            engine.stopGeneration()       // flag для generation
            chatSwitchJob?.cancel()       // прерываем chat switch если идёт
            chatSwitchJob = null
            engine.unloadModel()          // ждёт завершения текущей операции на llamaDispatcher
            _messages.value = emptyList()
            _currentChatId.value = null
            _tokensPerSecond.value = 0f
            _totalTokens.value = 0
            _backendInfo.value = ""
            _loadedModelName.value = ""
            refreshDownloadedModels()
            _screen.value = AppScreen.Setup
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Chat management (Stage 1)
    // ─────────────────────────────────────────────────────────────────────────

    /** Обновляет список чатов из БД. Вызывается после mutations и при init. */
    private suspend fun refreshChats() {
        try {
            _chats.value = ChatRepository.getChats()
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "refreshChats failed", e)
            _chats.value = emptyList()
        }
    }

    /**
     * Создаёт новый чат и сразу открывает его. После init engine'а title NULL —
     * заполнится в [sendMessage] при первом user message.
     */
    fun createNewChat() {
        if (_chats.value.size >= MAX_CHATS) {
            android.util.Log.w("MainViewModel",
                "createNewChat ignored — already at MAX_CHATS=$MAX_CHATS limit")
            return
        }
        chatSwitchJob?.cancel()
        chatSwitchJob = viewModelScope.launch {
            try {
                val chatId = ChatRepository.createChat(title = null)
                refreshChats()
                selectChatInternal(chatId, isNew = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "createNewChat failed", e)
            }
        }
    }

    /**
     * Открывает существующий чат: загружает messages из БД, replay'ит их в KV.
     * Стоимость replay'а = prefill всех сообщений чата (1-10 сек в зависимости
     * от истории и backend'а).
     */
    fun selectChat(chatId: Long) {
        chatSwitchJob?.cancel()
        chatSwitchJob = viewModelScope.launch {
            selectChatInternal(chatId, isNew = false)
        }
    }

    private suspend fun selectChatInternal(chatId: Long, isNew: Boolean) {
        _isSwitchingChat.value = true
        // Транзиционно показываем Chat screen с пустыми сообщениями (или старыми)
        // — пользователь сразу видит «Загрузка чата...» вместо застрявшего ChatList'а.
        _screen.value = AppScreen.Chat
        try {
            // Lazy load: грузим модель только при первом реальном входе в чат
            // (а не при init'е ChatList). Если model уже loaded — это no-op.
            val modelReady = ensureModelLoaded()
            if (!modelReady) {
                android.util.Log.e("MainViewModel", "selectChat: model load failed/cancelled")
                _screen.value = AppScreen.ChatList
                return
            }

            // Отменяем generation если идёт (после переключения это будет уже не наш ответ)
            engine.stopGeneration()

            // Загружаем messages из БД
            val rows = if (isNew) emptyList() else ChatRepository.getMessages(chatId)
            android.util.Log.i("MainViewModel", "selectChat: chatId=$chatId, isNew=$isNew, rows=${rows.size}")
            _messages.value = rows.map {
                ChatMessage(
                    text = it.content,
                    isUser = it.role == "user",
                    thinkingText = "", // Stage 1: thinking не персистится отдельно, оставляем пустым
                )
            }

            // Engine: reset to [system_only] state. На Strategy A пробуем
            // загрузить snapshot_base.bin (~50 ms) — radикально быстрее чем
            // re-decode system prompt'а (~2-3 сек на A80). Fallback на
            // setSystemPrompt если snapshot невалиден / отсутствует / load failed.
            val snapshotLoaded = if (engine.supportsStatePersist.value == true) {
                snapshotBase.load(
                    engine = engine,
                    systemPrompt = SYSTEM_PROMPT,
                    modelKey = currentModelKey(),
                    nCtx = nCtx,
                )
            } else false
            if (!snapshotLoaded) {
                engine.setSystemPrompt(SYSTEM_PROMPT)
                // На Strategy A snapshot отсутствовал — создадим теперь, в фоне будущих
                // переключений сэкономит время. Если предыдущая ensureSnapshotBaseSaved
                // уже создала — это no-op (idempotent).
                if (engine.supportsStatePersist.value == true) {
                    snapshotBase.save(engine, SYSTEM_PROMPT, currentModelKey(), nCtx)
                }
            }

            // Replay истории в KV — каждое сообщение через addMessageToHistory.
            // На ошибке (например context overflow) — прерываем replay, оставляем UI
            // как есть. Модель будет иметь truncated context, пользователь увидит
            // всю историю в UI но модель «помнит» только то что влезло.
            for (row in rows) {
                val rc = engine.addMessageToHistory(row.role, row.content)
                if (rc != 0) {
                    android.util.Log.w(
                        "MainViewModel",
                        "Chat replay stopped at message ${row.id} (role=${row.role}, rc=$rc) — KV state truncated"
                    )
                    break
                }
            }

            _currentChatId.value = chatId
            _totalTokens.value = engine.getCurrentPos()
            ChatRepository.touchChat(chatId)
            refreshChats()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Пользователь нажал back во время switch'а — откатываем UI на ChatList.
            // KV state в engine остаётся partial (часть сообщений decoded), но это OK:
            // следующий selectChat сделает setSystemPrompt → reset_chat_state → wipe.
            android.util.Log.i("MainViewModel", "selectChat cancelled by user for id=$chatId")
            _screen.value = AppScreen.ChatList
            _messages.value = emptyList()
            _currentChatId.value = null
            throw e  // rethrow обязателен — structured concurrency
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "selectChat failed for id=$chatId", e)
            _screen.value = AppScreen.ChatList
        } finally {
            _isSwitchingChat.value = false
        }
    }

    /** Удаляет чат (CASCADE: messages, summary, локальные facts). */
    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            try {
                ChatRepository.deleteChat(chatId)
                // Если удалили текущий открытый чат — закрываем его в UI тоже
                if (_currentChatId.value == chatId) {
                    _currentChatId.value = null
                    _messages.value = emptyList()
                    _screen.value = AppScreen.ChatList
                }
                refreshChats()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "deleteChat failed for id=$chatId", e)
            }
        }
    }

    /** Из ChatScreen возвращает на ChatList без unload'а модели. */
    fun backToChatList() {
        // Останавливаем active generation если есть — после возврата контекст не наш
        cancelRequested = true
        engine.stopGeneration()
        // Если идёт chat switch (ensureModelLoaded или replay) — прерываем его. Cancel
        // дойдёт до ближайшего suspend point (между addMessageToHistory'ами или
        // внутри ensureModelLoaded's delay loop) и selectChatInternal'ы CancellationException
        // handler сделает UI cleanup. engine.cancelLoading нужен на случай если model
        // ещё грузится — без него native load доработает до конца до выхода cancel'а.
        engine.cancelLoading()
        chatSwitchJob?.cancel()
        chatSwitchJob = null
        _currentChatId.value = null
        _messages.value = emptyList()
        _screen.value = AppScreen.ChatList
    }


    fun deleteModel(variant: ModelVariant) {
        val modelFile = File(downloader.getLocalPath(variant))
        if (modelFile.exists()) modelFile.delete()
        val partFile = File(modelFile.absolutePath + ".part")
        if (partFile.exists()) partFile.delete()
        // Also delete mmproj if present
        val mmProj = variant.mmProjFileName
        if (mmProj != null) {
            val app = getApplication<Application>()
            val mmFile = File(app.filesDir, mmProj)
            if (mmFile.exists()) mmFile.delete()
        }
        refreshDownloadedModels()
    }

    private fun refreshDownloadedModels() {
        _downloadedModels.value = ModelCatalog.variants
            .filter { downloader.isDownloaded(it) }
            .map { it.fileName }
            .toSet()
    }

    fun navigateTo(screen: AppScreen) {
        _screen.value = screen
    }

    override fun onCleared() {
        analyticsLogger.logSessionEnd()
        engine.shutdown()
        // shutdown DB через runBlocking — onCleared не suspend и мы должны успеть
        // закрыть DB файл (WAL checkpoint, finalize statements) до завершения процесса.
        // Это короткая операция (миллисекунды). Если повиснет — process kill всё
        // равно очистит file lock.
        try {
            kotlinx.coroutines.runBlocking { MemoryDatabaseHelper.shutdown() }
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "MemoryDatabase shutdown failed", e)
        }
        super.onCleared()
    }

    fun stopGeneration() {
        cancelRequested = true
        engine.stopGeneration()
    }

    fun setThinkingMode(mode: Int) {
        _thinkingMode.value = mode
        engine.setThinkingMode(mode)
    }

    fun checkForUpdates(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _updateCheckState.value = true // checking
            val result = UpdateChecker.check(getApplication())
            _availableUpdate.value = result
            if (!silent) _updateCheckState.value = if (result == null) false else null
        }
    }

    fun installUpdate() {
        val update = _availableUpdate.value ?: return
        viewModelScope.launch {
            ApkInstaller.downloadAndInstall(getApplication(), update.apkUrl)
        }
    }

    fun dismissUpdate() {
        _availableUpdate.value = null
    }

}

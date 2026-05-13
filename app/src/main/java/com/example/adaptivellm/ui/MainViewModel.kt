package com.example.adaptivellm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.adaptivellm.analytics.AnalyticsLogger
import com.example.adaptivellm.device.DeviceDetector
import com.example.adaptivellm.device.DeviceProfile
import com.example.adaptivellm.device.RamTier
import com.example.adaptivellm.inference.InferenceEngine
import com.example.adaptivellm.inference.InferenceEngineImpl
import com.example.adaptivellm.model.DownloadService
import com.example.adaptivellm.model.DownloadState
import com.example.adaptivellm.model.ModelCatalog
import com.example.adaptivellm.model.ModelDownloader
import com.example.adaptivellm.model.ModelSelector
import com.example.adaptivellm.model.ModelVariant
import com.example.adaptivellm.storage.MemoryDatabaseHelper
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
    }

    val engine: InferenceEngine = InferenceEngineImpl.getInstance(application)

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

    // Update
    private val _availableUpdate = MutableStateFlow<ReleaseInfo?>(null)
    val availableUpdate: StateFlow<ReleaseInfo?> = _availableUpdate.asStateFlow()

    // null = idle, true = checking, false = checked (no update)
    private val _updateCheckState = MutableStateFlow<Boolean?>(null)
    val updateCheckState: StateFlow<Boolean?> = _updateCheckState.asStateFlow()

    init {
        // Initialize memory database (Stage 0 — schema + sqlite-vec ready). Запускается
        // независимо от chat flow — БД нужна для retrieval (Phase 1) и persistence
        // (Phase 5), но не блокирует loading модели. Если упадёт — log + ничего не
        // делаем; chat продолжит работать без памяти (degraded mode).
        viewModelScope.launch {
            try {
                MemoryDatabaseHelper.initialize(application)
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
            _screen.value = AppScreen.Chat
            _selectedModel.value = downloadedVariant
            loadModel(downloadedVariant)
        } else {
            // Fallback: check for any .gguf pushed manually via adb (dev/testing only)
            val localGguf = findLocalGguf(application)
            if (localGguf != null) {
                _screen.value = AppScreen.Chat
                loadModelFromPath(localGguf)
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
                )
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
            _screen.value = AppScreen.Chat
            loadModel(model)
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
                    _screen.value = AppScreen.Chat
                    loadModel(model)
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
            )
            if (useVulkan) {
                val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_VULKAN_CRASHED, false).apply()
            }
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
        if (text.isBlank() || _isGenerating.value) return

        _messages.value = _messages.value + ChatMessage(text, isUser = true)
        _isGenerating.value = true

        viewModelScope.launch {
            val responseBuilder = StringBuilder()
            var tokenCount = 0
            val startTime = System.currentTimeMillis()
            val batteryBefore = analyticsLogger.getBatteryPercent()

            _messages.value = _messages.value + ChatMessage("", isUser = false)

            val thinkRegex = Regex("<think>([\\s\\S]*?)</think>\\s*")
            // Check if thinking was auto-disabled due to low context
            val thinkingActive = _thinkingMode.value == 1 && !engine.wasThinkingDisabled()

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
            engine.cancelLoading()        // flag для model load
            engine.stopGeneration()       // flag для generation
            engine.unloadModel()          // ждёт завершения текущей операции на llamaDispatcher
            _messages.value = emptyList()
            _tokensPerSecond.value = 0f
            _totalTokens.value = 0
            _backendInfo.value = ""
            _loadedModelName.value = ""
            refreshDownloadedModels()
            _screen.value = AppScreen.Setup
        }
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

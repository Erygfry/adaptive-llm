package com.example.adaptivellm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.adaptivellm.analytics.AnalyticsLogger
import com.example.adaptivellm.device.DeviceDetector
import com.example.adaptivellm.device.DeviceProfile
import com.example.adaptivellm.inference.InferenceEngine
import com.example.adaptivellm.inference.InferenceEngineImpl
import com.example.adaptivellm.model.DownloadService
import com.example.adaptivellm.model.DownloadState
import com.example.adaptivellm.model.ModelCatalog
import com.example.adaptivellm.model.ModelDownloader
import com.example.adaptivellm.model.ModelSelector
import com.example.adaptivellm.model.ModelVariant
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
        // Check if a model is already in app's files dir (e.g. pushed via adb)
        val localGguf = findLocalGguf(application)
        if (localGguf != null) {
            _screen.value = AppScreen.Chat
            loadModelFromPath(localGguf)
        } else if (downloader.isDownloaded(recommendedModel)) {
            _screen.value = AppScreen.Chat
            loadModel(recommendedModel)
        }

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
                engine.loadModel(path, nGpuLayers)
                engine.setSystemPrompt(SYSTEM_PROMPT)
                _backendInfo.value = engine.getBackendName()
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
            engine.loadModel(path, nGpuLayers = if (useVulkan) -1 else 0)
            if (useVulkan) {
                val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_VULKAN_CRASHED, false).apply()
            }
            engine.setSystemPrompt(SYSTEM_PROMPT)
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
                } else if (_thinkingMode.value == 1 && raw.contains("</think>")) {
                    // ALWAYS mode: opening <think> is in the prompt, not in generated text
                    thinkContent = raw.substringBefore("</think>").trim()
                    displayText = raw.substringAfter("</think>").trim()
                } else if (_thinkingMode.value == 1 && !raw.contains("</think>")) {
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
        engine.unloadModel()
        _messages.value = emptyList()
        _tokensPerSecond.value = 0f
        _backendInfo.value = ""
        _loadedModelName.value = ""
        refreshDownloadedModels()
        _screen.value = AppScreen.Setup
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
        engine.shutdown()
        super.onCleared()
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

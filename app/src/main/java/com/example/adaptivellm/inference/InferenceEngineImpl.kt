package com.example.adaptivellm.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InferenceEngineImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {

    companion object {
        private const val TAG = "InferenceEngine"

        @Volatile
        private var instance: InferenceEngineImpl? = null

        fun getInstance(context: Context): InferenceEngineImpl {
            return instance ?: synchronized(this) {
                instance ?: InferenceEngineImpl(
                    context.applicationInfo.nativeLibraryDir
                ).also { instance = it }
            }
        }
    }

    // JNI declarations
    private external fun nativeInit(backendPath: String)
    private external fun nativeLoadModel(modelPath: String, nGpuLayers: Int): Int
    private external fun nativeSetSystemPrompt(prompt: String): Int
    private external fun nativeProcessUserPrompt(prompt: String, nPredict: Int): Int
    private external fun nativeGenerateToken(): String?
    private external fun nativeSystemInfo(): String
    private external fun nativeBackendName(): String
    private external fun nativeBench(pp: Int, tg: Int, pl: Int, nr: Int): String
    private external fun nativeUnload()
    private external fun nativeShutdown()
    private external fun nativeSetThinkingMode(mode: Int)


    private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Idle)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    @Volatile
    private var cancelGeneration = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        scope.launch {
            try {
                _state.value = InferenceEngine.State.Initializing
                System.loadLibrary("adaptive-llm")
                nativeInit(nativeLibDir)
                _state.value = InferenceEngine.State.Ready
                Log.i(TAG, "Engine initialized. System info: ${nativeSystemInfo()}")
            } catch (e: Exception) {
                Log.e(TAG, "Init failed", e)
                _state.value = InferenceEngine.State.Error(e.message ?: "Init failed")
            }
        }
    }

    override suspend fun loadModel(path: String, nGpuLayers: Int): Unit = withContext(llamaDispatcher) {
        _state.value = InferenceEngine.State.LoadingModel
        Log.i(TAG, "Loading model: $path (nGpuLayers=$nGpuLayers)")

        val result = nativeLoadModel(path, nGpuLayers)
        if (result != 0) {
            val msg = "Failed to load model (code $result)"
            _state.value = InferenceEngine.State.Error(msg)
            throw RuntimeException(msg)
        }

        cancelGeneration = false
        _state.value = InferenceEngine.State.ModelLoaded
        Log.i(TAG, "Model loaded. Backend: ${nativeBackendName()}")
    }

    override suspend fun setSystemPrompt(prompt: String) = withContext(llamaDispatcher) {
        if (prompt.isBlank()) return@withContext

        _state.value = InferenceEngine.State.Processing
        val result = nativeSetSystemPrompt(prompt)
        if (result != 0) {
            _state.value = InferenceEngine.State.Error("System prompt failed (code $result)")
            return@withContext
        }
        _state.value = InferenceEngine.State.ModelLoaded
    }

    override fun sendMessage(message: String, maxTokens: Int): Flow<String> = flow {
        check(_state.value is InferenceEngine.State.ModelLoaded) {
            "Model not loaded (state: ${_state.value})"
        }

        cancelGeneration = false
        _state.value = InferenceEngine.State.Processing

        val result = nativeProcessUserPrompt(message, maxTokens)
        if (result != 0) {
            _state.value = InferenceEngine.State.Error("Prompt processing failed")
            return@flow
        }

        _state.value = InferenceEngine.State.Generating
        while (!cancelGeneration) {
            val token = nativeGenerateToken() ?: break
            if (token.isNotEmpty()) emit(token)
        }

        _state.value = InferenceEngine.State.ModelLoaded
    }.flowOn(llamaDispatcher)

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            nativeBench(pp, tg, pl, nr)
        }

    override fun getSystemInfo(): String = nativeSystemInfo()

    override fun getBackendName(): String = nativeBackendName()

    override fun stopGeneration() {
        cancelGeneration = true
    }

    override fun unloadModel() {
        cancelGeneration = true
        nativeUnload()
        _state.value = InferenceEngine.State.Ready
    }

    override fun shutdown() {
        cancelGeneration = true
        nativeUnload()
        nativeShutdown()
        _state.value = InferenceEngine.State.Idle
    }
    override fun setThinkingMode(mode: Int) {
        nativeSetThinkingMode(mode) 
    }


}

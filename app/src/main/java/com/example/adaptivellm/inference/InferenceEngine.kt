package com.example.adaptivellm.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface InferenceEngine {

    val state: StateFlow<State>

    suspend fun loadModel(path: String, nGpuLayers: Int = 0)
    suspend fun setSystemPrompt(prompt: String)
    fun sendMessage(message: String, maxTokens: Int = 4096): Flow<String>
    suspend fun bench(pp: Int = 512, tg: Int = 128, pl: Int = 1, nr: Int = 1): String
    fun getSystemInfo(): String
    fun getBackendName(): String
    fun stopGeneration()
    fun unloadModel()
    fun shutdown()
    // 0 = AUTO, 1 = ALWAYS, 2 = NEVER
    fun setThinkingMode(mode: Int)
    fun getCurrentPos(): Int
    fun wasThinkingDisabled(): Boolean

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

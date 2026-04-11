package com.example.adaptivellm.analytics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.logEvent

class AnalyticsLogger(private val context: Context) {

    private val analytics = FirebaseAnalytics.getInstance(context)

    /** Log once when a model is loaded. */
    fun logModelLoaded(
        modelName: String,
        modelSizeMb: Long,
        backend: String,
        nThreads: Int,
        nBigCores: Int,
        quantization: String,
    ) {
        analytics.logEvent("model_loaded") {
            param("model_name", modelName)
            param("model_size_mb", modelSizeMb)
            param("backend", backend)
            param("n_threads", nThreads.toLong())
            param("n_big_cores", nBigCores.toLong())
            param("quantization", quantization)
            param("device_model", Build.MODEL)
            param("device_soc", Build.HARDWARE)
            param("android_sdk", Build.VERSION.SDK_INT.toLong())
            param("ram_total_mb", getTotalRamMb())
        }
    }

    /** Log after each generation completes. */
    fun logGenerationComplete(
        modelName: String,
        tokensPerSecond: Float,
        tokenCount: Int,
        durationMs: Long,
        backend: String,
        thinkingMode: Int,
    ) {
        val memInfo = getMemoryInfo()

        analytics.logEvent("generation_complete") {
            param("model_name", modelName)
            param("tokens_per_second", tokensPerSecond.toDouble())
            param("token_count", tokenCount.toLong())
            param("duration_ms", durationMs)
            param("backend", backend)
            param("thinking_mode", thinkingMode.toLong())
            param("ram_used_mb", memInfo.usedRamMb)
            param("native_heap_mb", memInfo.nativeHeapMb)
            param("battery_percent", getBatteryPercent().toLong())
            param("device_model", Build.MODEL)
        }
    }

    /** Log battery drain for a generation session (call with before/after values). */
    fun logBatteryDrain(
        modelName: String,
        batteryBefore: Int,
        batteryAfter: Int,
        tokenCount: Int,
    ) {
        val drain = batteryBefore - batteryAfter
        if (drain > 0) {
            analytics.logEvent("battery_drain") {
                param("model_name", modelName)
                param("battery_drop_percent", drain.toLong())
                param("token_count", tokenCount.toLong())
                param("tokens_per_percent", if (drain > 0) (tokenCount / drain).toLong() else 0L)
            }
        }
    }

    /** Log when model loading fails. */
    fun logModelLoadError(
        modelName: String,
        error: String,
        backend: String,
    ) {
        analytics.logEvent("model_load_error") {
            param("model_name", modelName)
            param("error", error.take(100)) // Firebase limits param to 100 chars
            param("backend", backend)
            param("device_model", Build.MODEL)
            param("device_soc", Build.HARDWARE)
            param("ram_total_mb", getTotalRamMb())
        }
    }

    /** Log when model download completes. */
    fun logModelDownloaded(modelName: String, sizeMb: Long, durationMs: Long) {
        analytics.logEvent("model_downloaded") {
            param("model_name", modelName)
            param("model_size_mb", sizeMb)
            param("duration_ms", durationMs)
        }
    }

    // --- Helpers ---

    fun getBatteryPercent(): Int {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (scale > 0) (level * 100) / scale else -1
    }

    private fun getTotalRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    data class MemInfo(val usedRamMb: Long, val nativeHeapMb: Long)

    private fun getMemoryInfo(): MemInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val usedRam = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
        val nativeHeap = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        return MemInfo(usedRam, nativeHeap)
    }
}

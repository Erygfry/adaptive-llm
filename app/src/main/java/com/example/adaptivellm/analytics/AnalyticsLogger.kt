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
import com.google.firebase.firestore.FirebaseFirestore

class AnalyticsLogger(private val context: Context) {

    private val analytics = FirebaseAnalytics.getInstance(context)
    private val firestore = FirebaseFirestore.getInstance()
    private val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("analytics", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    /** Log once when a model is loaded. */
    fun logModelLoaded(
        modelName: String,
        modelSizeMb: Long,
        backend: String,
        nThreads: Int,
        nBigCores: Int,
        quantization: String,
    ) {
        val data = mapOf(
            "model_name" to modelName,
            "model_size_mb" to modelSizeMb,
            "backend" to backend,
            "n_threads" to nThreads,
            "n_big_cores" to nBigCores,
            "quantization" to quantization,
            "device_model" to Build.MODEL,
            "device_soc" to Build.HARDWARE,
            "android_sdk" to Build.VERSION.SDK_INT,
            "ram_total_mb" to getTotalRamMb(),
        )

        analytics.logEvent("model_loaded") {
            data.forEach { (k, v) ->
                when (v) {
                    is String -> param(k, v)
                    is Number -> param(k, v.toLong())
                }
            }
        }
        logToFirestore("model_loaded", data)
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

        val data = mapOf(
            "model_name" to modelName,
            "tokens_per_second" to tokensPerSecond,
            "token_count" to tokenCount,
            "duration_ms" to durationMs,
            "backend" to backend,
            "thinking_mode" to thinkingMode,
            "ram_used_mb" to memInfo.usedRamMb,
            "native_heap_mb" to memInfo.nativeHeapMb,
            "battery_percent" to getBatteryPercent(),
            "device_model" to Build.MODEL,
        )

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
        logToFirestore("generation_complete", data)
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
            val data = mapOf(
                "model_name" to modelName,
                "battery_drop_percent" to drain,
                "token_count" to tokenCount,
                "tokens_per_percent" to if (drain > 0) tokenCount / drain else 0,
            )

            analytics.logEvent("battery_drain") {
                param("model_name", modelName)
                param("battery_drop_percent", drain.toLong())
                param("token_count", tokenCount.toLong())
                param("tokens_per_percent", if (drain > 0) (tokenCount / drain).toLong() else 0L)
            }
            logToFirestore("battery_drain", data)
        }
    }

    /** Log when model loading fails. */
    fun logModelLoadError(
        modelName: String,
        error: String,
        backend: String,
    ) {
        val data = mapOf(
            "model_name" to modelName,
            "error" to error,
            "backend" to backend,
            "device_model" to Build.MODEL,
            "device_soc" to Build.HARDWARE,
            "ram_total_mb" to getTotalRamMb(),
        )

        analytics.logEvent("model_load_error") {
            param("model_name", modelName)
            param("error", error.take(100))
            param("backend", backend)
            param("device_model", Build.MODEL)
            param("device_soc", Build.HARDWARE)
            param("ram_total_mb", getTotalRamMb())
        }
        logToFirestore("model_load_error", data)
    }

    /** Log when model download completes. */
    fun logModelDownloaded(modelName: String, sizeMb: Long, durationMs: Long) {
        val data = mapOf(
            "model_name" to modelName,
            "model_size_mb" to sizeMb,
            "duration_ms" to durationMs,
        )

        analytics.logEvent("model_downloaded") {
            param("model_name", modelName)
            param("model_size_mb", sizeMb)
            param("duration_ms", durationMs)
        }
        logToFirestore("model_downloaded", data)
    }

    private fun logToFirestore(event: String, data: Map<String, Any>) {
        val doc = data.toMutableMap()
        doc["device_id"] = deviceId
        doc["timestamp"] = com.google.firebase.Timestamp.now()
        doc["app_version"] = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }

        firestore.collection("events").document("$event-${System.currentTimeMillis()}")
            .set(doc)
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

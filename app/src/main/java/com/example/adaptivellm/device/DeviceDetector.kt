package com.example.adaptivellm.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

object DeviceDetector {

    fun detect(context: Context): DeviceProfile {
        return DeviceProfile(
            totalRamMb = getTotalRamMb(context),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuFeatures = getCpuFeatures(),
            hasVulkan = hasVulkanSupport(context),
            socModel = getSocModel(),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    private fun getTotalRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getCpuFeatures(): Set<String> {
        return try {
            val cpuinfo = File("/proc/cpuinfo").readText()
            val featuresLine = cpuinfo.lines()
                .firstOrNull { it.startsWith("Features") }
                ?: return emptySet()
            featuresLine.substringAfter(":")
                .trim()
                .split("\\s+".toRegex())
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun hasVulkanSupport(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, 0
        )
    }

    private fun getSocModel(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            try {
                File("/proc/cpuinfo").readText().lines()
                    .firstOrNull { it.startsWith("Hardware") }
                    ?.substringAfter(":")?.trim()
                    ?: "Unknown"
            } catch (e: Exception) {
                "Unknown"
            }
        }
    }
}

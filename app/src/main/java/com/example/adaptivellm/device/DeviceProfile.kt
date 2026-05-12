package com.example.adaptivellm.device

data class DeviceProfile(
    val totalRamMb: Long,
    val cpuCores: Int,
    val cpuFeatures: Set<String>,
    val hasVulkan: Boolean,
    val socModel: String,
    val deviceModel: String,
) {
    // Пороги откалиброваны против `ActivityManager.MemoryInfo.totalMem`, который
    // возвращает USABLE RAM (после Android system reserve — обычно на 1-2 GB ниже
    // nominal). Соответствие nominal vs reported:
    //   12 GB nominal → ~10-11 GB reported → HIGH (Pixel 9 etc.)
    //   8 GB nominal  → ~7 GB reported     → UPPER_MID
    //   6 GB nominal  → ~5 GB reported     → MID (Galaxy A80, Xiaomi Pad 5 etc.)
    //   4 GB nominal  → ~3.5 GB reported   → LOW (модели не грузятся, "not supported")
    val ramTier: RamTier
        get() = when {
            totalRamMb >= 10_000 -> RamTier.HIGH
            totalRamMb >= 7_000  -> RamTier.UPPER_MID
            totalRamMb >= 5_000  -> RamTier.MID
            totalRamMb >= 3_500  -> RamTier.LOW
            else                 -> RamTier.VERY_LOW
        }

    val hasDotProd: Boolean get() = "asimddp" in cpuFeatures
    val hasI8mm: Boolean get() = "i8mm" in cpuFeatures
    val hasSve: Boolean get() = "sve" in cpuFeatures

    fun toDisplayString(): String = buildString {
        appendLine("Device: $deviceModel")
        appendLine("SoC: $socModel")
        appendLine("RAM: ${totalRamMb / 1024} GB")
        appendLine("CPU cores: $cpuCores")
        append("CPU features: ${cpuFeatures.joinToString(", ")}")
    }
}

enum class RamTier {
    VERY_LOW, LOW, MID, UPPER_MID, HIGH
}

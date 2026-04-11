package com.example.adaptivellm.device

data class DeviceProfile(
    val totalRamMb: Long,
    val cpuCores: Int,
    val cpuFeatures: Set<String>,
    val hasVulkan: Boolean,
    val socModel: String,
    val deviceModel: String,
) {
    val ramTier: RamTier
        get() = when {
            totalRamMb >= 12_000 -> RamTier.HIGH
            totalRamMb >= 8_000  -> RamTier.UPPER_MID
            totalRamMb >= 6_000  -> RamTier.MID
            totalRamMb >= 4_000  -> RamTier.LOW
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
        appendLine("CPU features: ${cpuFeatures.joinToString(", ")}")
        appendLine("Vulkan: ${if (hasVulkan) "Yes" else "No"}")
    }
}

enum class RamTier {
    VERY_LOW, LOW, MID, UPPER_MID, HIGH
}

package com.example.adaptivellm.model

import com.example.adaptivellm.device.DeviceProfile

object ModelSelector {

    /**
     * Returns the best model variant for the given device profile.
     * Picks the largest model that fits in RAM, preferring higher quantization.
     */
    fun recommend(profile: DeviceProfile): ModelVariant {
        val compatible = ModelCatalog.variants
            .filter { it.minRamMb <= profile.totalRamMb }
            .sortedWith(
                compareByDescending<ModelVariant> { paramSizeOrder(it.parameterSize) }
                    .thenByDescending { quantOrder(it.quantization) }
            )

        return compatible.firstOrNull() ?: ModelCatalog.variants.first()
    }

    /**
     * Returns all models compatible with the device, grouped by parameter size.
     */
    fun compatibleModels(profile: DeviceProfile): Map<String, List<ModelVariant>> {
        return ModelCatalog.variants
            .filter { it.minRamMb <= profile.totalRamMb }
            .groupBy { it.parameterSize }
    }

    private fun paramSizeOrder(size: String): Int = when (size) {
        "2B" -> 1
        "4B" -> 2
        "8B" -> 3
        else -> 0
    }

    private fun quantOrder(quant: String): Int = when (quant) {
        "Q3_K_M" -> 1
        "Q4_K_M" -> 2
        "Q6_K" -> 3
        else -> 0
    }
}

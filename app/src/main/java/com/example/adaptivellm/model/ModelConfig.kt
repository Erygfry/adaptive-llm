package com.example.adaptivellm.model

data class ModelVariant(
    val displayName: String,
    val parameterSize: String,
    val quantization: String,
    val fileSizeMb: Long,
    val huggingFaceRepo: String,
    val fileName: String,
    /**
     * Минимум reported usable RAM (после Android system reserve). Используется для
     * проверки совместимости через DeviceProfile.totalRamMb. См. калибровку в комментарии
     * ниже на ModelCatalog.variants.
     */
    val minRamMb: Long,
    /**
     * Nominal RAM устройства которое мы рекомендуем пользователю (то что написано на
     * коробке). Используется только для display в UI — реальная проверка идёт по
     * minRamMb. Обычно nominalMinRamGb ≈ ceil((minRamMb + 1024) / 1024).
     */
    val nominalMinRamGb: Int,
    /** Optional vision projector file for VL models (--mmproj) */
    val mmProjFileName: String? = null,
)


object ModelCatalog {

    private const val HF_REPO = "MrShadow1/qwen3-adaptive"

    // minRamMb откалиброван против reported usable RAM (см. DeviceProfile.ramTier),
    // не nominal. Соответствие nominal → reported порогам:
    //   Q3 (Lite):     6GB nominal → 5000 reported  (MID tier+, включая Xiaomi Pad 5)
    //   Q4 (Standard): 8GB nominal → 7000 reported  (UPPER_MID tier+)
    //   Q6 (Quality):  12GB nominal → 10000 reported (HIGH tier)
    val variants = listOf(
        ModelVariant(
            displayName = "Lite",
            parameterSize = "4B",
            quantization = "Q3_K_M",
            fileSizeMb = 2186,
            huggingFaceRepo = HF_REPO,
            fileName = "Qwen3.5-4B-Q3_K_M.gguf",
            minRamMb = 5_000,
            nominalMinRamGb = 6,
        ),
        ModelVariant(
            displayName = "Standard",
            parameterSize = "4B",
            quantization = "Q4_K_M",
            fileSizeMb = 2576,
            huggingFaceRepo = HF_REPO,
            fileName = "Qwen3.5-4B-Q4_K_M.gguf",
            minRamMb = 7_000,
            nominalMinRamGb = 8,
        ),
        ModelVariant(
            displayName = "Quality",
            parameterSize = "4B",
            quantization = "Q6_K",
            fileSizeMb = 3263,
            huggingFaceRepo = HF_REPO,
            fileName = "Qwen3.5-4B-Q6_K.gguf",
            minRamMb = 10_000,
            nominalMinRamGb = 12,
        ),
    )
}

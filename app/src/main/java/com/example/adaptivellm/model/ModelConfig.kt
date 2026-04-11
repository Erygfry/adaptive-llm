package com.example.adaptivellm.model

data class ModelVariant(
    val displayName: String,
    val parameterSize: String,
    val quantization: String,
    val fileSizeMb: Long,
    val huggingFaceRepo: String,
    val fileName: String,
    val minRamMb: Long,
    /** Optional vision projector file for VL models (--mmproj) */
    val mmProjFileName: String? = null,
)


object ModelCatalog {

    private const val HF_REPO = "MrShadow1/qwen3-adaptive"

    val variants = listOf(
        ModelVariant(
            displayName = "Qwen3.5-4B Q3_K_M",
            parameterSize = "4B",
            quantization = "Q3_K_M",
            fileSizeMb = 2186,
            huggingFaceRepo = HF_REPO,
            fileName = "Qwen3.5-4B-Q3_K_M.gguf",
            minRamMb = 4_000,
        ),
        ModelVariant(
            displayName = "Qwen3.5-4B Q4_K_M",
            parameterSize = "4B",
            quantization = "Q4_K_M",
            fileSizeMb = 2576,
            huggingFaceRepo = HF_REPO,
            fileName = "Qwen3.5-4B-Q4_K_M.gguf",
            minRamMb = 5_000,
        ),
        ModelVariant(
            displayName = "Qwen3.5-4B Q6_K",
            parameterSize = "4B",
            quantization = "Q6_K",
            fileSizeMb = 3263,
            huggingFaceRepo = HF_REPO,
            fileName = "Qwen3.5-4B-Q6_K.gguf",
            minRamMb = 6_000,
        ),
    )
}

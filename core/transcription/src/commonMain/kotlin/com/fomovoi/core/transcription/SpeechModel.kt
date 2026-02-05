package com.fomovoi.core.transcription

/**
 * Available speech recognition models with their configurations.
 */
enum class SpeechModelType(
    val displayName: String,
    val description: String,
    val isStreaming: Boolean
) {
    WHISPER_TINY(
        displayName = "Whisper Tiny",
        description = "Fast, good accuracy (~100MB)",
        isStreaming = false
    ),
    WHISPER_SMALL(
        displayName = "Whisper Small",
        description = "Best accuracy, slower (~375MB)",
        isStreaming = false
    )
}

/**
 * A downloadable speech model for a specific language.
 */
data class SpeechModel(
    val id: String,
    val type: SpeechModelType,
    val language: SpeechLanguage,
    val baseUrl: String,
    val files: List<ModelFile>,
    val isDownloaded: Boolean = false,
    val downloadProgress: Float = 0f
) {
    val totalSizeBytes: Long
        get() = files.sumOf { it.expectedSize }

    val totalSizeMB: Int
        get() = (totalSizeBytes / 1_000_000).toInt()

    val displayName: String
        get() = "${language.displayName} - ${type.displayName}"
}

/**
 * Available speech models catalog.
 */
object SpeechModelCatalog {

    val allModels: List<SpeechModel> by lazy {
        listOf(
            whisperTinyEnglish,
            whisperSmallEnglish
        )
    }

    val whisperTinyEnglish = SpeechModel(
        id = "whisper-tiny-en",
        type = SpeechModelType.WHISPER_TINY,
        language = SpeechLanguage.ENGLISH,
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main",
        files = listOf(
            ModelFile("tiny.en-encoder.int8.onnx", 12_937_772L),
            ModelFile("tiny.en-decoder.int8.onnx", 89_853_865L),
            ModelFile("tiny.en-tokens.txt", 835_554L)
        )
    )

    val whisperSmallEnglish = SpeechModel(
        id = "whisper-small-en",
        type = SpeechModelType.WHISPER_SMALL,
        language = SpeechLanguage.ENGLISH,
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small.en/resolve/main",
        files = listOf(
            ModelFile("small.en-encoder.int8.onnx", 112_442_483L),
            ModelFile("small.en-decoder.int8.onnx", 262_223_042L),
            ModelFile("small.en-tokens.txt", 835_554L)
        )
    )

    fun getModelById(id: String): SpeechModel? = allModels.find { it.id == id }

    fun getModelsForLanguage(language: SpeechLanguage): List<SpeechModel> =
        allModels.filter { it.language == language }

    fun getModelsByType(type: SpeechModelType): List<SpeechModel> =
        allModels.filter { it.type == type }
}

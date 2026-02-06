package app.s4h.nisafone.core.transcription

/**
 * Available speech recognition model types.
 * All Whisper models use batch processing (not streaming).
 */
enum class SpeechModelType(
    val displayName: String,
    val description: String,
    val isStreaming: Boolean
) {
    WHISPER_TINY(
        displayName = "Whisper Tiny",
        description = "Fastest, ~100MB",
        isStreaming = false
    ),
    WHISPER_BASE(
        displayName = "Whisper Base",
        description = "Fast, ~150MB",
        isStreaming = false
    ),
    WHISPER_SMALL(
        displayName = "Whisper Small",
        description = "Balanced, ~375MB",
        isStreaming = false
    ),
    WHISPER_MEDIUM(
        displayName = "Whisper Medium",
        description = "Good accuracy, ~750MB",
        isStreaming = false
    ),
    WHISPER_LARGE(
        displayName = "Whisper Large",
        description = "Best accuracy, ~1.5GB+",
        isStreaming = false
    ),
    WHISPER_TURBO(
        displayName = "Whisper Turbo",
        description = "Optimized large model",
        isStreaming = false
    ),
    WHISPER_DISTIL(
        displayName = "Whisper Distil",
        description = "Distilled for speed",
        isStreaming = false
    ),
    WHISPER_OTHER(
        displayName = "Whisper",
        description = "Other Whisper variant",
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
        get() = "${type.displayName} (${language.displayName})"
}

/**
 * Fallback static catalog for when Hugging Face discovery fails.
 */
object SpeechModelCatalog {

    val allModels: List<SpeechModel> by lazy {
        listOf(
            whisperTinyEnglish,
            whisperSmallEnglish
        )
    }

    val whisperTinyEnglish = SpeechModel(
        id = "whisper-tiny.en",
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
        id = "whisper-small.en",
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

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
        description = "Fast, good accuracy (~75MB per language)",
        isStreaming = false
    ),
    WHISPER_SMALL(
        displayName = "Whisper Small",
        description = "Best accuracy, slower (~250MB per language)",
        isStreaming = false
    ),
    ZIPFORMER_STREAMING(
        displayName = "Zipformer (Real-time)",
        description = "Live transcription as you speak (~300MB per language)",
        isStreaming = true
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
        buildList {
            // Whisper Tiny models (English only for now - multilingual available)
            add(whisperTinyEnglish)

            // Whisper Small models
            add(whisperSmallEnglish)

            // Zipformer streaming models
            addAll(zipformerStreamingModels)
        }
    }

    val whisperTinyEnglish = SpeechModel(
        id = "whisper-tiny-en",
        type = SpeechModelType.WHISPER_TINY,
        language = SpeechLanguage.ENGLISH,
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main",
        files = listOf(
            ModelFile("tiny.en-encoder.int8.onnx", 24_135_241L),
            ModelFile("tiny.en-decoder.int8.onnx", 36_837_588L),
            ModelFile("tiny.en-tokens.txt", 107_505L)
        )
    )

    val whisperSmallEnglish = SpeechModel(
        id = "whisper-small-en",
        type = SpeechModelType.WHISPER_SMALL,
        language = SpeechLanguage.ENGLISH,
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small.en/resolve/main",
        files = listOf(
            ModelFile("small.en-encoder.int8.onnx", 112_442_483L),
            ModelFile("small.en-decoder.int8.onnx", 120_938_400L),
            ModelFile("small.en-tokens.txt", 107_505L)
        )
    )

    private val zipformerStreamingModels = listOf(
        // English
        SpeechModel(
            id = "zipformer-streaming-en",
            type = SpeechModelType.ZIPFORMER_STREAMING,
            language = SpeechLanguage.ENGLISH,
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21/resolve/main",
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.onnx", 292_543_537L),
                ModelFile("decoder-epoch-99-avg-1.onnx", 2_093_080L),
                ModelFile("joiner-epoch-99-avg-1.onnx", 1_026_462L),
                ModelFile("tokens.txt", 5_048L)
            )
        ),
        // Chinese
        SpeechModel(
            id = "zipformer-streaming-zh",
            type = SpeechModelType.ZIPFORMER_STREAMING,
            language = SpeechLanguage.CHINESE,
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-2023-02-21/resolve/main",
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.onnx", 292_543_537L),
                ModelFile("decoder-epoch-99-avg-1.onnx", 12_823_448L),
                ModelFile("joiner-epoch-99-avg-1.onnx", 6_291_866L),
                ModelFile("tokens.txt", 98_188L)
            )
        ),
        // Japanese
        SpeechModel(
            id = "zipformer-streaming-ja",
            type = SpeechModelType.ZIPFORMER_STREAMING,
            language = SpeechLanguage.JAPANESE,
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ja-2023-09-29/resolve/main",
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.onnx", 292_543_537L),
                ModelFile("decoder-epoch-99-avg-1.onnx", 12_823_448L),
                ModelFile("joiner-epoch-99-avg-1.onnx", 6_291_866L),
                ModelFile("tokens.txt", 54_068L)
            )
        ),
        // Korean
        SpeechModel(
            id = "zipformer-streaming-ko",
            type = SpeechModelType.ZIPFORMER_STREAMING,
            language = SpeechLanguage.KOREAN,
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-korean-2024-06-16/resolve/main",
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.onnx", 292_543_537L),
                ModelFile("decoder-epoch-99-avg-1.onnx", 5_119_640L),
                ModelFile("joiner-epoch-99-avg-1.onnx", 2_512_282L),
                ModelFile("tokens.txt", 12_044L)
            )
        ),
        // German
        SpeechModel(
            id = "zipformer-streaming-de",
            type = SpeechModelType.ZIPFORMER_STREAMING,
            language = SpeechLanguage.GERMAN,
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-de-2023-06-26/resolve/main",
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.onnx", 292_543_537L),
                ModelFile("decoder-epoch-99-avg-1.onnx", 2_093_080L),
                ModelFile("joiner-epoch-99-avg-1.onnx", 1_026_462L),
                ModelFile("tokens.txt", 5_016L)
            )
        ),
        // French
        SpeechModel(
            id = "zipformer-streaming-fr",
            type = SpeechModelType.ZIPFORMER_STREAMING,
            language = SpeechLanguage.FRENCH,
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/main",
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.onnx", 292_543_537L),
                ModelFile("decoder-epoch-99-avg-1.onnx", 2_093_080L),
                ModelFile("joiner-epoch-99-avg-1.onnx", 1_026_462L),
                ModelFile("tokens.txt", 5_016L)
            )
        ),
        // Spanish
        SpeechModel(
            id = "zipformer-streaming-es",
            type = SpeechModelType.ZIPFORMER_STREAMING,
            language = SpeechLanguage.SPANISH,
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-es-2023-06-21/resolve/main",
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.onnx", 292_543_537L),
                ModelFile("decoder-epoch-99-avg-1.onnx", 2_093_080L),
                ModelFile("joiner-epoch-99-avg-1.onnx", 1_026_462L),
                ModelFile("tokens.txt", 5_016L)
            )
        ),
        // Russian
        SpeechModel(
            id = "zipformer-streaming-ru",
            type = SpeechModelType.ZIPFORMER_STREAMING,
            language = SpeechLanguage.RUSSIAN,
            baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ru-2023-06-26/resolve/main",
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.onnx", 292_543_537L),
                ModelFile("decoder-epoch-99-avg-1.onnx", 2_093_080L),
                ModelFile("joiner-epoch-99-avg-1.onnx", 1_026_462L),
                ModelFile("tokens.txt", 5_016L)
            )
        )
    )

    fun getModelById(id: String): SpeechModel? = allModels.find { it.id == id }

    fun getModelsForLanguage(language: SpeechLanguage): List<SpeechModel> =
        allModels.filter { it.language == language }

    fun getModelsByType(type: SpeechModelType): List<SpeechModel> =
        allModels.filter { it.type == type }
}

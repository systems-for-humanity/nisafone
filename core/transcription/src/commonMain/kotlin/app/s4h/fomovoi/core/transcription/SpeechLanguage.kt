package app.s4h.fomovoi.core.transcription

/**
 * Supported language configurations for speech recognition.
 * Whisper models come in two variants:
 * - English-only: Optimized for English, slightly better accuracy
 * - Multilingual: Supports ~100 languages with auto-detection
 */
enum class SpeechLanguage(
    val code: String,
    val displayName: String
) {
    ENGLISH(
        code = "en",
        displayName = "English"
    ),
    MULTILINGUAL(
        code = "multi",
        displayName = "Multilingual"
    );

    companion object {
        fun fromCode(code: String): SpeechLanguage? = entries.find { it.code == code }

        val default: SpeechLanguage = ENGLISH
    }
}

data class ModelFile(
    val name: String,
    val expectedSize: Long
)

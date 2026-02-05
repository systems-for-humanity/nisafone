package app.s4h.fomovoi.core.transcription

/**
 * Language hints for multilingual Whisper models.
 * When set, Whisper will prioritize transcribing in the specified language.
 * AUTO_DETECT lets Whisper automatically detect the spoken language.
 */
enum class LanguageHint(
    val code: String,
    val displayName: String
) {
    AUTO_DETECT("", "Auto-detect"),
    ENGLISH("en", "English"),
    SPANISH("es", "Spanish"),
    FRENCH("fr", "French"),
    GERMAN("de", "German"),
    ITALIAN("it", "Italian"),
    PORTUGUESE("pt", "Portuguese"),
    DUTCH("nl", "Dutch"),
    POLISH("pl", "Polish"),
    RUSSIAN("ru", "Russian"),
    UKRAINIAN("uk", "Ukrainian"),
    CHINESE("zh", "Chinese"),
    JAPANESE("ja", "Japanese"),
    KOREAN("ko", "Korean"),
    ARABIC("ar", "Arabic"),
    HINDI("hi", "Hindi"),
    TURKISH("tr", "Turkish"),
    VIETNAMESE("vi", "Vietnamese"),
    THAI("th", "Thai"),
    INDONESIAN("id", "Indonesian"),
    MALAY("ms", "Malay"),
    TAGALOG("tl", "Tagalog"),
    SWEDISH("sv", "Swedish"),
    DANISH("da", "Danish"),
    NORWEGIAN("no", "Norwegian"),
    FINNISH("fi", "Finnish"),
    GREEK("el", "Greek"),
    CZECH("cs", "Czech"),
    ROMANIAN("ro", "Romanian"),
    HUNGARIAN("hu", "Hungarian"),
    HEBREW("he", "Hebrew");

    companion object {
        fun fromCode(code: String): LanguageHint? = entries.find { it.code == code }

        val default: LanguageHint = AUTO_DETECT
    }
}

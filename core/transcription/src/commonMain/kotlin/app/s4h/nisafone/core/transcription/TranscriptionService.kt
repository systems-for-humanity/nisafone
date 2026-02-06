package app.s4h.nisafone.core.transcription

import app.s4h.nisafone.core.audio.AudioChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TranscriptionService {
    val state: StateFlow<TranscriptionState>
    val events: Flow<TranscriptionEvent>
    val currentSpeaker: StateFlow<Speaker?>
    val currentLanguage: StateFlow<SpeechLanguage>
    val currentLanguageHint: StateFlow<LanguageHint>
    val translateToEnglish: StateFlow<Boolean>
    val availableLanguages: List<SpeechLanguage>

    /**
     * Whether this transcription service handles audio input internally.
     * If true, the caller should NOT start a separate AudioRecorder as it
     * would conflict with the transcription service's internal audio capture.
     */
    val handlesAudioInternally: Boolean
        get() = false

    suspend fun initialize()
    suspend fun startTranscription()
    suspend fun processAudioChunk(chunk: AudioChunk)
    suspend fun stopTranscription(): TranscriptionResult?

    /**
     * Change the speech recognition language.
     * This will download the model if not already cached.
     */
    suspend fun setLanguage(language: SpeechLanguage)

    /**
     * Set the language hint for multilingual models.
     * Has no effect on English-only models.
     * @param hint The language to prioritize, or AUTO_DETECT for automatic detection.
     */
    suspend fun setLanguageHint(hint: LanguageHint)

    /**
     * Set whether to translate speech to English or transcribe in original language.
     * Only applies to multilingual Whisper models.
     * @param translate true to translate to English, false to transcribe in original language.
     */
    suspend fun setTranslateToEnglish(translate: Boolean)

    fun release()
}

expect fun createTranscriptionService(): TranscriptionService

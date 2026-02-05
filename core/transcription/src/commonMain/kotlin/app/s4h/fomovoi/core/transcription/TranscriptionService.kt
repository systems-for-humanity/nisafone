package app.s4h.fomovoi.core.transcription

import app.s4h.fomovoi.core.audio.AudioChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TranscriptionService {
    val state: StateFlow<TranscriptionState>
    val events: Flow<TranscriptionEvent>
    val currentSpeaker: StateFlow<Speaker?>
    val currentLanguage: StateFlow<SpeechLanguage>
    val currentLanguageHint: StateFlow<LanguageHint>
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
    suspend fun setSpeakerLabel(speakerId: String, label: String)

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

    fun release()
}

expect fun createTranscriptionService(): TranscriptionService

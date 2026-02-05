package app.s4h.fomovoi.feature.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import app.s4h.fomovoi.core.audio.AudioRecorder
import app.s4h.fomovoi.core.audio.RecordingState
import app.s4h.fomovoi.core.domain.model.Recording
import app.s4h.fomovoi.core.domain.usecase.SaveRecordingUseCase
import app.s4h.fomovoi.core.sharing.ShareService
import app.s4h.fomovoi.core.transcription.Speaker
import app.s4h.fomovoi.core.transcription.SpeechLanguage
import app.s4h.fomovoi.core.transcription.TranscriptionEvent
import app.s4h.fomovoi.core.transcription.TranscriptionResult
import app.s4h.fomovoi.core.transcription.TranscriptionService
import app.s4h.fomovoi.core.transcription.TranscriptionState
import app.s4h.fomovoi.core.transcription.Utterance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class RecordingUiState(
    val recordingState: RecordingState = RecordingState.IDLE,
    val transcriptionState: TranscriptionState = TranscriptionState.IDLE,
    val currentSpeaker: Speaker? = null,
    val currentLanguage: SpeechLanguage = SpeechLanguage.ENGLISH,
    val availableLanguages: List<SpeechLanguage> = emptyList(),
    val partialText: String = "",
    val utterances: List<Utterance> = emptyList(),
    val elapsedTimeMs: Long = 0,
    val isSharing: Boolean = false,
    val error: String? = null
) {
    // Recording state considers both audio recorder and transcription service
    // (some transcription services handle audio internally)
    val isRecording: Boolean
        get() = recordingState == RecordingState.RECORDING ||
                transcriptionState == TranscriptionState.TRANSCRIBING

    val isPaused: Boolean
        get() = recordingState == RecordingState.PAUSED

    val canStart: Boolean
        get() = recordingState == RecordingState.IDLE &&
                (transcriptionState == TranscriptionState.READY || transcriptionState == TranscriptionState.IDLE)

    val formattedTime: String
        get() {
            val seconds = (elapsedTimeMs / 1000) % 60
            val minutes = (elapsedTimeMs / (1000 * 60)) % 60
            val hours = elapsedTimeMs / (1000 * 60 * 60)
            return if (hours > 0) {
                "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
            } else {
                "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
            }
        }
}

class RecordingViewModel(
    private val audioRecorder: AudioRecorder,
    private val transcriptionService: TranscriptionService,
    private val shareService: ShareService,
    private val saveRecordingUseCase: SaveRecordingUseCase
) : ViewModel() {

    private val logger = Logger.withTag("RecordingViewModel")

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private var startTimeMs: Long = 0
    private val collectedUtterances = mutableListOf<Utterance>()
    private var isInitialized = false

    private var audioStreamJob: kotlinx.coroutines.Job? = null

    init {
        observeAudioState()
        observeTranscriptionState()
        observeTranscriptionEvents()
        observeLanguage()
    }

    private fun observeAudioState() {
        viewModelScope.launch {
            audioRecorder.state.collect { state ->
                _uiState.update { it.copy(recordingState = state) }
            }
        }
    }

    private fun observeTranscriptionState() {
        viewModelScope.launch {
            transcriptionService.state.collect { state ->
                _uiState.update { it.copy(transcriptionState = state) }
            }
        }

        viewModelScope.launch {
            transcriptionService.currentSpeaker.collect { speaker ->
                _uiState.update { it.copy(currentSpeaker = speaker) }
            }
        }
    }

    private fun observeLanguage() {
        // Set available languages immediately
        _uiState.update { it.copy(availableLanguages = transcriptionService.availableLanguages) }

        viewModelScope.launch {
            transcriptionService.currentLanguage.collect { language ->
                _uiState.update { it.copy(currentLanguage = language) }
            }
        }
    }

    private fun observeTranscriptionEvents() {
        viewModelScope.launch {
            transcriptionService.events.collect { event ->
                when (event) {
                    is TranscriptionEvent.PartialResult -> {
                        _uiState.update { it.copy(partialText = event.text) }
                    }
                    is TranscriptionEvent.FinalResult -> {
                        collectedUtterances.add(event.utterance)
                        _uiState.update {
                            it.copy(
                                utterances = collectedUtterances.toList(),
                                partialText = ""
                            )
                        }
                    }
                    is TranscriptionEvent.SpeakerChange -> {
                        _uiState.update { it.copy(currentSpeaker = event.newSpeaker) }
                    }
                    is TranscriptionEvent.Error -> {
                        logger.e { "Transcription error: ${event.message}" }
                        _uiState.update { it.copy(error = event.message) }
                    }
                }
            }
        }
    }

    fun initialize() {
        // Reinitialize if transcription service is in ERROR state (e.g., after downloading a model)
        val transcriptionState = transcriptionService.state.value
        val needsReinit = transcriptionState == TranscriptionState.ERROR || transcriptionState == TranscriptionState.IDLE

        if (isInitialized && !needsReinit) {
            logger.d { "initialize() called but already initialized, skipping" }
            return
        }
        logger.d { "initialize() called (reinit=$needsReinit, state=$transcriptionState)" }
        viewModelScope.launch {
            try {
                logger.d { "Initializing audioRecorder..." }
                audioRecorder.initialize()
                logger.d { "Initializing transcriptionService..." }
                transcriptionService.initialize()
                isInitialized = true
                logger.d { "Initialization complete" }
            } catch (e: Exception) {
                logger.e(e) { "Failed to initialize" }
                _uiState.update { it.copy(error = "Failed to initialize: ${e.message}") }
            }
        }
    }

    fun startRecording() {
        logger.d { "startRecording() called, canStart=${_uiState.value.canStart}" }
        viewModelScope.launch {
            try {
                collectedUtterances.clear()
                _uiState.update {
                    it.copy(
                        utterances = emptyList(),
                        partialText = "",
                        error = null
                    )
                }

                startTimeMs = Clock.System.now().toEpochMilliseconds()

                // Only start audio recorder if transcription service doesn't handle audio internally
                // (e.g., Android SpeechRecognizer handles its own audio capture)
                if (!transcriptionService.handlesAudioInternally) {
                    logger.d { "Starting audioRecorder..." }
                    audioRecorder.startRecording()

                    // Pipe audio stream to transcription service
                    audioStreamJob = viewModelScope.launch {
                        audioRecorder.audioStream.collect { chunk ->
                            transcriptionService.processAudioChunk(chunk)
                        }
                    }
                } else {
                    logger.d { "Transcription service handles audio internally, skipping audioRecorder" }
                }

                logger.d { "Starting transcriptionService..." }
                transcriptionService.startTranscription()
                logger.d { "Recording and transcription started" }

                // Start elapsed time tracking
                startElapsedTimeUpdates()
            } catch (e: Exception) {
                logger.e(e) { "Failed to start recording" }
                _uiState.update { it.copy(error = "Failed to start: ${e.message}") }
            }
        }
    }

    private fun startElapsedTimeUpdates() {
        viewModelScope.launch {
            while (_uiState.value.isRecording || _uiState.value.isPaused) {
                if (_uiState.value.isRecording) {
                    val elapsed = Clock.System.now().toEpochMilliseconds() - startTimeMs
                    _uiState.update { it.copy(elapsedTimeMs = elapsed) }
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    fun pauseRecording() {
        viewModelScope.launch {
            if (!transcriptionService.handlesAudioInternally) {
                audioRecorder.pauseRecording()
            }
            // Note: Android SpeechRecognizer doesn't support pause, so we just stop and restart
        }
    }

    fun resumeRecording() {
        viewModelScope.launch {
            if (!transcriptionService.handlesAudioInternally) {
                audioRecorder.resumeRecording()
            }
        }
    }

    fun stopRecording() {
        logger.d { "stopRecording() called, isRecording=${_uiState.value.isRecording}" }
        viewModelScope.launch {
            try {
                // Stop audio stream piping
                audioStreamJob?.cancel()
                audioStreamJob = null

                if (!transcriptionService.handlesAudioInternally) {
                    audioRecorder.stopRecording()
                }
                logger.d { "Calling transcriptionService.stopTranscription()" }
                val result = transcriptionService.stopTranscription()
                logger.d { "stopTranscription returned: ${result?.utterances?.size ?: 0} utterances" }

                result?.let { transcription ->
                    logger.d { "Saving recording with ${transcription.utterances.size} utterances" }
                    saveRecording(transcription)
                }
            } catch (e: Exception) {
                logger.e(e) { "Failed to stop recording" }
                _uiState.update { it.copy(error = "Failed to stop: ${e.message}") }
            }
        }
    }

    private suspend fun saveRecording(transcription: TranscriptionResult) {
        val recording = Recording(
            id = transcription.id,
            title = "",
            transcription = transcription,
            audioFilePath = null,
            createdAt = transcription.createdAt,
            updatedAt = transcription.createdAt,
            durationMs = transcription.durationMs
        )
        saveRecordingUseCase(recording)
    }

    fun shareTranscription() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSharing = true) }

            val text = buildTranscriptionText()
            shareService.shareText(text, "Fomovoi Transcription")

            _uiState.update { it.copy(isSharing = false) }
        }
    }

    private fun buildTranscriptionText(): String {
        return buildString {
            _uiState.value.utterances.forEach { utterance ->
                appendLine("[${utterance.speaker.label}]: ${utterance.text}")
                appendLine()
            }
            if (_uiState.value.partialText.isNotBlank()) {
                appendLine("[${_uiState.value.currentSpeaker?.label ?: "Speaker"}]: ${_uiState.value.partialText}...")
            }
        }
    }

    fun switchSpeaker() {
        // This would be called by the platform-specific transcription service
        // For now, emit through the transcription service
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setLanguage(language: SpeechLanguage) {
        viewModelScope.launch {
            try {
                transcriptionService.setLanguage(language)
            } catch (e: Exception) {
                logger.e(e) { "Failed to set language" }
                _uiState.update { it.copy(error = "Failed to change language: ${e.message}") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.release()
        transcriptionService.release()
    }
}

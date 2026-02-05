package com.fomovoi.feature.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.fomovoi.core.audio.AudioRecorder
import com.fomovoi.core.audio.RecordingState
import com.fomovoi.core.domain.model.Recording
import com.fomovoi.core.domain.usecase.SaveRecordingUseCase
import com.fomovoi.core.sharing.ShareService
import com.fomovoi.core.transcription.Speaker
import com.fomovoi.core.transcription.TranscriptionEvent
import com.fomovoi.core.transcription.TranscriptionResult
import com.fomovoi.core.transcription.TranscriptionService
import com.fomovoi.core.transcription.TranscriptionState
import com.fomovoi.core.transcription.Utterance
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
    val partialText: String = "",
    val utterances: List<Utterance> = emptyList(),
    val elapsedTimeMs: Long = 0,
    val isSharing: Boolean = false,
    val error: String? = null
) {
    val isRecording: Boolean
        get() = recordingState == RecordingState.RECORDING

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

    init {
        observeAudioState()
        observeTranscriptionState()
        observeTranscriptionEvents()
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
        viewModelScope.launch {
            try {
                audioRecorder.initialize()
                transcriptionService.initialize()
            } catch (e: Exception) {
                logger.e(e) { "Failed to initialize" }
                _uiState.update { it.copy(error = "Failed to initialize: ${e.message}") }
            }
        }
    }

    fun startRecording() {
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
                audioRecorder.startRecording()
                transcriptionService.startTranscription()

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
            audioRecorder.pauseRecording()
        }
    }

    fun resumeRecording() {
        viewModelScope.launch {
            audioRecorder.resumeRecording()
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            try {
                audioRecorder.stopRecording()
                val result = transcriptionService.stopTranscription()

                result?.let { transcription ->
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

    override fun onCleared() {
        super.onCleared()
        audioRecorder.release()
        transcriptionService.release()
    }
}

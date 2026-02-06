package app.s4h.nisafone.feature.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import app.s4h.nisafone.core.audio.AudioDevice
import app.s4h.nisafone.core.audio.AudioRecorder
import app.s4h.nisafone.core.audio.RecordingState
import app.s4h.nisafone.core.domain.model.Recording
import app.s4h.nisafone.core.domain.usecase.SaveRecordingUseCase
import app.s4h.nisafone.core.domain.usecase.UpdateRecordingUseCase
import app.s4h.nisafone.core.sharing.ShareResult
import app.s4h.nisafone.core.sharing.ShareService
import app.s4h.nisafone.feature.settings.EmailSettingsRepository
import app.s4h.nisafone.core.transcription.Speaker
import app.s4h.nisafone.core.transcription.SpeechLanguage
import app.s4h.nisafone.core.transcription.TranscriptionEvent
import app.s4h.nisafone.core.transcription.TranscriptionResult
import app.s4h.nisafone.core.transcription.TranscriptionService
import app.s4h.nisafone.core.transcription.TranscriptionState
import app.s4h.nisafone.core.transcription.Utterance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
    val error: String? = null,
    val titlePrefixes: List<String> = listOf("Recording"),
    val selectedTitlePrefix: String = "Recording",
    val showAddPrefixDialog: Boolean = false,
    val availableAudioDevices: List<AudioDevice> = emptyList(),
    val selectedAudioDevice: AudioDevice? = null
) {
    // Recording state considers both audio recorder and transcription service
    // (some transcription services handle audio internally)
    val isRecording: Boolean
        get() = recordingState == RecordingState.RECORDING ||
                transcriptionState == TranscriptionState.TRANSCRIBING

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
    private val saveRecordingUseCase: SaveRecordingUseCase,
    private val updateRecordingUseCase: UpdateRecordingUseCase,
    private val titlePrefixRepository: TitlePrefixRepository,
    private val emailSettingsRepository: EmailSettingsRepository
) : ViewModel() {

    private val logger = Logger.withTag("RecordingViewModel")

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private var startTimeMs: Long = 0
    private val collectedUtterances = mutableListOf<Utterance>()
    private var isInitialized = false
    private var currentRecordingId: String? = null

    private var audioStreamJob: kotlinx.coroutines.Job? = null

    init {
        observeAudioState()
        observeAudioDevices()
        observeTranscriptionState()
        observeTranscriptionEvents()
        observeLanguage()
        observeTitlePrefixes()
    }

    private fun observeAudioState() {
        viewModelScope.launch {
            audioRecorder.state.collect { state ->
                _uiState.update { it.copy(recordingState = state) }
            }
        }
    }

    private fun observeAudioDevices() {
        viewModelScope.launch {
            audioRecorder.availableDevices.collect { devices ->
                _uiState.update { it.copy(availableAudioDevices = devices) }
            }
        }
        viewModelScope.launch {
            audioRecorder.selectedDevice.collect { device ->
                _uiState.update { it.copy(selectedAudioDevice = device) }
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

    private fun observeTitlePrefixes() {
        viewModelScope.launch {
            titlePrefixRepository.prefixes.collect { prefixes ->
                _uiState.update { it.copy(titlePrefixes = prefixes) }
            }
        }
        viewModelScope.launch {
            titlePrefixRepository.selectedPrefix.collect { prefix ->
                _uiState.update { it.copy(selectedTitlePrefix = prefix) }
            }
        }
    }

    private fun observeTranscriptionEvents() {
        viewModelScope.launch {
            transcriptionService.events.collect { event ->
                when (event) {
                    is TranscriptionEvent.PartialResult -> {
                        _uiState.update { it.copy(partialText = event.text) }
                        // Save partial transcription to database
                        saveCurrentTranscription()
                    }
                    is TranscriptionEvent.FinalResult -> {
                        collectedUtterances.add(event.utterance)
                        _uiState.update {
                            it.copy(
                                utterances = collectedUtterances.toList(),
                                partialText = ""
                            )
                        }
                        // Save with final utterance
                        saveCurrentTranscription()
                    }
                    is TranscriptionEvent.Error -> {
                        logger.e { "Transcription error: ${event.message}" }
                        _uiState.update { it.copy(error = event.message) }
                    }
                }
            }
        }
    }

    private suspend fun saveCurrentTranscription() {
        val recordingId = currentRecordingId ?: return
        val state = _uiState.value

        // Build current transcription from utterances and partial text
        val allUtterances = collectedUtterances.toMutableList()
        if (state.partialText.isNotBlank()) {
            val speaker = state.currentSpeaker ?: Speaker(id = "speaker_1", label = "Speaker 1")
            allUtterances.add(
                Utterance(
                    id = "partial",
                    text = state.partialText,
                    speaker = speaker,
                    startTimeMs = 0,
                    endTimeMs = state.elapsedTimeMs
                )
            )
        }

        if (allUtterances.isEmpty()) return

        val now = Clock.System.now()
        val fullText = allUtterances.joinToString(" ") { it.text }

        val transcription = TranscriptionResult(
            id = recordingId,
            utterances = allUtterances,
            fullText = fullText,
            durationMs = state.elapsedTimeMs,
            createdAt = now,
            isComplete = false
        )

        val recording = Recording(
            id = recordingId,
            title = state.selectedTitlePrefix,
            transcription = transcription,
            createdAt = now,
            updatedAt = now,
            durationMs = state.elapsedTimeMs
        )

        try {
            updateRecordingUseCase(recording)
        } catch (e: Exception) {
            logger.e(e) { "Failed to save transcription progress" }
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

    @OptIn(ExperimentalUuidApi::class)
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
                val now = Clock.System.now()

                // Generate a new recording ID and create initial entry in database
                currentRecordingId = Uuid.random().toString()
                val initialRecording = Recording(
                    id = currentRecordingId!!,
                    title = _uiState.value.selectedTitlePrefix,
                    transcription = null,
                    createdAt = now,
                    updatedAt = now,
                    durationMs = 0
                )
                saveRecordingUseCase(initialRecording)
                logger.d { "Created initial recording entry: ${currentRecordingId}" }

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
            while (_uiState.value.isRecording) {
                val elapsed = Clock.System.now().toEpochMilliseconds() - startTimeMs
                _uiState.update { it.copy(elapsedTimeMs = elapsed) }
                kotlinx.coroutines.delay(100)
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

                // Save final recording with complete transcription
                val recordingId = currentRecordingId
                if (recordingId != null && result != null) {
                    logger.d { "Saving final recording with ${result.utterances.size} utterances" }
                    val finalTranscription = result.copy(
                        id = recordingId,
                        isComplete = true
                    )
                    saveFinalRecording(recordingId, finalTranscription)

                    // Auto-email if enabled
                    sendAutoEmail(finalTranscription)
                }
                currentRecordingId = null
            } catch (e: Exception) {
                logger.e(e) { "Failed to stop recording" }
                _uiState.update { it.copy(error = "Failed to stop: ${e.message}") }
            }
        }
    }

    private suspend fun saveFinalRecording(recordingId: String, transcription: TranscriptionResult) {
        val now = Clock.System.now()
        val recording = Recording(
            id = recordingId,
            title = _uiState.value.selectedTitlePrefix,
            transcription = transcription,
            createdAt = now,
            updatedAt = now,
            durationMs = transcription.durationMs
        )
        updateRecordingUseCase(recording)
    }

    private suspend fun sendAutoEmail(transcription: TranscriptionResult) {
        val autoEmailEnabled = emailSettingsRepository.autoEmailEnabled.value
        val emailAddress = emailSettingsRepository.emailAddress.value

        if (!autoEmailEnabled || emailAddress.isBlank()) {
            logger.d { "Auto-email disabled or no email address configured" }
            return
        }

        logger.d { "Sending auto-email to: $emailAddress" }
        val title = _uiState.value.selectedTitlePrefix
        val subject = "nisafone: $title"
        val body = buildTranscriptionText()

        when (val result = shareService.sendEmail(emailAddress, subject, body)) {
            is ShareResult.Success -> {
                logger.d { "Auto-email opened successfully" }
            }
            is ShareResult.Error -> {
                logger.e { "Auto-email failed: ${result.message}" }
                _uiState.update { it.copy(error = "Failed to send email: ${result.message}") }
            }
            is ShareResult.Cancelled -> {
                logger.d { "Auto-email cancelled" }
            }
        }
    }

    fun shareTranscription() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSharing = true) }

            val text = buildTranscriptionText()
            shareService.shareText(text, "Nisafone Transcription")

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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun selectAudioDevice(device: AudioDevice) {
        viewModelScope.launch {
            audioRecorder.selectDevice(device)
        }
    }

    fun selectTitlePrefix(prefix: String) {
        titlePrefixRepository.selectPrefix(prefix)
    }

    fun addTitlePrefix(prefix: String) {
        titlePrefixRepository.addPrefix(prefix)
        _uiState.update { it.copy(showAddPrefixDialog = false) }
    }

    fun showAddPrefixDialog() {
        _uiState.update { it.copy(showAddPrefixDialog = true) }
    }

    fun hideAddPrefixDialog() {
        _uiState.update { it.copy(showAddPrefixDialog = false) }
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

package app.s4h.fomovoi.core.transcription

import co.touchlab.kermit.Logger
import app.s4h.fomovoi.core.audio.AudioChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import platform.Foundation.NSUUID
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.Speech.SFSpeechRecognizerDelegateProtocol
import platform.darwin.NSObject

actual fun createTranscriptionService(): TranscriptionService = IosTranscriptionService()

class IosTranscriptionService : TranscriptionService {

    private val logger = Logger.withTag("IosTranscriptionService")
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var speechRecognizer: SFSpeechRecognizer? = null
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest? = null
    private var recognitionTask: SFSpeechRecognitionTask? = null

    private val utterances = mutableListOf<Utterance>()
    private var currentUtteranceStart: Long = 0
    private var sessionStartTime: Long = 0
    private var lastPartialText: String = ""

    private val _state = MutableStateFlow(TranscriptionState.IDLE)
    override val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 64)
    override val events: Flow<TranscriptionEvent> = _events.asSharedFlow()

    private val _currentSpeaker = MutableStateFlow<Speaker?>(null)
    override val currentSpeaker: StateFlow<Speaker?> = _currentSpeaker.asStateFlow()

    private val _currentLanguage = MutableStateFlow(SpeechLanguage.ENGLISH)
    override val currentLanguage: StateFlow<SpeechLanguage> = _currentLanguage.asStateFlow()

    private val _currentLanguageHint = MutableStateFlow(LanguageHint.AUTO_DETECT)
    override val currentLanguageHint: StateFlow<LanguageHint> = _currentLanguageHint.asStateFlow()

    override val availableLanguages: List<SpeechLanguage> = listOf(SpeechLanguage.ENGLISH)

    private val speakers = mutableMapOf<String, Speaker>()

    override suspend fun initialize() {
        logger.d { "Initializing iOS transcription service" }
        _state.value = TranscriptionState.INITIALIZING

        SFSpeechRecognizer.requestAuthorization { status ->
            scope.launch {
                when (status) {
                    SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized -> {
                        logger.d { "Speech recognition authorized" }
                        setupRecognizer()
                    }
                    SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusDenied -> {
                        logger.e { "Speech recognition denied" }
                        _state.value = TranscriptionState.ERROR
                        _events.emit(TranscriptionEvent.Error("Speech recognition permission denied"))
                    }
                    SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusRestricted -> {
                        logger.e { "Speech recognition restricted" }
                        _state.value = TranscriptionState.ERROR
                        _events.emit(TranscriptionEvent.Error("Speech recognition is restricted on this device"))
                    }
                    SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined -> {
                        logger.e { "Speech recognition status not determined" }
                        _state.value = TranscriptionState.ERROR
                    }
                    else -> {
                        logger.e { "Unknown authorization status" }
                        _state.value = TranscriptionState.ERROR
                    }
                }
            }
        }
    }

    private fun setupRecognizer() {
        speechRecognizer = SFSpeechRecognizer()

        speechRecognizer?.let { recognizer ->
            if (!recognizer.isAvailable()) {
                logger.e { "Speech recognizer not available" }
                _state.value = TranscriptionState.ERROR
                return
            }

            // Enable on-device recognition (iOS 13+)
            if (recognizer.supportsOnDeviceRecognition()) {
                logger.d { "On-device recognition supported" }
            }
        }

        // Initialize default speaker
        val defaultSpeaker = Speaker(id = "speaker_1", label = "Speaker 1")
        speakers[defaultSpeaker.id] = defaultSpeaker
        _currentSpeaker.value = defaultSpeaker

        _state.value = TranscriptionState.READY
        logger.d { "Transcription service initialized" }
    }

    override suspend fun startTranscription() {
        if (_state.value != TranscriptionState.READY) {
            logger.w { "Cannot start transcription in state: ${_state.value}" }
            return
        }

        logger.d { "Starting transcription" }
        utterances.clear()
        sessionStartTime = (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong()
        currentUtteranceStart = 0
        lastPartialText = ""

        recognitionRequest = SFSpeechAudioBufferRecognitionRequest().apply {
            shouldReportPartialResults = true
            // Enable on-device recognition
            speechRecognizer?.let { recognizer ->
                if (recognizer.supportsOnDeviceRecognition()) {
                    requiresOnDeviceRecognition = true
                }
            }
        }

        val request = recognitionRequest ?: return

        recognitionTask = speechRecognizer?.recognitionTaskWithRequest(request) { result, error ->
            if (error != null) {
                logger.e { "Recognition error: ${error.localizedDescription}" }
                scope.launch {
                    _events.emit(TranscriptionEvent.Error(error.localizedDescription ?: "Unknown error"))
                }
                return@recognitionTaskWithRequest
            }

            result?.let { speechResult ->
                val text = speechResult.bestTranscription.formattedString

                if (speechResult.isFinal) {
                    // Final result
                    val currentTime = (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong() - sessionStartTime
                    val speaker = _currentSpeaker.value ?: return@recognitionTaskWithRequest

                    val utterance = Utterance(
                        id = NSUUID().UUIDString,
                        text = text,
                        speaker = speaker,
                        startTimeMs = currentUtteranceStart,
                        endTimeMs = currentTime
                    )

                    utterances.add(utterance)

                    scope.launch {
                        _events.emit(TranscriptionEvent.FinalResult(utterance))
                    }

                    currentUtteranceStart = currentTime
                    lastPartialText = ""
                } else {
                    // Partial result
                    if (text != lastPartialText) {
                        lastPartialText = text
                        scope.launch {
                            _events.emit(TranscriptionEvent.PartialResult(text))
                        }
                    }
                }
            }
        }

        _state.value = TranscriptionState.TRANSCRIBING
    }

    override suspend fun processAudioChunk(chunk: AudioChunk) {
        // For iOS, we would need to convert AudioChunk to AVAudioPCMBuffer
        // and append it to the recognition request
        // This requires bridging to AVAudioFormat and AVAudioPCMBuffer
        // In a production app, this would be implemented with proper audio format conversion
    }

    override suspend fun stopTranscription(): TranscriptionResult? {
        logger.d { "Stopping transcription" }

        recognitionTask?.cancel()
        recognitionTask = null
        recognitionRequest?.endAudio()
        recognitionRequest = null

        _state.value = TranscriptionState.READY

        if (utterances.isEmpty()) {
            return null
        }

        val fullText = utterances.joinToString(" ") { it.text }
        val durationMs = utterances.lastOrNull()?.endTimeMs ?: 0

        return TranscriptionResult(
            id = NSUUID().UUIDString,
            utterances = utterances.toList(),
            fullText = fullText,
            durationMs = durationMs,
            createdAt = Clock.System.now(),
            isComplete = true
        )
    }

    override suspend fun setSpeakerLabel(speakerId: String, label: String) {
        speakers[speakerId]?.let { speaker ->
            val updated = speaker.copy(label = label)
            speakers[speakerId] = updated
            if (_currentSpeaker.value?.id == speakerId) {
                _currentSpeaker.value = updated
            }
        }
    }

    override suspend fun setLanguage(language: SpeechLanguage) {
        // iOS SFSpeechRecognizer uses system language
        _currentLanguage.value = language
    }

    override suspend fun setLanguageHint(hint: LanguageHint) {
        // iOS SFSpeechRecognizer doesn't support Whisper language hints
        _currentLanguageHint.value = hint
    }

    fun switchSpeaker() {
        val nextSpeakerId = "speaker_${speakers.size + 1}"
        val newSpeaker = speakers.getOrPut(nextSpeakerId) {
            Speaker(id = nextSpeakerId, label = "Speaker ${speakers.size + 1}")
        }
        _currentSpeaker.value = newSpeaker
        scope.launch {
            _events.emit(TranscriptionEvent.SpeakerChange(newSpeaker))
        }
    }

    override fun release() {
        logger.d { "Releasing transcription service" }
        scope.cancel()
        recognitionTask?.cancel()
        recognitionTask = null
        recognitionRequest = null
        speechRecognizer = null
    }
}

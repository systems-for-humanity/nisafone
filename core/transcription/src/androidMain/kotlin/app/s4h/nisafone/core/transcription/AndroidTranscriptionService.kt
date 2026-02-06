package app.s4h.nisafone.core.transcription

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import co.touchlab.kermit.Logger
import app.s4h.nisafone.core.audio.AudioChunk
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
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import java.util.Locale
import java.util.UUID

actual fun createTranscriptionService(): TranscriptionService {
    throw IllegalStateException("Use createAndroidTranscriptionService with Context")
}

fun createAndroidTranscriptionService(context: Context): TranscriptionService {
    return AndroidTranscriptionService(context)
}

class AndroidTranscriptionService(
    private val context: Context
) : TranscriptionService {

    companion object {
        private const val TAG = "NisafoneTranscription"
    }

    private val logger = Logger.withTag("AndroidTranscriptionService")
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Android SpeechRecognizer handles audio capture internally
    override val handlesAudioInternally: Boolean = true

    private var speechRecognizer: SpeechRecognizer? = null
    private val utterances = mutableListOf<Utterance>()
    private var currentUtteranceStart: Long = 0
    private var sessionStartTime: Long = 0
    private var isListening = false
    private var lastPartialResult: String = ""  // Track partial results to use on ERROR_NO_MATCH

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

    private val _translateToEnglish = MutableStateFlow(true)
    override val translateToEnglish: StateFlow<Boolean> = _translateToEnglish.asStateFlow()

    // Android SpeechRecognizer uses system language, limited language selection
    override val availableLanguages: List<SpeechLanguage> = listOf(SpeechLanguage.ENGLISH)

    private val speakers = mutableMapOf<String, Speaker>()

    override suspend fun initialize() {
        Log.d(TAG, "initialize() called")
        logger.d { "Initializing Android transcription service" }
        _state.value = TranscriptionState.IDLE

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            logger.e { "Speech recognition not available" }
            _state.value = TranscriptionState.ERROR
            _events.emit(TranscriptionEvent.Error("Speech recognition not available on this device"))
            return
        }
        Log.d(TAG, "Speech recognition is available")

        // SpeechRecognizer MUST be created on main thread
        withContext(Dispatchers.Main) {
            Log.d(TAG, "Creating SpeechRecognizer on main thread")
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
            Log.d(TAG, "SpeechRecognizer created: ${speechRecognizer != null}")
        }

        // Initialize default speaker
        val defaultSpeaker = Speaker(id = "speaker_1", label = "Speaker 1")
        speakers[defaultSpeaker.id] = defaultSpeaker
        _currentSpeaker.value = defaultSpeaker

        _state.value = TranscriptionState.READY
        Log.d(TAG, "Transcription service initialized, state=READY")
        logger.d { "Transcription service initialized" }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            logger.d { "Ready for speech" }
            isListening = true
            currentUtteranceStart = System.currentTimeMillis() - sessionStartTime
            lastPartialResult = ""  // Clear partial result for new listening session
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
            logger.d { "Beginning of speech" }
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Could use this for speaker change detection based on audio level
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            Log.d(TAG, "onBufferReceived: ${buffer?.size} bytes")
            // Raw audio buffer
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
            logger.d { "End of speech" }
            isListening = false
        }

        override fun onError(error: Int) {
            isListening = false
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                else -> "Unknown error: $error"
            }
            Log.e(TAG, "onError: $errorMessage (code: $error)")
            logger.e { "Recognition error: $errorMessage (code: $error)" }

            // Emit error for fatal errors only
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                scope.launch {
                    _events.emit(TranscriptionEvent.Error(errorMessage))
                }
                return
            }

            // Handle ERROR_NO_MATCH by using the last partial result if available
            // This is common when speech is detected but recognition can't get a final result
            if (error == SpeechRecognizer.ERROR_NO_MATCH && lastPartialResult.isNotBlank()) {
                Log.d(TAG, "Using last partial result as final: $lastPartialResult")
                val currentTime = System.currentTimeMillis() - sessionStartTime
                val speaker = _currentSpeaker.value

                if (speaker != null) {
                    val utterance = Utterance(
                        id = UUID.randomUUID().toString(),
                        text = lastPartialResult,
                        speaker = speaker,
                        startTimeMs = currentUtteranceStart,
                        endTimeMs = currentTime
                    )
                    utterances.add(utterance)
                    scope.launch {
                        _events.emit(TranscriptionEvent.FinalResult(utterance))
                    }
                }
                lastPartialResult = ""  // Clear after using
            }

            // Restart recognition for continuous listening
            if (_state.value == TranscriptionState.TRANSCRIBING) {
                // Restart immediately for better continuity
                mainHandler.post {
                    if (_state.value == TranscriptionState.TRANSCRIBING) {
                        logger.d { "Restarting speech recognition after error" }
                        startListeningInternal()
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            Log.d(TAG, "onResults called")
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()
            Log.d(TAG, "onResults: matches=$matches, text=$text")

            if (!text.isNullOrBlank()) {
                Log.d(TAG, "Final result: $text")
                logger.d { "Final result: $text" }

                val currentTime = System.currentTimeMillis() - sessionStartTime
                val speaker = _currentSpeaker.value ?: return

                val utterance = Utterance(
                    id = UUID.randomUUID().toString(),
                    text = text,
                    speaker = speaker,
                    startTimeMs = currentUtteranceStart,
                    endTimeMs = currentTime
                )

                utterances.add(utterance)

                scope.launch {
                    _events.emit(TranscriptionEvent.FinalResult(utterance))
                }
            }

            // Continue listening if still transcribing - restart immediately for continuity
            if (_state.value == TranscriptionState.TRANSCRIBING) {
                mainHandler.post {
                    if (_state.value == TranscriptionState.TRANSCRIBING) {
                        startListeningInternal()
                    }
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: return
            Log.d(TAG, "onPartialResults: $text")

            if (text.isNotBlank()) {
                lastPartialResult = text  // Track for use on ERROR_NO_MATCH
                scope.launch {
                    _events.emit(TranscriptionEvent.PartialResult(text))
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            logger.d { "Recognition event: $eventType" }
        }
    }

    override suspend fun startTranscription() {
        Log.d(TAG, "startTranscription() called, current state: ${_state.value}")
        if (_state.value != TranscriptionState.READY && _state.value != TranscriptionState.IDLE) {
            Log.w(TAG, "Cannot start transcription in state: ${_state.value}")
            logger.w { "Cannot start transcription in state: ${_state.value}" }
            return
        }

        Log.d(TAG, "Starting transcription")
        logger.d { "Starting transcription" }
        utterances.clear()
        sessionStartTime = System.currentTimeMillis()
        _state.value = TranscriptionState.TRANSCRIBING

        withContext(Dispatchers.Main) {
            Log.d(TAG, "Calling startListeningInternal on main thread")
            startListeningInternal()
        }
    }

    private fun startListeningInternal() {
        Log.d(TAG, "startListeningInternal() called, isListening=$isListening, speechRecognizer=${speechRecognizer != null}")
        if (isListening) {
            Log.d(TAG, "Already listening, skipping start")
            logger.d { "Already listening, skipping start" }
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Don't force offline - let it use network if needed
            // putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        Log.d(TAG, "Starting speech recognizer with language: ${Locale.getDefault().toLanguageTag()}")
        logger.d { "Starting speech recognizer with language: ${Locale.getDefault().toLanguageTag()}" }
        speechRecognizer?.startListening(intent)
        Log.d(TAG, "startListening() called on speechRecognizer")
    }

    override suspend fun processAudioChunk(chunk: AudioChunk) {
        // The Android SpeechRecognizer handles audio internally
        // This method is provided for alternative implementations (e.g., Sherpa-ONNX)
    }

    override suspend fun stopTranscription(): TranscriptionResult? {
        Log.d(TAG, "stopTranscription() called, utterances.size=${utterances.size}")
        logger.d { "Stopping transcription" }
        _state.value = TranscriptionState.READY
        isListening = false

        withContext(Dispatchers.Main) {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        }

        Log.d(TAG, "After stopping, utterances.size=${utterances.size}")
        if (utterances.isEmpty()) {
            Log.d(TAG, "No utterances to return, returning null")
            return null
        }

        val fullText = utterances.joinToString(" ") { it.text }
        val durationMs = utterances.lastOrNull()?.endTimeMs ?: 0

        return TranscriptionResult(
            id = UUID.randomUUID().toString(),
            utterances = utterances.toList(),
            fullText = fullText,
            durationMs = durationMs,
            createdAt = Clock.System.now(),
            isComplete = true
        )
    }


    override suspend fun setLanguage(language: SpeechLanguage) {
        // Android SpeechRecognizer uses system language
        // For full language support, use SherpaOnnxTranscriptionService
        _currentLanguage.value = language
        _events.emit(TranscriptionEvent.Error("Language selection requires Sherpa-ONNX service"))
    }

    override suspend fun setLanguageHint(hint: LanguageHint) {
        // Android SpeechRecognizer doesn't support language hints
        // This is only applicable to Whisper models in SherpaOnnxTranscriptionService
        _currentLanguageHint.value = hint
    }

    override suspend fun setTranslateToEnglish(translate: Boolean) {
        // Android SpeechRecognizer doesn't support translation
        // This is only applicable to Whisper models in SherpaOnnxTranscriptionService
        _translateToEnglish.value = translate
    }

    override fun release() {
        logger.d { "Releasing transcription service" }
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }
}

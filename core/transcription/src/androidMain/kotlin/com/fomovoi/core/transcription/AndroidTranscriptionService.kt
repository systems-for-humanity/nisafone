package com.fomovoi.core.transcription

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import co.touchlab.kermit.Logger
import com.fomovoi.core.audio.AudioChunk
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

    private val logger = Logger.withTag("AndroidTranscriptionService")
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private val utterances = mutableListOf<Utterance>()
    private var currentUtteranceStart: Long = 0
    private var sessionStartTime: Long = 0
    private var isListening = false

    private val _state = MutableStateFlow(TranscriptionState.IDLE)
    override val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 64)
    override val events: Flow<TranscriptionEvent> = _events.asSharedFlow()

    private val _currentSpeaker = MutableStateFlow<Speaker?>(null)
    override val currentSpeaker: StateFlow<Speaker?> = _currentSpeaker.asStateFlow()

    private val speakers = mutableMapOf<String, Speaker>()

    override suspend fun initialize() {
        logger.d { "Initializing Android transcription service" }
        _state.value = TranscriptionState.INITIALIZING

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            logger.e { "Speech recognition not available" }
            _state.value = TranscriptionState.ERROR
            _events.emit(TranscriptionEvent.Error("Speech recognition not available on this device"))
            return
        }

        // SpeechRecognizer MUST be created on main thread
        withContext(Dispatchers.Main) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
        }

        // Initialize default speaker
        val defaultSpeaker = Speaker(id = "speaker_1", label = "Speaker 1")
        speakers[defaultSpeaker.id] = defaultSpeaker
        _currentSpeaker.value = defaultSpeaker

        _state.value = TranscriptionState.READY
        logger.d { "Transcription service initialized" }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            logger.d { "Ready for speech" }
            isListening = true
            currentUtteranceStart = System.currentTimeMillis() - sessionStartTime
        }

        override fun onBeginningOfSpeech() {
            logger.d { "Beginning of speech" }
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Could use this for speaker change detection based on audio level
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Raw audio buffer
        }

        override fun onEndOfSpeech() {
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
            logger.e { "Recognition error: $errorMessage (code: $error)" }

            // Emit error for fatal errors only
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                scope.launch {
                    _events.emit(TranscriptionEvent.Error(errorMessage))
                }
                return
            }

            // Restart recognition for continuous listening
            if (_state.value == TranscriptionState.TRANSCRIBING) {
                // Small delay before restarting to avoid rapid restarts
                mainHandler.postDelayed({
                    if (_state.value == TranscriptionState.TRANSCRIBING) {
                        logger.d { "Restarting speech recognition after error" }
                        startListeningInternal()
                    }
                }, 100)
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()

            if (!text.isNullOrBlank()) {
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

            // Continue listening if still transcribing
            if (_state.value == TranscriptionState.TRANSCRIBING) {
                mainHandler.postDelayed({
                    if (_state.value == TranscriptionState.TRANSCRIBING) {
                        startListeningInternal()
                    }
                }, 100)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: return

            if (text.isNotBlank()) {
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
        if (_state.value != TranscriptionState.READY && _state.value != TranscriptionState.IDLE) {
            logger.w { "Cannot start transcription in state: ${_state.value}" }
            return
        }

        logger.d { "Starting transcription" }
        utterances.clear()
        sessionStartTime = System.currentTimeMillis()
        _state.value = TranscriptionState.TRANSCRIBING

        withContext(Dispatchers.Main) {
            startListeningInternal()
        }
    }

    private fun startListeningInternal() {
        if (isListening) {
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

        logger.d { "Starting speech recognizer with language: ${Locale.getDefault().toLanguageTag()}" }
        speechRecognizer?.startListening(intent)
    }

    override suspend fun processAudioChunk(chunk: AudioChunk) {
        // The Android SpeechRecognizer handles audio internally
        // This method is provided for alternative implementations (e.g., Sherpa-ONNX)
    }

    override suspend fun stopTranscription(): TranscriptionResult? {
        logger.d { "Stopping transcription" }
        _state.value = TranscriptionState.READY
        isListening = false

        withContext(Dispatchers.Main) {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        }

        if (utterances.isEmpty()) {
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

    override suspend fun setSpeakerLabel(speakerId: String, label: String) {
        speakers[speakerId]?.let { speaker ->
            val updated = speaker.copy(label = label)
            speakers[speakerId] = updated
            if (_currentSpeaker.value?.id == speakerId) {
                _currentSpeaker.value = updated
            }
        }
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
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }
}

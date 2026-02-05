package com.fomovoi.core.transcription

import android.content.Context
import android.util.Log
import co.touchlab.kermit.Logger
import com.fomovoi.core.audio.AudioChunk
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Transcription service using Sherpa-ONNX Whisper models for on-device speech recognition.
 * Uses batch processing - audio is accumulated during recording and transcribed when stopped.
 */
fun createSherpaOnnxTranscriptionService(context: Context): TranscriptionService {
    return SherpaOnnxTranscriptionService(context)
}

class SherpaOnnxTranscriptionService(
    private val context: Context
) : TranscriptionService {

    companion object {
        private const val TAG = "SherpaOnnxTranscription"
    }

    private val logger = Logger.withTag("SherpaOnnxTranscriptionService")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val modelManager = ModelManager(context)

    private var recognizer: OfflineRecognizer? = null
    private var currentModel: SpeechModel? = null
    private val audioBuffer = mutableListOf<FloatArray>()
    private var sessionStartTime: Long = 0

    private val _state = MutableStateFlow(TranscriptionState.IDLE)
    override val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 64)
    override val events: Flow<TranscriptionEvent> = _events.asSharedFlow()

    private val _currentSpeaker = MutableStateFlow<Speaker?>(null)
    override val currentSpeaker: StateFlow<Speaker?> = _currentSpeaker.asStateFlow()

    private val _currentLanguage = MutableStateFlow(SpeechLanguage.ENGLISH)
    override val currentLanguage: StateFlow<SpeechLanguage> = _currentLanguage.asStateFlow()

    override val availableLanguages: List<SpeechLanguage> = SpeechLanguage.entries

    private val speakers = mutableMapOf<String, Speaker>()

    override val handlesAudioInternally: Boolean = false

    override suspend fun initialize() {
        Log.d(TAG, "initialize() called")
        logger.d { "Initializing Sherpa-ONNX Whisper transcription service" }
        _state.value = TranscriptionState.INITIALIZING

        try {
            // First discover models from Hugging Face to get accurate file sizes
            modelManager.discoverModels()

            val selectedModel = modelManager.getSelectedModel()

            if (selectedModel == null) {
                Log.w(TAG, "No model selected, checking for downloaded models...")
                // Try to find any downloaded Whisper model
                val availableModels = modelManager.getAvailableModels()
                val downloadedWhisper = availableModels.find {
                    it.isDownloaded && (it.type == SpeechModelType.WHISPER_TINY || it.type == SpeechModelType.WHISPER_SMALL)
                }

                if (downloadedWhisper != null) {
                    Log.d(TAG, "Found downloaded model: ${downloadedWhisper.displayName}")
                    modelManager.setSelectedModel(downloadedWhisper)
                    initializeWithModel(downloadedWhisper)
                } else {
                    Log.w(TAG, "No Whisper model downloaded. Please download a model from Settings.")
                    _state.value = TranscriptionState.ERROR
                    _events.emit(TranscriptionEvent.Error("No model downloaded. Please download a model from Settings."))
                    return
                }
            } else {
                initializeWithModel(selectedModel)
            }

            _state.value = TranscriptionState.READY
            Log.d(TAG, "Sherpa-ONNX Whisper transcription service initialized")
            logger.d { "Transcription service initialized" }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}", e)
            logger.e(e) { "Failed to initialize Sherpa-ONNX Whisper" }
            _state.value = TranscriptionState.ERROR
            _events.emit(TranscriptionEvent.Error("Failed to initialize: ${e.message}"))
        }
    }

    private fun initializeWithModel(model: SpeechModel) {
        Log.d(TAG, "Initializing with model: ${model.displayName}")

        if (model.type != SpeechModelType.WHISPER_TINY && model.type != SpeechModelType.WHISPER_SMALL) {
            throw IllegalArgumentException("Only Whisper models are supported. Selected: ${model.type}")
        }

        val modelDir = modelManager.getModelDir(model)
        if (!modelManager.isModelDownloaded(model)) {
            throw IllegalStateException("Model not downloaded: ${model.displayName}")
        }

        // Release existing recognizer
        recognizer?.release()
        recognizer = null

        val config = createWhisperConfig(modelDir, model)
        recognizer = OfflineRecognizer(config = config)
        currentModel = model
        _currentLanguage.value = model.language

        // Initialize default speaker
        val defaultSpeaker = Speaker(id = "speaker_1", label = "Speaker 1")
        speakers[defaultSpeaker.id] = defaultSpeaker
        _currentSpeaker.value = defaultSpeaker

        Log.d(TAG, "OfflineRecognizer created for ${model.displayName}")
    }

    private fun createWhisperConfig(modelDir: File, model: SpeechModel): OfflineRecognizerConfig {
        // Get file names based on model type
        val (encoderName, decoderName, tokensName) = when (model.type) {
            SpeechModelType.WHISPER_TINY -> Triple(
                "tiny.en-encoder.int8.onnx",
                "tiny.en-decoder.int8.onnx",
                "tiny.en-tokens.txt"
            )
            SpeechModelType.WHISPER_SMALL -> Triple(
                "small.en-encoder.int8.onnx",
                "small.en-decoder.int8.onnx",
                "small.en-tokens.txt"
            )
            else -> throw IllegalArgumentException("Unsupported model type: ${model.type}")
        }

        val whisperConfig = OfflineWhisperModelConfig(
            encoder = File(modelDir, encoderName).absolutePath,
            decoder = File(modelDir, decoderName).absolutePath,
            language = "en",
            task = "transcribe"
        )

        val modelConfig = OfflineModelConfig(
            whisper = whisperConfig,
            tokens = File(modelDir, tokensName).absolutePath,
            numThreads = 2,
            debug = false,
            provider = "cpu"
        )

        return OfflineRecognizerConfig(
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )
    }

    override suspend fun setLanguage(language: SpeechLanguage) {
        // For Whisper, language is determined by the model
        // Find a downloaded model for this language
        val availableModels = modelManager.getAvailableModels()
        val modelForLanguage = availableModels.find {
            it.isDownloaded && it.language == language &&
            (it.type == SpeechModelType.WHISPER_TINY || it.type == SpeechModelType.WHISPER_SMALL)
        }

        if (modelForLanguage != null) {
            Log.d(TAG, "Switching to model for ${language.displayName}: ${modelForLanguage.displayName}")
            modelManager.setSelectedModel(modelForLanguage)

            val wasTranscribing = _state.value == TranscriptionState.TRANSCRIBING
            if (wasTranscribing) {
                stopTranscription()
            }

            _state.value = TranscriptionState.INITIALIZING
            try {
                initializeWithModel(modelForLanguage)
                _state.value = TranscriptionState.READY
                _events.emit(TranscriptionEvent.Error("Switched to ${modelForLanguage.displayName}"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch model: ${e.message}", e)
                _state.value = TranscriptionState.ERROR
                _events.emit(TranscriptionEvent.Error("Failed to switch model: ${e.message}"))
            }
        } else {
            _events.emit(TranscriptionEvent.Error("No ${language.displayName} model downloaded"))
        }
    }

    override suspend fun startTranscription() {
        Log.d(TAG, "startTranscription() called, current state: ${_state.value}")

        if (_state.value != TranscriptionState.READY && _state.value != TranscriptionState.IDLE) {
            Log.w(TAG, "Cannot start transcription in state: ${_state.value}")
            return
        }

        if (recognizer == null) {
            Log.e(TAG, "Recognizer not initialized")
            _events.emit(TranscriptionEvent.Error("No model loaded. Please download a model from Settings."))
            return
        }

        Log.d(TAG, "Starting transcription with ${currentModel?.displayName}")
        audioBuffer.clear()
        sessionStartTime = System.currentTimeMillis()

        _state.value = TranscriptionState.TRANSCRIBING

        scope.launch {
            _events.emit(TranscriptionEvent.Error("Recording... (transcription will run when you stop)"))
        }
    }

    override suspend fun processAudioChunk(chunk: AudioChunk) {
        if (_state.value != TranscriptionState.TRANSCRIBING) return

        // Convert bytes to float samples
        val samples = FloatArray(chunk.data.size / 2)
        val buffer = ByteBuffer.wrap(chunk.data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) {
            samples[i] = buffer.getShort().toFloat() / 32768.0f
        }

        // Accumulate audio for batch processing
        audioBuffer.add(samples)

        // Emit periodic status updates
        val durationSecs = audioBuffer.sumOf { it.size } / 16000
        if (durationSecs > 0 && durationSecs % 5 == 0) {
            scope.launch {
                _events.emit(TranscriptionEvent.PartialResult("Recording: ${durationSecs}s..."))
            }
        }
    }

    override suspend fun stopTranscription(): TranscriptionResult? {
        Log.d(TAG, "stopTranscription() called, audioBuffer chunks: ${audioBuffer.size}")
        logger.d { "Stopping transcription" }

        if (audioBuffer.isEmpty()) {
            Log.d(TAG, "No audio recorded")
            _state.value = TranscriptionState.READY
            return null
        }

        val currentRecognizer = recognizer
        if (currentRecognizer == null) {
            Log.e(TAG, "Recognizer is null")
            _state.value = TranscriptionState.READY
            return null
        }

        // Combine all audio chunks
        val totalSamples = audioBuffer.sumOf { it.size }
        val allSamples = FloatArray(totalSamples)
        var offset = 0
        for (chunk in audioBuffer) {
            chunk.copyInto(allSamples, offset)
            offset += chunk.size
        }

        Log.d(TAG, "Processing ${totalSamples} samples (${totalSamples / 16000.0}s of audio)")

        scope.launch {
            _events.emit(TranscriptionEvent.PartialResult("Transcribing..."))
        }

        // Run Whisper transcription
        return withContext(Dispatchers.Default) {
            try {
                val stream = currentRecognizer.createStream()
                stream.acceptWaveform(allSamples, 16000)
                currentRecognizer.decode(stream)

                val result = currentRecognizer.getResult(stream)
                val text = result.text.trim()

                Log.d(TAG, "Transcription result: $text")

                audioBuffer.clear()
                _state.value = TranscriptionState.READY

                if (text.isEmpty()) {
                    Log.d(TAG, "Empty transcription result")
                    return@withContext null
                }

                val speaker = _currentSpeaker.value ?: Speaker(id = "speaker_1", label = "Speaker 1")
                val durationMs = System.currentTimeMillis() - sessionStartTime

                val utterance = Utterance(
                    id = UUID.randomUUID().toString(),
                    text = text,
                    speaker = speaker,
                    startTimeMs = 0,
                    endTimeMs = durationMs
                )

                scope.launch {
                    _events.emit(TranscriptionEvent.FinalResult(utterance))
                }

                TranscriptionResult(
                    id = UUID.randomUUID().toString(),
                    utterances = listOf(utterance),
                    fullText = text,
                    durationMs = durationMs,
                    createdAt = Clock.System.now(),
                    isComplete = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed: ${e.message}", e)
                audioBuffer.clear()
                _state.value = TranscriptionState.READY
                scope.launch {
                    _events.emit(TranscriptionEvent.Error("Transcription failed: ${e.message}"))
                }
                null
            }
        }
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

    override fun release() {
        Log.d(TAG, "Releasing Sherpa-ONNX Whisper transcription service")
        logger.d { "Releasing transcription service" }
        scope.cancel()
        audioBuffer.clear()
        recognizer?.release()
        recognizer = null
    }
}

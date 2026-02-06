package app.s4h.nisafone.core.transcription

import android.content.Context
import android.os.StrictMode
import android.util.Log
import co.touchlab.kermit.Logger
import app.s4h.nisafone.core.audio.AudioChunk
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.datetime.Clock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Transcription service using Sherpa-ONNX Whisper models for on-device speech recognition.
 * Uses batch processing - audio is accumulated during recording and transcribed when stopped.
 */
fun createSherpaOnnxTranscriptionService(context: Context, modelManager: ModelManager): TranscriptionService {
    return SherpaOnnxTranscriptionService(context, modelManager)
}

class SherpaOnnxTranscriptionService(
    private val context: Context,
    private val modelManager: ModelManager
) : TranscriptionService {

    companion object {
        private const val TAG = "SherpaOnnxTranscription"
        private const val PREFS_NAME = "transcription_prefs"
        private const val PREF_LANGUAGE_HINT = "language_hint"
        private const val PREF_TRANSLATE_TO_ENGLISH = "translate_to_english"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_DURATION_SECS = 28 // Whisper handles ~30s max, use 28s for safety
    }

    private val logger = Logger.withTag("SherpaOnnxTranscriptionService")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val prefs by lazy {
        val oldPolicy = StrictMode.allowThreadDiskReads()
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } finally {
            StrictMode.setThreadPolicy(oldPolicy)
        }
    }

    private var recognizer: OfflineRecognizer? = null
    private var currentModel: SpeechModel? = null
    private val audioBuffer = mutableListOf<FloatArray>()
    private var sessionStartTime: Long = 0

    // Background transcription tracking
    private val transcriptionResults = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private var currentChunkIndex = 0
    private var samplesInCurrentChunk = 0
    private val chunkSizeSamples = CHUNK_DURATION_SECS * SAMPLE_RATE
    private val transcriptionMutex = Mutex()

    private val _state = MutableStateFlow(TranscriptionState.IDLE)
    override val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 64)
    override val events: Flow<TranscriptionEvent> = _events.asSharedFlow()

    private val _currentSpeaker = MutableStateFlow<Speaker?>(null)
    override val currentSpeaker: StateFlow<Speaker?> = _currentSpeaker.asStateFlow()

    private val _currentLanguage = MutableStateFlow(SpeechLanguage.ENGLISH)
    override val currentLanguage: StateFlow<SpeechLanguage> = _currentLanguage.asStateFlow()

    // Initialize with default, actual value loaded lazily
    private val _currentLanguageHint = MutableStateFlow(LanguageHint.AUTO_DETECT)
    override val currentLanguageHint: StateFlow<LanguageHint> = _currentLanguageHint.asStateFlow()

    private val _translateToEnglish = MutableStateFlow(true)
    override val translateToEnglish: StateFlow<Boolean> = _translateToEnglish.asStateFlow()

    override val availableLanguages: List<SpeechLanguage> = SpeechLanguage.entries

    private val speakers = mutableMapOf<String, Speaker>()

    override val handlesAudioInternally: Boolean = false

    private var languageHintLoaded = false

    private fun loadLanguageHint(): LanguageHint {
        if (!languageHintLoaded) {
            languageHintLoaded = true
            val oldPolicy = StrictMode.allowThreadDiskReads()
            try {
                val code = prefs.getString(PREF_LANGUAGE_HINT, "") ?: ""
                _currentLanguageHint.value = LanguageHint.fromCode(code) ?: LanguageHint.AUTO_DETECT
            } finally {
                StrictMode.setThreadPolicy(oldPolicy)
            }
        }
        return _currentLanguageHint.value
    }

    private fun saveLanguageHint(hint: LanguageHint) {
        prefs.edit().putString(PREF_LANGUAGE_HINT, hint.code).apply()
    }

    private var translateSettingLoaded = false

    private fun loadTranslateSetting(): Boolean {
        if (!translateSettingLoaded) {
            translateSettingLoaded = true
            val oldPolicy = StrictMode.allowThreadDiskReads()
            try {
                _translateToEnglish.value = prefs.getBoolean(PREF_TRANSLATE_TO_ENGLISH, true)
            } finally {
                StrictMode.setThreadPolicy(oldPolicy)
            }
        }
        return _translateToEnglish.value
    }

    private fun saveTranslateSetting(translate: Boolean) {
        prefs.edit().putBoolean(PREF_TRANSLATE_TO_ENGLISH, translate).apply()
    }

    override suspend fun initialize() {
        Log.d(TAG, "initialize() called")
        logger.d { "Initializing Sherpa-ONNX Whisper transcription service" }
        _state.value = TranscriptionState.IDLE

        try {
            // Run heavy initialization work off the main thread
            withContext(Dispatchers.IO) {
                // Load settings from prefs (disk I/O)
                loadLanguageHint()
                loadTranslateSetting()

                // First discover models from Hugging Face to get accurate file sizes
                modelManager.discoverModels()

                val selectedModel = modelManager.getSelectedModel()

                if (selectedModel == null) {
                    Log.w(TAG, "No model selected, checking for downloaded models...")
                    // Try to find any downloaded Whisper model (prefer smaller models)
                    val availableModels = modelManager.getAvailableModels()
                    val downloadedWhisper = availableModels
                        .filter { it.isDownloaded }
                        .minByOrNull { it.totalSizeBytes }

                    if (downloadedWhisper != null) {
                        Log.d(TAG, "Found downloaded model: ${downloadedWhisper.displayName}")
                        modelManager.setSelectedModel(downloadedWhisper)
                        initializeWithModel(downloadedWhisper)
                    } else {
                        Log.w(TAG, "No Whisper model downloaded. Please download a model from Settings.")
                        _state.value = TranscriptionState.ERROR
                        _events.emit(TranscriptionEvent.Error("No model downloaded. Please download a model from Settings."))
                        return@withContext
                    }
                } else {
                    initializeWithModel(selectedModel)
                }
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
        // Use the model's id to construct file names dynamically
        val prefix = model.id.removePrefix("whisper-")
        val encoderName = "$prefix-encoder.int8.onnx"
        val decoderName = "$prefix-decoder.int8.onnx"
        val tokensName = "$prefix-tokens.txt"

        // Determine language setting for Whisper
        // For English-only models, always use "en"
        // For multilingual models, use the language hint (or empty for auto-detect)
        val whisperLanguage = if (model.language == SpeechLanguage.ENGLISH) {
            "en"
        } else {
            _currentLanguageHint.value.code
        }

        // Determine task: translate to English or transcribe in original language
        // Translation only applies to multilingual models
        val whisperTask = if (model.language != SpeechLanguage.ENGLISH && _translateToEnglish.value) {
            "translate"
        } else {
            "transcribe"
        }

        Log.d(TAG, "Creating Whisper config with language: '$whisperLanguage' (hint: ${_currentLanguageHint.value.displayName}), task: $whisperTask")

        val whisperConfig = OfflineWhisperModelConfig(
            encoder = File(modelDir, encoderName).absolutePath,
            decoder = File(modelDir, decoderName).absolutePath,
            language = whisperLanguage,
            task = whisperTask
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
        // Find a downloaded model for this language (prefer smaller models)
        val availableModels = modelManager.getAvailableModels()
        val modelForLanguage = availableModels
            .filter { it.isDownloaded && it.language == language }
            .minByOrNull { it.totalSizeBytes }

        if (modelForLanguage != null) {
            Log.d(TAG, "Switching to model for ${language.displayName}: ${modelForLanguage.displayName}")
            modelManager.setSelectedModel(modelForLanguage)

            val wasTranscribing = _state.value == TranscriptionState.TRANSCRIBING
            if (wasTranscribing) {
                stopTranscription()
            }

            _state.value = TranscriptionState.IDLE
            try {
                withContext(Dispatchers.IO) {
                    initializeWithModel(modelForLanguage)
                }
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

    override suspend fun setLanguageHint(hint: LanguageHint) {
        Log.d(TAG, "Setting language hint to: ${hint.displayName} (${hint.code})")
        _currentLanguageHint.value = hint
        saveLanguageHint(hint)

        // Reinitialize recognizer if using a multilingual model to apply the new hint
        val model = currentModel
        if (model != null && model.language == SpeechLanguage.MULTILINGUAL) {
            val wasTranscribing = _state.value == TranscriptionState.TRANSCRIBING
            if (wasTranscribing) {
                stopTranscription()
            }

            _state.value = TranscriptionState.IDLE
            try {
                withContext(Dispatchers.IO) {
                    initializeWithModel(model)
                }
                _state.value = TranscriptionState.READY
                _events.emit(TranscriptionEvent.Error("Language hint set to ${hint.displayName}"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reinitialize with new language hint: ${e.message}", e)
                _state.value = TranscriptionState.ERROR
                _events.emit(TranscriptionEvent.Error("Failed to apply language hint: ${e.message}"))
            }
        }
    }

    override suspend fun setTranslateToEnglish(translate: Boolean) {
        Log.d(TAG, "Setting translate to English: $translate")
        _translateToEnglish.value = translate
        saveTranslateSetting(translate)

        // Reinitialize recognizer if using a multilingual model to apply the new setting
        val model = currentModel
        if (model != null && model.language != SpeechLanguage.ENGLISH) {
            val wasTranscribing = _state.value == TranscriptionState.TRANSCRIBING
            if (wasTranscribing) {
                stopTranscription()
            }

            _state.value = TranscriptionState.IDLE
            try {
                withContext(Dispatchers.IO) {
                    initializeWithModel(model)
                }
                _state.value = TranscriptionState.READY
                val message = if (translate) "Translation to English enabled" else "Original language transcription enabled"
                _events.emit(TranscriptionEvent.Error(message))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reinitialize with new translate setting: ${e.message}", e)
                _state.value = TranscriptionState.ERROR
                _events.emit(TranscriptionEvent.Error("Failed to apply translate setting: ${e.message}"))
            }
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
        transcriptionResults.clear()
        currentChunkIndex = 0
        samplesInCurrentChunk = 0
        sessionStartTime = System.currentTimeMillis()

        _state.value = TranscriptionState.TRANSCRIBING
    }

    override suspend fun processAudioChunk(chunk: AudioChunk) {
        if (_state.value != TranscriptionState.TRANSCRIBING) return

        // Convert bytes to float samples
        val samples = FloatArray(chunk.data.size / 2)
        val buffer = ByteBuffer.wrap(chunk.data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) {
            samples[i] = buffer.getShort().toFloat() / 32768.0f
        }

        // Accumulate audio
        transcriptionMutex.withLock {
            audioBuffer.add(samples)
            samplesInCurrentChunk += samples.size
        }

        // Check if we have enough audio for a chunk
        if (samplesInCurrentChunk >= chunkSizeSamples) {
            transcriptionMutex.withLock {
                // Extract chunk for transcription
                val chunkToTranscribe = extractChunkSamples()
                if (chunkToTranscribe != null) {
                    val chunkIndex = currentChunkIndex++
                    startBackgroundTranscription(chunkIndex, chunkToTranscribe)
                }
            }
        }
    }

    private fun extractChunkSamples(): FloatArray? {
        if (audioBuffer.isEmpty()) return null

        // Combine audio buffer into single array
        val totalSamples = audioBuffer.sumOf { it.size }
        if (totalSamples < chunkSizeSamples) return null

        val allSamples = FloatArray(totalSamples)
        var offset = 0
        for (chunk in audioBuffer) {
            chunk.copyInto(allSamples, offset)
            offset += chunk.size
        }

        // Extract one chunk worth of samples
        val chunkSamples = allSamples.copyOfRange(0, chunkSizeSamples)

        // Keep remaining samples in buffer
        val remaining = allSamples.copyOfRange(chunkSizeSamples, totalSamples)
        audioBuffer.clear()
        if (remaining.isNotEmpty()) {
            audioBuffer.add(remaining)
        }
        samplesInCurrentChunk = remaining.size

        return chunkSamples
    }

    private fun startBackgroundTranscription(chunkIndex: Int, samples: FloatArray) {
        val currentRecognizer = recognizer ?: return
        val deferred = CompletableDeferred<String>()
        transcriptionResults[chunkIndex] = deferred

        Log.d(TAG, "Starting background transcription for chunk $chunkIndex (${samples.size / SAMPLE_RATE}s)")

        scope.launch(Dispatchers.Default) {
            try {
                val text = transcribeChunk(currentRecognizer, samples)
                Log.d(TAG, "Chunk $chunkIndex transcribed: ${text.take(50)}...")
                deferred.complete(text)

                // Emit partial result with completed transcriptions so far
                emitPartialTranscription()
            } catch (e: Exception) {
                Log.e(TAG, "Background transcription failed for chunk $chunkIndex: ${e.message}", e)
                deferred.complete("") // Complete with empty string on error
            }
        }
    }

    private suspend fun emitPartialTranscription() {
        val completedTexts = transcriptionResults.entries
            .filter { it.value.isCompleted }
            .sortedBy { it.key }
            .mapNotNull { runCatching { it.value.getCompleted() }.getOrNull() }
            .filter { it.isNotEmpty() }

        if (completedTexts.isNotEmpty()) {
            val partialText = completedTexts.joinToString(" ")
            _events.emit(TranscriptionEvent.PartialResult(partialText))
        }
    }

    override suspend fun stopTranscription(): TranscriptionResult? {
        Log.d(TAG, "stopTranscription() called, audioBuffer chunks: ${audioBuffer.size}, pending transcriptions: ${transcriptionResults.size}")
        logger.d { "Stopping transcription" }

        // Set state to READY immediately so UI updates (timer stops)
        _state.value = TranscriptionState.READY

        val currentRecognizer = recognizer
        if (currentRecognizer == null) {
            Log.e(TAG, "Recognizer is null")
            return null
        }

        return withContext(Dispatchers.Default) {
            try {
                // Transcribe any remaining audio in the buffer
                val remainingSamples = transcriptionMutex.withLock {
                    if (audioBuffer.isEmpty()) {
                        null
                    } else {
                        val totalSamples = audioBuffer.sumOf { it.size }
                        val allSamples = FloatArray(totalSamples)
                        var offset = 0
                        for (chunk in audioBuffer) {
                            chunk.copyInto(allSamples, offset)
                            offset += chunk.size
                        }
                        audioBuffer.clear()
                        allSamples
                    }
                }

                // Transcribe remaining audio if any
                if (remainingSamples != null && remainingSamples.isNotEmpty()) {
                    val chunkIndex = currentChunkIndex++
                    Log.d(TAG, "Transcribing final chunk $chunkIndex (${remainingSamples.size / SAMPLE_RATE}s)")

                    val finalText = transcribeChunk(currentRecognizer, remainingSamples)
                    val deferred = CompletableDeferred<String>()
                    deferred.complete(finalText)
                    transcriptionResults[chunkIndex] = deferred
                }

                // Wait for all background transcriptions to complete
                Log.d(TAG, "Waiting for ${transcriptionResults.size} transcriptions to complete...")
                val allTexts = transcriptionResults.entries
                    .sortedBy { it.key }
                    .map { it.value.await() }
                    .filter { it.isNotEmpty() }

                val text = allTexts.joinToString(" ")
                Log.d(TAG, "Final transcription result (${allTexts.size} chunks): $text")

                // Clear for next session
                transcriptionResults.clear()

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
                transcriptionResults.clear()
                scope.launch {
                    _events.emit(TranscriptionEvent.Error("Transcription failed: ${e.message}"))
                }
                null
            }
        }
    }

    private fun transcribeChunk(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        recognizer.decode(stream)
        return recognizer.getResult(stream).text.trim()
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

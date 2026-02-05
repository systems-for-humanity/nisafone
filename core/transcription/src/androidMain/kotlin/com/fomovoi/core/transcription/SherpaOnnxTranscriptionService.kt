package com.fomovoi.core.transcription

import android.content.Context
import android.util.Log
import co.touchlab.kermit.Logger
import com.fomovoi.core.audio.AudioChunk
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Transcription service using Sherpa-ONNX for continuous, on-device speech recognition.
 * Uses streaming Zipformer transducer model for real-time transcription.
 * Models are downloaded on first use (~122MB total).
 */
fun createSherpaOnnxTranscriptionService(context: Context): TranscriptionService {
    return SherpaOnnxTranscriptionService(context)
}

class SherpaOnnxTranscriptionService(
    private val context: Context
) : TranscriptionService {

    companion object {
        private const val TAG = "SherpaOnnxTranscription"
        private const val MODEL_DIR = "sherpa-onnx-models"
        private const val MODEL_BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21/resolve/main"

        // Model files with expected sizes for verification
        // Using non-quantized models for better accuracy (~296MB total)
        private val MODEL_FILES = listOf(
            ModelFile("encoder-epoch-99-avg-1.onnx", 292_543_537L),
            ModelFile("decoder-epoch-99-avg-1.onnx", 2_093_080L),
            ModelFile("joiner-epoch-99-avg-1.onnx", 1_026_462L),
            ModelFile("tokens.txt", 5_048L)
        )

        private data class ModelFile(val name: String, val expectedSize: Long)
    }

    private val logger = Logger.withTag("SherpaOnnxTranscriptionService")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private val utterances = mutableListOf<Utterance>()
    private var sessionStartTime: Long = 0
    private var lastText: String = ""

    private val _state = MutableStateFlow(TranscriptionState.IDLE)
    override val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 64)
    override val events: Flow<TranscriptionEvent> = _events.asSharedFlow()

    private val _currentSpeaker = MutableStateFlow<Speaker?>(null)
    override val currentSpeaker: StateFlow<Speaker?> = _currentSpeaker.asStateFlow()

    private val speakers = mutableMapOf<String, Speaker>()

    // Sherpa-ONNX handles audio capture externally via processAudioChunk
    override val handlesAudioInternally: Boolean = false

    override suspend fun initialize() {
        Log.d(TAG, "initialize() called")
        logger.d { "Initializing Sherpa-ONNX transcription service" }
        _state.value = TranscriptionState.INITIALIZING

        try {
            val modelDir = File(context.filesDir, MODEL_DIR)

            // Check if models need to be downloaded
            if (!areModelsReady(modelDir)) {
                Log.d(TAG, "Models not found or incomplete, downloading...")
                downloadModels(modelDir)
            }

            // Initialize recognizer
            val config = createRecognizerConfig(modelDir)
            recognizer = OnlineRecognizer(config = config)
            Log.d(TAG, "OnlineRecognizer created")

            // Initialize default speaker
            val defaultSpeaker = Speaker(id = "speaker_1", label = "Speaker 1")
            speakers[defaultSpeaker.id] = defaultSpeaker
            _currentSpeaker.value = defaultSpeaker

            _state.value = TranscriptionState.READY
            Log.d(TAG, "Sherpa-ONNX transcription service initialized")
            logger.d { "Transcription service initialized" }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}", e)
            logger.e(e) { "Failed to initialize Sherpa-ONNX" }
            _state.value = TranscriptionState.ERROR
            _events.emit(TranscriptionEvent.Error("Failed to initialize: ${e.message}"))
        }
    }

    private fun areModelsReady(modelDir: File): Boolean {
        if (!modelDir.exists()) return false
        return MODEL_FILES.all { modelFile ->
            val file = File(modelDir, modelFile.name)
            file.exists() && file.length() == modelFile.expectedSize
        }
    }

    private suspend fun downloadModels(modelDir: File) = withContext(Dispatchers.IO) {
        modelDir.mkdirs()

        // Clean up any partial downloads
        MODEL_FILES.forEach { modelFile ->
            val file = File(modelDir, modelFile.name)
            if (file.exists() && file.length() != modelFile.expectedSize) {
                Log.d(TAG, "Removing incomplete file: ${modelFile.name}")
                file.delete()
            }
        }

        val totalSize = MODEL_FILES.sumOf { it.expectedSize }
        var downloadedSize = 0L

        MODEL_FILES.forEach { modelFile ->
            val file = File(modelDir, modelFile.name)
            if (!file.exists()) {
                Log.d(TAG, "Downloading ${modelFile.name}...")
                val progressPercent = (downloadedSize * 100 / totalSize).toInt()
                scope.launch {
                    _events.emit(TranscriptionEvent.Error("Downloading speech model: $progressPercent% (${modelFile.name})"))
                }

                downloadFile(
                    url = "$MODEL_BASE_URL/${modelFile.name}",
                    destination = file,
                    expectedSize = modelFile.expectedSize
                )

                Log.d(TAG, "Downloaded ${modelFile.name}")
            }
            downloadedSize += modelFile.expectedSize
        }

        scope.launch {
            _events.emit(TranscriptionEvent.Error("Speech model ready"))
        }
    }

    private fun downloadFile(url: String, destination: File, expectedSize: Long) {
        val tempFile = File(destination.parent, "${destination.name}.tmp")

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "Fomovoi-Android")

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                    }

                    if (totalBytesRead != expectedSize) {
                        throw Exception("Download incomplete: got $totalBytesRead bytes, expected $expectedSize")
                    }
                }
            }

            // Rename temp file to final destination
            if (!tempFile.renameTo(destination)) {
                throw Exception("Failed to rename temp file")
            }

        } catch (e: Exception) {
            tempFile.delete()
            throw Exception("Failed to download from $url: ${e.message}", e)
        }
    }

    private fun createRecognizerConfig(modelDir: File): OnlineRecognizerConfig {
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(modelDir, "encoder-epoch-99-avg-1.onnx").absolutePath,
                decoder = File(modelDir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                joiner = File(modelDir, "joiner-epoch-99-avg-1.onnx").absolutePath
            ),
            tokens = File(modelDir, "tokens.txt").absolutePath,
            numThreads = 2,
            debug = false,
            provider = "cpu",
            modelType = "zipformer"
        )

        val endpointConfig = EndpointConfig(
            rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0f),
            rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.2f, minUtteranceLength = 0f),
            rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 20f)
        )

        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            endpointConfig = endpointConfig,
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4
        )
    }

    override suspend fun startTranscription() {
        Log.d(TAG, "startTranscription() called, current state: ${_state.value}")
        if (_state.value != TranscriptionState.READY && _state.value != TranscriptionState.IDLE) {
            Log.w(TAG, "Cannot start transcription in state: ${_state.value}")
            return
        }

        if (recognizer == null) {
            Log.e(TAG, "Recognizer not initialized")
            _events.emit(TranscriptionEvent.Error("Recognizer not initialized"))
            return
        }

        Log.d(TAG, "Starting transcription")
        utterances.clear()
        lastText = ""
        sessionStartTime = System.currentTimeMillis()

        // Create new stream for this session
        stream = recognizer?.createStream()
        Log.d(TAG, "Created new stream: ${stream != null}")

        _state.value = TranscriptionState.TRANSCRIBING
    }

    override suspend fun processAudioChunk(chunk: AudioChunk) {
        val currentStream = stream ?: return
        val currentRecognizer = recognizer ?: return

        if (_state.value != TranscriptionState.TRANSCRIBING) return

        // Convert ByteArray to FloatArray (16-bit PCM to float)
        val samples = FloatArray(chunk.data.size / 2)
        for (i in samples.indices) {
            val low = chunk.data[i * 2].toInt() and 0xFF
            val high = chunk.data[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            samples[i] = sample / 32768.0f
        }

        // Feed audio to recognizer
        currentStream.acceptWaveform(samples, chunk.sampleRate)

        // Process while ready
        while (currentRecognizer.isReady(currentStream)) {
            currentRecognizer.decode(currentStream)
        }

        // Get current result
        val result = currentRecognizer.getResult(currentStream)
        val text = result.text.trim()

        if (text.isNotEmpty() && text != lastText) {
            Log.d(TAG, "Partial result: $text")
            scope.launch {
                _events.emit(TranscriptionEvent.PartialResult(text))
            }
        }

        // Check for endpoint (sentence completion)
        if (currentRecognizer.isEndpoint(currentStream)) {
            if (text.isNotEmpty()) {
                Log.d(TAG, "Final result: $text")
                val currentTime = System.currentTimeMillis() - sessionStartTime
                val speaker = _currentSpeaker.value ?: return

                val utterance = Utterance(
                    id = UUID.randomUUID().toString(),
                    text = text,
                    speaker = speaker,
                    startTimeMs = currentTime - 1000, // Approximate
                    endTimeMs = currentTime
                )
                utterances.add(utterance)

                scope.launch {
                    _events.emit(TranscriptionEvent.FinalResult(utterance))
                }
            }

            // Reset stream for next utterance
            currentRecognizer.reset(currentStream)
            lastText = ""
        } else {
            lastText = text
        }
    }

    override suspend fun stopTranscription(): TranscriptionResult? {
        Log.d(TAG, "stopTranscription() called, utterances.size=${utterances.size}")
        logger.d { "Stopping transcription" }

        // Get any remaining text
        val currentStream = stream
        val currentRecognizer = recognizer
        if (currentStream != null && currentRecognizer != null && lastText.isNotEmpty()) {
            val currentTime = System.currentTimeMillis() - sessionStartTime
            val speaker = _currentSpeaker.value
            if (speaker != null) {
                val utterance = Utterance(
                    id = UUID.randomUUID().toString(),
                    text = lastText,
                    speaker = speaker,
                    startTimeMs = currentTime - 1000,
                    endTimeMs = currentTime
                )
                utterances.add(utterance)
                scope.launch {
                    _events.emit(TranscriptionEvent.FinalResult(utterance))
                }
            }
        }

        _state.value = TranscriptionState.READY
        stream = null
        lastText = ""

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
        Log.d(TAG, "Releasing Sherpa-ONNX transcription service")
        logger.d { "Releasing transcription service" }
        scope.cancel()
        stream = null
        recognizer?.release()
        recognizer = null
    }
}

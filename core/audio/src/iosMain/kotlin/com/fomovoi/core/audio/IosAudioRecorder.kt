package com.fomovoi.core.audio

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
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
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.posix.memcpy

actual fun createAudioRecorder(): AudioRecorder = IosAudioRecorder()

@OptIn(ExperimentalForeignApi::class)
class IosAudioRecorder : AudioRecorder {
    private val logger = Logger.withTag("IosAudioRecorder")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var audioEngine: AVAudioEngine? = null
    private var startTimeMs: Long = 0

    private val _state = MutableStateFlow(RecordingState.IDLE)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _audioStream = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)
    override val audioStream: Flow<AudioChunk> = _audioStream.asSharedFlow()

    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioDevice?>(null)
    override val selectedDevice: StateFlow<AudioDevice?> = _selectedDevice.asStateFlow()

    companion object {
        private const val SAMPLE_RATE = 16000.0
        private const val BUFFER_SIZE = 4096
    }

    override suspend fun initialize() {
        logger.d { "Initializing iOS audio recorder" }

        val session = AVAudioSession.sharedInstance()
        try {
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeMeasurement,
                options = 0u,
                error = null
            )
            session.setActive(true, null)
        } catch (e: Exception) {
            logger.e(e) { "Failed to configure audio session" }
            _state.value = RecordingState.ERROR
            return
        }

        val defaultDevice = AudioDevice(
            id = "default",
            name = "Default Microphone",
            isDefault = true
        )
        _availableDevices.value = listOf(defaultDevice)
        _selectedDevice.value = defaultDevice

        audioEngine = AVAudioEngine()
    }

    override suspend fun startRecording() {
        if (_state.value == RecordingState.RECORDING) {
            logger.w { "Already recording" }
            return
        }

        logger.d { "Starting recording" }

        val engine = audioEngine ?: run {
            logger.e { "Audio engine not initialized" }
            _state.value = RecordingState.ERROR
            return
        }

        val inputNode = engine.inputNode
        val format = inputNode.outputFormatForBus(0u)

        startTimeMs = platform.Foundation.NSDate().timeIntervalSince1970.toLong() * 1000

        inputNode.installTapOnBus(
            bus = 0u,
            bufferSize = BUFFER_SIZE.toUInt(),
            format = format
        ) { buffer, _ ->
            buffer?.let { processAudioBuffer(it) }
        }

        try {
            engine.prepare()
            engine.startAndReturnError(null)
            _state.value = RecordingState.RECORDING
        } catch (e: Exception) {
            logger.e(e) { "Failed to start audio engine" }
            _state.value = RecordingState.ERROR
        }
    }

    private fun processAudioBuffer(buffer: AVAudioPCMBuffer) {
        val frameLength = buffer.frameLength.toInt()
        if (frameLength == 0) return

        val floatData = buffer.floatChannelData
        if (floatData == null) return

        // Convert float audio data to 16-bit PCM
        val pcmData = ByteArray(frameLength * 2)
        // Note: In production, you'd properly convert the float data
        // This is a simplified version

        val currentTimeMs = platform.Foundation.NSDate().timeIntervalSince1970.toLong() * 1000
        val chunk = AudioChunk(
            data = pcmData,
            timestampMs = currentTimeMs - startTimeMs,
            sampleRate = SAMPLE_RATE.toInt(),
            channels = 1
        )

        scope.launch {
            _audioStream.emit(chunk)
        }
    }

    override suspend fun pauseRecording() {
        if (_state.value != RecordingState.RECORDING) return
        logger.d { "Pausing recording" }
        audioEngine?.pause()
        _state.value = RecordingState.PAUSED
    }

    override suspend fun resumeRecording() {
        if (_state.value != RecordingState.PAUSED) return
        logger.d { "Resuming recording" }
        try {
            audioEngine?.startAndReturnError(null)
            _state.value = RecordingState.RECORDING
        } catch (e: Exception) {
            logger.e(e) { "Failed to resume recording" }
            _state.value = RecordingState.ERROR
        }
    }

    override suspend fun stopRecording() {
        logger.d { "Stopping recording" }
        audioEngine?.inputNode?.removeTapOnBus(0u)
        audioEngine?.stop()
        _state.value = RecordingState.IDLE
    }

    override suspend fun selectDevice(device: AudioDevice) {
        logger.d { "Selecting device: ${device.name}" }
        _selectedDevice.value = device
    }

    override fun release() {
        logger.d { "Releasing audio recorder" }
        scope.cancel()
        audioEngine?.stop()
        audioEngine = null
    }
}

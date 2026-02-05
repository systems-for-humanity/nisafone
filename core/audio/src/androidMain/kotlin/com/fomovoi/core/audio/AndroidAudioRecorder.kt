package com.fomovoi.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

actual fun createAudioRecorder(): AudioRecorder = AndroidAudioRecorder()

class AndroidAudioRecorder : AudioRecorder {
    private val logger = Logger.withTag("AndroidAudioRecorder")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    private val _state = MutableStateFlow(RecordingState.IDLE)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _audioStream = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)
    override val audioStream: Flow<AudioChunk> = _audioStream.asSharedFlow()

    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioDevice?>(null)
    override val selectedDevice: StateFlow<AudioDevice?> = _selectedDevice.asStateFlow()

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    override suspend fun initialize() {
        logger.d { "Initializing audio recorder" }
        val defaultDevice = AudioDevice(
            id = "default",
            name = "Default Microphone",
            isDefault = true
        )
        _availableDevices.value = listOf(defaultDevice)
        _selectedDevice.value = defaultDevice
    }

    @SuppressLint("MissingPermission")
    override suspend fun startRecording() {
        if (_state.value == RecordingState.RECORDING) {
            logger.w { "Already recording" }
            return
        }

        logger.d { "Starting recording" }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ).coerceAtLeast(4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            logger.e { "Failed to initialize AudioRecord" }
            _state.value = RecordingState.ERROR
            return
        }

        audioRecord?.startRecording()
        _state.value = RecordingState.RECORDING

        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            val startTime = System.currentTimeMillis()

            while (isActive && _state.value == RecordingState.RECORDING) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    val chunk = AudioChunk(
                        data = buffer.copyOf(bytesRead),
                        timestampMs = System.currentTimeMillis() - startTime,
                        sampleRate = SAMPLE_RATE,
                        channels = 1
                    )
                    _audioStream.emit(chunk)
                }
            }
        }
    }

    override suspend fun pauseRecording() {
        if (_state.value != RecordingState.RECORDING) return
        logger.d { "Pausing recording" }
        _state.value = RecordingState.PAUSED
        audioRecord?.stop()
    }

    override suspend fun resumeRecording() {
        if (_state.value != RecordingState.PAUSED) return
        logger.d { "Resuming recording" }
        audioRecord?.startRecording()
        _state.value = RecordingState.RECORDING
    }

    override suspend fun stopRecording() {
        logger.d { "Stopping recording" }
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        _state.value = RecordingState.IDLE
    }

    override suspend fun selectDevice(device: AudioDevice) {
        logger.d { "Selecting device: ${device.name}" }
        _selectedDevice.value = device
    }

    override fun release() {
        logger.d { "Releasing audio recorder" }
        scope.cancel()
        audioRecord?.release()
        audioRecord = null
    }
}

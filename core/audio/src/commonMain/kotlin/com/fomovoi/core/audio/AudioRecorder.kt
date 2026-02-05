package com.fomovoi.core.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class AudioDevice(
    val id: String,
    val name: String,
    val isDefault: Boolean = false
)

data class AudioChunk(
    val data: ByteArray,
    val timestampMs: Long,
    val sampleRate: Int = 16000,
    val channels: Int = 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as AudioChunk
        return timestampMs == other.timestampMs &&
               sampleRate == other.sampleRate &&
               channels == other.channels &&
               data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        return result
    }
}

enum class RecordingState {
    IDLE,
    RECORDING,
    PAUSED,
    ERROR
}

interface AudioRecorder {
    val state: StateFlow<RecordingState>
    val audioStream: Flow<AudioChunk>
    val availableDevices: StateFlow<List<AudioDevice>>
    val selectedDevice: StateFlow<AudioDevice?>

    suspend fun initialize()
    suspend fun startRecording()
    suspend fun pauseRecording()
    suspend fun resumeRecording()
    suspend fun stopRecording()
    suspend fun selectDevice(device: AudioDevice)
    fun release()
}

expect fun createAudioRecorder(): AudioRecorder

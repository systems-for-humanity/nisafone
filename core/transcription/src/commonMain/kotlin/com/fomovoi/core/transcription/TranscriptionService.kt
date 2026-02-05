package com.fomovoi.core.transcription

import com.fomovoi.core.audio.AudioChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TranscriptionService {
    val state: StateFlow<TranscriptionState>
    val events: Flow<TranscriptionEvent>
    val currentSpeaker: StateFlow<Speaker?>

    suspend fun initialize()
    suspend fun startTranscription()
    suspend fun processAudioChunk(chunk: AudioChunk)
    suspend fun stopTranscription(): TranscriptionResult?
    suspend fun setSpeakerLabel(speakerId: String, label: String)
    fun release()
}

expect fun createTranscriptionService(): TranscriptionService

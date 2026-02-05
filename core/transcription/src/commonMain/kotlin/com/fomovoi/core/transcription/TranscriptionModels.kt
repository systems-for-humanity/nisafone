package com.fomovoi.core.transcription

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Speaker(
    val id: String,
    val label: String = "Speaker ${id.takeLast(4)}"
)

@Serializable
data class Utterance(
    val id: String,
    val text: String,
    val speaker: Speaker,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val confidence: Float = 1.0f
)

@Serializable
data class TranscriptionResult(
    val id: String,
    val utterances: List<Utterance>,
    val fullText: String,
    val durationMs: Long,
    val createdAt: Instant,
    val isComplete: Boolean = false
) {
    val formattedText: String
        get() = utterances.joinToString("\n\n") { utterance ->
            "[${utterance.speaker.label}]: ${utterance.text}"
        }
}

sealed class TranscriptionEvent {
    data class PartialResult(val text: String) : TranscriptionEvent()
    data class FinalResult(val utterance: Utterance) : TranscriptionEvent()
    data class SpeakerChange(val newSpeaker: Speaker) : TranscriptionEvent()
    data class Error(val message: String, val cause: Throwable? = null) : TranscriptionEvent()
}

enum class TranscriptionState {
    IDLE,
    INITIALIZING,
    READY,
    TRANSCRIBING,
    ERROR
}

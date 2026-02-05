package app.s4h.fomovoi.core.domain.model

import app.s4h.fomovoi.core.transcription.TranscriptionResult
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Recording(
    val id: String,
    val title: String,
    val transcription: TranscriptionResult?,
    val audioFilePath: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val durationMs: Long,
    val isFavorite: Boolean = false
) {
    val displayTitle: String
        get() = title.ifBlank { "Recording ${createdAt}" }
}

@Serializable
data class RecordingSettings(
    val autoSave: Boolean = true,
    val saveAudioFile: Boolean = false,
    val defaultSpeakerCount: Int = 2,
    val preferOfflineRecognition: Boolean = true
)

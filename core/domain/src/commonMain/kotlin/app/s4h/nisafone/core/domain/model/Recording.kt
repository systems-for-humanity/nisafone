package app.s4h.nisafone.core.domain.model

import app.s4h.nisafone.core.transcription.TranscriptionResult
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class Recording(
    val id: String,
    val title: String,
    val transcription: TranscriptionResult?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val durationMs: Long,
    val isFavorite: Boolean = false
) {
    val displayTitle: String
        get() {
            val prefix = title.ifBlank { "Recording" }
            val dateTime = createdAt.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            val month = dateTime.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            val day = dateTime.dayOfMonth
            val hour = dateTime.hour
            val minute = dateTime.minute.toString().padStart(2, '0')
            return "$prefix - $month $day, $hour:$minute"
        }
}

package app.s4h.nisafone.core.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import co.touchlab.kermit.Logger
import app.s4h.nisafone.core.data.local.NisafoneDatabase
import app.s4h.nisafone.core.data.local.RecordingEntity
import app.s4h.nisafone.core.domain.model.Recording
import app.s4h.nisafone.core.domain.usecase.RecordingRepository
import app.s4h.nisafone.core.transcription.TranscriptionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RecordingRepositoryImpl(
    private val database: NisafoneDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : RecordingRepository {

    private val logger = Logger.withTag("RecordingRepository")
    private val queries = database.recordingQueries

    override fun getAllRecordings(): Flow<List<Recording>> {
        return queries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { entities -> entities.map { it.toRecording() } }
    }

    override fun getRecordingById(id: String): Flow<Recording?> {
        return queries.selectById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it?.toRecording() }
    }

    override suspend fun saveRecording(recording: Recording) = withContext(Dispatchers.IO) {
        logger.d { "Saving recording: ${recording.id}" }
        queries.insert(
            id = recording.id,
            title = recording.title,
            transcriptionJson = recording.transcription?.let { json.encodeToString(it) },
            createdAt = recording.createdAt.toEpochMilliseconds(),
            updatedAt = recording.updatedAt.toEpochMilliseconds(),
            durationMs = recording.durationMs,
            isFavorite = if (recording.isFavorite) 1L else 0L
        )
    }

    override suspend fun deleteRecording(id: String) = withContext(Dispatchers.IO) {
        logger.d { "Deleting recording: $id" }
        queries.deleteById(id)
    }

    override suspend fun updateRecording(recording: Recording) = withContext(Dispatchers.IO) {
        logger.d { "Updating recording: ${recording.id}" }
        queries.update(
            title = recording.title,
            transcriptionJson = recording.transcription?.let { json.encodeToString(it) },
            updatedAt = recording.updatedAt.toEpochMilliseconds(),
            durationMs = recording.durationMs,
            isFavorite = if (recording.isFavorite) 1L else 0L,
            id = recording.id
        )
    }

    private fun RecordingEntity.toRecording(): Recording {
        return Recording(
            id = id,
            title = title,
            transcription = transcriptionJson?.let {
                try {
                    json.decodeFromString<TranscriptionResult>(it)
                } catch (e: Exception) {
                    logger.e(e) { "Failed to decode transcription" }
                    null
                }
            },
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt),
            durationMs = durationMs,
            isFavorite = isFavorite == 1L
        )
    }
}

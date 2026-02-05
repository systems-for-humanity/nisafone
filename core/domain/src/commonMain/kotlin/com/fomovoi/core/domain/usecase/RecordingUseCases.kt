package com.fomovoi.core.domain.usecase

import com.fomovoi.core.domain.model.Recording
import com.fomovoi.core.transcription.TranscriptionResult
import kotlinx.coroutines.flow.Flow

interface RecordingRepository {
    fun getAllRecordings(): Flow<List<Recording>>
    fun getRecordingById(id: String): Flow<Recording?>
    suspend fun saveRecording(recording: Recording)
    suspend fun deleteRecording(id: String)
    suspend fun updateRecording(recording: Recording)
}

class GetAllRecordingsUseCase(
    private val repository: RecordingRepository
) {
    operator fun invoke(): Flow<List<Recording>> {
        return repository.getAllRecordings()
    }
}

class GetRecordingByIdUseCase(
    private val repository: RecordingRepository
) {
    operator fun invoke(id: String): Flow<Recording?> {
        return repository.getRecordingById(id)
    }
}

class SaveRecordingUseCase(
    private val repository: RecordingRepository
) {
    suspend operator fun invoke(recording: Recording) {
        repository.saveRecording(recording)
    }
}

class DeleteRecordingUseCase(
    private val repository: RecordingRepository
) {
    suspend operator fun invoke(id: String) {
        repository.deleteRecording(id)
    }
}

class ToggleFavoriteUseCase(
    private val repository: RecordingRepository
) {
    suspend operator fun invoke(recording: Recording) {
        repository.updateRecording(recording.copy(isFavorite = !recording.isFavorite))
    }
}

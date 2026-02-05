package app.s4h.fomovoi.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import app.s4h.fomovoi.core.domain.model.Recording
import app.s4h.fomovoi.core.domain.usecase.DeleteRecordingUseCase
import app.s4h.fomovoi.core.domain.usecase.GetAllRecordingsUseCase
import app.s4h.fomovoi.core.domain.usecase.ToggleFavoriteUseCase
import app.s4h.fomovoi.core.sharing.ShareService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val recordings: List<Recording> = emptyList(),
    val isLoading: Boolean = true,
    val selectedRecording: Recording? = null,
    val error: String? = null
)

class HistoryViewModel(
    private val getAllRecordingsUseCase: GetAllRecordingsUseCase,
    private val deleteRecordingUseCase: DeleteRecordingUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val shareService: ShareService
) : ViewModel() {

    private val logger = Logger.withTag("HistoryViewModel")

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadRecordings()
    }

    private fun loadRecordings() {
        viewModelScope.launch {
            getAllRecordingsUseCase().collect { recordings ->
                _uiState.update {
                    it.copy(
                        recordings = recordings,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun selectRecording(recording: Recording) {
        _uiState.update { it.copy(selectedRecording = recording) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedRecording = null) }
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            try {
                deleteRecordingUseCase(recording.id)
                if (_uiState.value.selectedRecording?.id == recording.id) {
                    clearSelection()
                }
            } catch (e: Exception) {
                logger.e(e) { "Failed to delete recording" }
                _uiState.update { it.copy(error = "Failed to delete recording") }
            }
        }
    }

    fun toggleFavorite(recording: Recording) {
        viewModelScope.launch {
            try {
                toggleFavoriteUseCase(recording)
            } catch (e: Exception) {
                logger.e(e) { "Failed to toggle favorite" }
            }
        }
    }

    fun shareRecording(recording: Recording) {
        viewModelScope.launch {
            recording.transcription?.let { transcription ->
                shareService.shareTranscription(transcription)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

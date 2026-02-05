package app.s4h.fomovoi.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import app.s4h.fomovoi.core.transcription.LanguageHint
import app.s4h.fomovoi.core.transcription.ModelManager
import app.s4h.fomovoi.core.transcription.SpeechModel
import app.s4h.fomovoi.core.transcription.SpeechModelType
import app.s4h.fomovoi.core.transcription.TranscriptionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SettingsViewModel : ViewModel(), KoinComponent, SettingsViewModelInterface {

    private val logger = Logger.withTag("SettingsViewModel")
    private val modelManager: ModelManager by inject()
    private val transcriptionService: TranscriptionService by inject()

    private val _uiState = MutableStateFlow(SettingsUiState())
    override val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadModels()
        observeDownloads()
        observeLanguageHint()
    }

    private fun observeDownloads() {
        viewModelScope.launch {
            modelManager.downloadingModels.collect { downloading ->
                _uiState.update { it.copy(downloadingModelIds = downloading) }
            }
        }
        viewModelScope.launch {
            modelManager.downloadProgress.collect { progress ->
                _uiState.update { it.copy(downloadProgress = progress) }
            }
        }
    }

    private fun observeLanguageHint() {
        viewModelScope.launch {
            transcriptionService.currentLanguageHint.collect { hint ->
                _uiState.update { it.copy(languageHint = hint) }
            }
        }
    }

    override fun loadModels() {
        viewModelScope.launch {
            try {
                // First discover models from Hugging Face (cached after first call)
                _uiState.update { it.copy(error = "Discovering available models...") }
                modelManager.discoverModels()

                val models = modelManager.getAvailableModels()
                val selectedId = modelManager.getSelectedModelId()
                val storageUsed = modelManager.getTotalStorageUsed() / 1_000_000

                _uiState.update {
                    it.copy(
                        models = models,
                        selectedModelId = selectedId,
                        totalStorageUsedMB = storageUsed.toInt(),
                        error = null
                    )
                }
            } catch (e: Exception) {
                logger.e(e) { "Failed to load models" }
                _uiState.update { it.copy(error = "Failed to load models: ${e.message}") }
            }
        }
    }

    override fun downloadModel(model: SpeechModel) {
        viewModelScope.launch {
            val result = modelManager.downloadModel(model)
            result.onFailure { e ->
                _uiState.update { it.copy(error = "Download failed: ${e.message}") }
            }
            loadModels() // Refresh model list
        }
    }

    override fun deleteModel(model: SpeechModel) {
        viewModelScope.launch {
            try {
                modelManager.deleteModel(model)
                // If this was the selected model, clear selection
                if (_uiState.value.selectedModelId == model.id) {
                    _uiState.update { it.copy(selectedModelId = null) }
                }
                loadModels() // Refresh model list
            } catch (e: Exception) {
                logger.e(e) { "Failed to delete model" }
                _uiState.update { it.copy(error = "Failed to delete: ${e.message}") }
            }
        }
    }

    override fun selectModel(model: SpeechModel) {
        if (!model.isDownloaded) {
            _uiState.update { it.copy(error = "Please download the model first") }
            return
        }
        modelManager.setSelectedModel(model)
        _uiState.update { it.copy(selectedModelId = model.id) }
    }

    override fun setFilter(type: SpeechModelType?) {
        _uiState.update { it.copy(filterByType = type) }
    }

    override fun setLanguageHint(hint: LanguageHint) {
        viewModelScope.launch {
            try {
                transcriptionService.setLanguageHint(hint)
            } catch (e: Exception) {
                logger.e(e) { "Failed to set language hint" }
                _uiState.update { it.copy(error = "Failed to set language hint: ${e.message}") }
            }
        }
    }

    override fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

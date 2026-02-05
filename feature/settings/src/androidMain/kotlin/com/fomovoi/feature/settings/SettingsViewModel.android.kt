package com.fomovoi.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.fomovoi.core.transcription.ModelManager
import com.fomovoi.core.transcription.SpeechModel
import com.fomovoi.core.transcription.SpeechModelType
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

    private val _uiState = MutableStateFlow(SettingsUiState())
    override val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadModels()
        observeDownloads()
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

    override fun loadModels() {
        viewModelScope.launch {
            try {
                val models = modelManager.getAvailableModels()
                val selectedId = modelManager.getSelectedModelId()
                val storageUsed = modelManager.getTotalStorageUsed() / 1_000_000

                _uiState.update {
                    it.copy(
                        models = models,
                        selectedModelId = selectedId,
                        totalStorageUsedMB = storageUsed.toInt()
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

    override fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

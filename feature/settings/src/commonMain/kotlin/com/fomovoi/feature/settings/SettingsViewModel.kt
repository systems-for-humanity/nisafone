package com.fomovoi.feature.settings

import com.fomovoi.core.transcription.SpeechModel
import com.fomovoi.core.transcription.SpeechModelType
import kotlinx.coroutines.flow.StateFlow

data class SettingsUiState(
    val models: List<SpeechModel> = emptyList(),
    val selectedModelId: String? = null,
    val downloadingModelIds: Set<String> = emptySet(),
    val downloadProgress: Map<String, Float> = emptyMap(),
    val totalStorageUsedMB: Int = 0,
    val error: String? = null,
    val filterByType: SpeechModelType? = null
) {
    val filteredModels: List<SpeechModel>
        get() = if (filterByType != null) {
            models.filter { it.type == filterByType }
        } else {
            models
        }

    val downloadedModels: List<SpeechModel>
        get() = models.filter { it.isDownloaded }

    val selectedModel: SpeechModel?
        get() = models.find { it.id == selectedModelId && it.isDownloaded }
}

interface SettingsViewModelInterface {
    val uiState: StateFlow<SettingsUiState>

    fun loadModels()
    fun downloadModel(model: SpeechModel)
    fun deleteModel(model: SpeechModel)
    fun selectModel(model: SpeechModel)
    fun setFilter(type: SpeechModelType?)
    fun clearError()
}

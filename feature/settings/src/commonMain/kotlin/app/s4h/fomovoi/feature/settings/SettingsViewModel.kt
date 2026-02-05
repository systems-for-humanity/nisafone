package app.s4h.fomovoi.feature.settings

import app.s4h.fomovoi.core.transcription.LanguageHint
import app.s4h.fomovoi.core.transcription.SpeechLanguage
import app.s4h.fomovoi.core.transcription.SpeechModel
import app.s4h.fomovoi.core.transcription.SpeechModelType
import kotlinx.coroutines.flow.StateFlow

data class SettingsUiState(
    val models: List<SpeechModel> = emptyList(),
    val selectedModelId: String? = null,
    val downloadingModelIds: Set<String> = emptySet(),
    val downloadProgress: Map<String, Float> = emptyMap(),
    val totalStorageUsedMB: Int = 0,
    val error: String? = null,
    val filterByType: SpeechModelType? = null,
    val languageHint: LanguageHint = LanguageHint.AUTO_DETECT
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

    /**
     * Whether the language hint setting should be shown.
     * Only relevant when a multilingual model is selected.
     */
    val showLanguageHint: Boolean
        get() = selectedModel?.language == SpeechLanguage.MULTILINGUAL
}

interface SettingsViewModelInterface {
    val uiState: StateFlow<SettingsUiState>

    fun loadModels()
    fun downloadModel(model: SpeechModel)
    fun deleteModel(model: SpeechModel)
    fun selectModel(model: SpeechModel)
    fun setFilter(type: SpeechModelType?)
    fun setLanguageHint(hint: LanguageHint)
    fun clearError()
}

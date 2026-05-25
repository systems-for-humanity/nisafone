package app.s4h.nisafone.feature.settings

import app.s4h.nisafone.core.transcription.LanguageHint
import app.s4h.nisafone.core.transcription.SpeechLanguage
import app.s4h.nisafone.core.transcription.SpeechModel
import app.s4h.nisafone.core.transcription.SpeechModelType
import kotlinx.coroutines.flow.StateFlow

data class SettingsUiState(
    val models: List<SpeechModel> = emptyList(),
    val selectedModelId: String? = null,
    val downloadingModelIds: Set<String> = emptySet(),
    val downloadProgress: Map<String, Float> = emptyMap(),
    val totalStorageUsedMB: Int = 0,
    val error: String? = null,
    val filterByType: SpeechModelType? = null,
    val languageHint: LanguageHint = LanguageHint.AUTO_DETECT,
    val translateToEnglish: Boolean = true,
    val autoEmailEnabled: Boolean = false,
    val autoEmailAddress: String = "",
    val autoStartScheduleEnabled: Boolean = false,
    val autoStartSchedules: List<AutoStartSchedule> = emptyList(),
    val isDiscovering: Boolean = false,
    val hasDiscoveredRemoteModels: Boolean = false
) {
    val filteredModels: List<SpeechModel>
        get() {
            val filtered = if (filterByType != null) {
                models.filter { it.type == filterByType }
            } else {
                models
            }
            // Sort with downloaded models first, then by display name
            return filtered.sortedWith(
                compareByDescending<SpeechModel> { it.isDownloaded }
                    .thenBy { it.displayName }
            )
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

    /**
     * Whether the translate to English option should be shown.
     * Only relevant when a non-English (multilingual) model is selected.
     */
    val showTranslateOption: Boolean
        get() = selectedModel?.language != null && selectedModel?.language != SpeechLanguage.ENGLISH
}

interface SettingsViewModelInterface {
    val uiState: StateFlow<SettingsUiState>

    fun loadModels()
    fun downloadModel(model: SpeechModel)
    fun deleteModel(model: SpeechModel)
    fun selectModel(model: SpeechModel)
    fun setFilter(type: SpeechModelType?)
    fun setLanguageHint(hint: LanguageHint)
    fun setTranslateToEnglish(translate: Boolean)
    fun setAutoEmailEnabled(enabled: Boolean)
    fun setAutoEmailAddress(address: String)
    fun setAutoStartScheduleEnabled(enabled: Boolean)
    fun addAutoStartSchedule()
    fun updateAutoStartSchedule(schedule: AutoStartSchedule)
    fun removeAutoStartSchedule(scheduleId: String)
    fun discoverMoreModels()
    fun clearError()
}

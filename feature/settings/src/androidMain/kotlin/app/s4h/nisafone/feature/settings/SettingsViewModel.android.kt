package app.s4h.nisafone.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import app.s4h.nisafone.core.transcription.LanguageHint
import app.s4h.nisafone.core.transcription.ModelManager
import app.s4h.nisafone.core.transcription.SpeechModel
import app.s4h.nisafone.core.transcription.SpeechModelType
import app.s4h.nisafone.core.transcription.TranscriptionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.random.Random

class SettingsViewModel : ViewModel(), KoinComponent, SettingsViewModelInterface {

    private val logger = Logger.withTag("SettingsViewModel")
    private val modelManager: ModelManager by inject()
    private val transcriptionService: TranscriptionService by inject()
    private val emailSettingsRepository: EmailSettingsRepository by inject()

    private val _uiState = MutableStateFlow(SettingsUiState())
    override val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadModels()
        observeDownloads()
        observeLanguageHint()
        observeTranslateSetting()
        observeAutoShareSettings()
        observeAutoStartScheduleSettings()
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

    private fun observeTranslateSetting() {
        viewModelScope.launch {
            transcriptionService.translateToEnglish.collect { translate ->
                _uiState.update { it.copy(translateToEnglish = translate) }
            }
        }
    }

    private fun observeAutoShareSettings() {
        viewModelScope.launch {
            emailSettingsRepository.autoEmailEnabled.collect { enabled ->
                _uiState.update { it.copy(autoEmailEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            emailSettingsRepository.emailAddress.collect { address ->
                _uiState.update { it.copy(autoEmailAddress = address) }
            }
        }
    }

    private fun observeAutoStartScheduleSettings() {
        viewModelScope.launch {
            emailSettingsRepository.autoStartScheduleEnabled.collect { enabled ->
                _uiState.update { it.copy(autoStartScheduleEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            emailSettingsRepository.autoStartSchedules.collect { schedules ->
                _uiState.update { it.copy(autoStartSchedules = schedules) }
            }
        }
    }

    override fun loadModels() {
        viewModelScope.launch {
            try {
                // After discovery, use full catalog; otherwise local-only (fast, no network)
                val useFullCatalog = _uiState.value.hasDiscoveredRemoteModels
                val (models, selectedId, storageUsed) = withContext(Dispatchers.IO) {
                    Triple(
                        if (useFullCatalog) modelManager.getAvailableModels()
                        else modelManager.getLocalModels(),
                        modelManager.getSelectedModelId(),
                        modelManager.getTotalStorageUsed() / 1_000_000
                    )
                }

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

    override fun discoverMoreModels() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isDiscovering = true) }

                modelManager.discoverModels()

                val (models, selectedId, storageUsed) = withContext(Dispatchers.IO) {
                    Triple(
                        modelManager.getAvailableModels(),
                        modelManager.getSelectedModelId(),
                        modelManager.getTotalStorageUsed() / 1_000_000
                    )
                }

                _uiState.update {
                    it.copy(
                        models = models,
                        selectedModelId = selectedId,
                        totalStorageUsedMB = storageUsed.toInt(),
                        isDiscovering = false,
                        hasDiscoveredRemoteModels = true,
                        error = null
                    )
                }
            } catch (e: Exception) {
                logger.e(e) { "Failed to discover models" }
                _uiState.update {
                    it.copy(
                        isDiscovering = false,
                        error = "Failed to discover models: ${e.message}"
                    )
                }
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
                withContext(Dispatchers.IO) {
                    modelManager.deleteModel(model)
                }
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

    override fun setTranslateToEnglish(translate: Boolean) {
        viewModelScope.launch {
            try {
                transcriptionService.setTranslateToEnglish(translate)
            } catch (e: Exception) {
                logger.e(e) { "Failed to set translate setting" }
                _uiState.update { it.copy(error = "Failed to set translate setting: ${e.message}") }
            }
        }
    }

    override fun setAutoEmailEnabled(enabled: Boolean) {
        emailSettingsRepository.setAutoEmailEnabled(enabled)
    }

    override fun setAutoEmailAddress(address: String) {
        emailSettingsRepository.setEmailAddress(address)
    }

    override fun setAutoStartScheduleEnabled(enabled: Boolean) {
        emailSettingsRepository.setAutoStartScheduleEnabled(enabled)
    }

    override fun addAutoStartSchedule() {
        val newScheduleId = "schedule-${Random.nextLong(1_000_000_000L, 9_999_999_999L)}"
        val current = emailSettingsRepository.autoStartSchedules.value
        emailSettingsRepository.setAutoStartSchedules(current + AutoStartSchedule.default(newScheduleId))
    }

    override fun updateAutoStartSchedule(schedule: AutoStartSchedule) {
        val updated = emailSettingsRepository.autoStartSchedules.value.map {
            if (it.id == schedule.id) schedule.normalized() else it
        }
        emailSettingsRepository.setAutoStartSchedules(updated)
    }

    override fun removeAutoStartSchedule(scheduleId: String) {
        val updated = emailSettingsRepository.autoStartSchedules.value.filterNot { it.id == scheduleId }
        emailSettingsRepository.setAutoStartSchedules(updated)
    }

    override fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

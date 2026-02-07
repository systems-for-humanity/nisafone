package app.s4h.nisafone.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.s4h.nisafone.core.transcription.LanguageHint
import app.s4h.nisafone.core.transcription.SpeechModel
import app.s4h.nisafone.core.transcription.SpeechModelType
import app.s4h.nisafone.settings.generated.resources.Res
import app.s4h.nisafone.settings.generated.resources.about_app_name
import app.s4h.nisafone.settings.generated.resources.about_powered_by
import app.s4h.nisafone.settings.generated.resources.about_version
import app.s4h.nisafone.settings.generated.resources.active_model
import app.s4h.nisafone.settings.generated.resources.auto_email
import app.s4h.nisafone.settings.generated.resources.auto_email_description
import app.s4h.nisafone.settings.generated.resources.available_models
import app.s4h.nisafone.settings.generated.resources.batch_transcription
import app.s4h.nisafone.settings.generated.resources.delete
import app.s4h.nisafone.settings.generated.resources.download
import app.s4h.nisafone.settings.generated.resources.downloading_progress
import app.s4h.nisafone.settings.generated.resources.email_address
import app.s4h.nisafone.settings.generated.resources.email_placeholder
import app.s4h.nisafone.settings.generated.resources.filter_all
import app.s4h.nisafone.settings.generated.resources.language_hint
import app.s4h.nisafone.settings.generated.resources.language_hint_description
import app.s4h.nisafone.settings.generated.resources.multilingual
import app.s4h.nisafone.settings.generated.resources.none_selected
import app.s4h.nisafone.settings.generated.resources.realtime_transcription
import app.s4h.nisafone.settings.generated.resources.selected
import app.s4h.nisafone.settings.generated.resources.settings_title
import app.s4h.nisafone.settings.generated.resources.storage_info
import app.s4h.nisafone.settings.generated.resources.storage_used
import app.s4h.nisafone.settings.generated.resources.translate_disabled_description
import app.s4h.nisafone.settings.generated.resources.translate_enabled_description
import app.s4h.nisafone.settings.generated.resources.translate_to_english
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModelInterface = koinInject(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.loadModels()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(Res.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            // Storage info
            item {
                StorageInfoCard(
                    totalStorageUsedMB = uiState.totalStorageUsedMB,
                    downloadedCount = uiState.downloadedModels.size
                )
            }

            // Currently selected model
            item {
                SelectedModelCard(
                    selectedModel = uiState.selectedModel
                )
            }

            // Language hint (only for multilingual models)
            if (uiState.showLanguageHint) {
                item {
                    LanguageHintCard(
                        currentHint = uiState.languageHint,
                        onHintSelected = viewModel::setLanguageHint
                    )
                }
            }

            // Translate to English toggle (only for non-English models)
            if (uiState.showTranslateOption) {
                item {
                    TranslateToEnglishCard(
                        translateToEnglish = uiState.translateToEnglish,
                        onTranslateChanged = viewModel::setTranslateToEnglish
                    )
                }
            }

            // Auto-email settings
            item {
                AutoEmailCard(
                    enabled = uiState.autoEmailEnabled,
                    emailAddress = uiState.autoEmailAddress,
                    onEnabledChanged = viewModel::setAutoEmailEnabled,
                    onEmailAddressChanged = viewModel::setAutoEmailAddress
                )
            }

            // Filter chips
            item {
                ModelFilterChips(
                    selectedFilter = uiState.filterByType,
                    onFilterSelected = viewModel::setFilter
                )
            }

            // Model list header
            item {
                Text(
                    text = stringResource(Res.string.available_models),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Model list
            items(uiState.filteredModels, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    isSelected = model.id == uiState.selectedModelId,
                    isDownloading = model.id in uiState.downloadingModelIds,
                    downloadProgress = uiState.downloadProgress[model.id] ?: 0f,
                    onDownload = { viewModel.downloadModel(model) },
                    onDelete = { viewModel.deleteModel(model) },
                    onSelect = { viewModel.selectModel(model) }
                )
            }

            // About section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                AboutCard()
            }
        }
    }
}

@Composable
private fun StorageInfoCard(
    totalStorageUsedMB: Int,
    downloadedCount: Int
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(Res.string.storage_used),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(Res.string.storage_info, totalStorageUsedMB, downloadedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun SelectedModelCard(
    selectedModel: SpeechModel?
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(Res.string.active_model),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = selectedModel?.displayName ?: stringResource(Res.string.none_selected),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                if (selectedModel != null) {
                    Text(
                        text = if (selectedModel.type.isStreaming) {
                            stringResource(Res.string.realtime_transcription)
                        } else {
                            stringResource(Res.string.batch_transcription)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageHintCard(
    currentHint: LanguageHint,
    onHintSelected: (LanguageHint) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(Res.string.language_hint),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = stringResource(Res.string.language_hint_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(LanguageHint.entries.toList()) { hint ->
                    FilterChip(
                        selected = currentHint == hint,
                        onClick = { onHintSelected(hint) },
                        label = { Text(hint.displayName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslateToEnglishCard(
    translateToEnglish: Boolean,
    onTranslateChanged: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Translate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.translate_to_english),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (translateToEnglish) {
                        stringResource(Res.string.translate_enabled_description)
                    } else {
                        stringResource(Res.string.translate_disabled_description)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = translateToEnglish,
                onCheckedChange = onTranslateChanged
            )
        }
    }
}

@Composable
private fun ModelFilterChips(
    selectedFilter: SpeechModelType?,
    onFilterSelected: (SpeechModelType?) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { onFilterSelected(null) },
                label = { Text(stringResource(Res.string.filter_all)) }
            )
        }
        items(SpeechModelType.entries.toList()) { type ->
            FilterChip(
                selected = selectedFilter == type,
                onClick = { onFilterSelected(type) },
                label = { Text(type.displayName) }
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: SpeechModel,
    isSelected: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        onClick = if (model.isDownloaded && !isDownloading) onSelect else { {} },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Language indicator: Globe icon for multilingual, EN text for English
                if (model.language.code == "multi") {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = stringResource(Res.string.multilingual),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "EN",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.type.displayName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(Res.string.selected),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = model.type.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${model.totalSizeMB}MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Action buttons
                when {
                    isDownloading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    model.isDownloaded -> {
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(Res.string.delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    else -> {
                        IconButton(onClick = onDownload) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = stringResource(Res.string.download),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Download progress
            AnimatedVisibility(visible = isDownloading) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.downloading_progress, (downloadProgress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoEmailCard(
    enabled: Boolean,
    emailAddress: String,
    onEnabledChanged: (Boolean) -> Unit,
    onEmailAddressChanged: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.auto_email),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(Res.string.auto_email_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChanged
                )
            }

            AnimatedVisibility(visible = enabled) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = emailAddress,
                        onValueChange = onEmailAddressChanged,
                        label = { Text(stringResource(Res.string.email_address)) },
                        placeholder = { Text(stringResource(Res.string.email_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(Res.string.about_app_name),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(Res.string.about_version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(Res.string.about_powered_by),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

package app.s4h.fomovoi.feature.recording

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.s4h.fomovoi.core.transcription.Utterance
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun RecordingScreen(
    viewModel: RecordingViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.initialize()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Auto-scroll to bottom when new utterances arrive
    LaunchedEffect(uiState.utterances.size) {
        if (uiState.utterances.isNotEmpty()) {
            listState.animateScrollToItem(uiState.utterances.size - 1)
        }
    }

    // Keep screen on while recording
    if (uiState.isRecording) {
        KeepScreenOn()
    }

    // Add prefix dialog
    if (uiState.showAddPrefixDialog) {
        AddPrefixDialog(
            onDismiss = viewModel::hideAddPrefixDialog,
            onConfirm = viewModel::addTitlePrefix
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            RecordingFab(
                isRecording = uiState.isRecording,
                canStart = uiState.canStart,
                onStart = viewModel::startRecording,
                onStop = viewModel::stopRecording
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Title prefix selector
            TitlePrefixSelector(
                prefixes = uiState.titlePrefixes,
                selectedPrefix = uiState.selectedTitlePrefix,
                onPrefixSelected = viewModel::selectTitlePrefix,
                onAddNewPrefix = viewModel::showAddPrefixDialog,
                enabled = !uiState.isRecording,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Timer display
            TimerDisplay(
                formattedTime = uiState.formattedTime,
                isRecording = uiState.isRecording,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Current speaker indicator
            AnimatedVisibility(visible = uiState.currentSpeaker != null && uiState.isRecording) {
                CurrentSpeakerIndicator(
                    speakerLabel = uiState.currentSpeaker?.label ?: "",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Transcription display
            TranscriptionDisplay(
                utterances = uiState.utterances,
                partialText = uiState.partialText,
                currentSpeaker = uiState.currentSpeaker?.label,
                listState = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            ActionButtons(
                hasTranscription = uiState.utterances.isNotEmpty() || uiState.partialText.isNotBlank(),
                isSharing = uiState.isSharing,
                onShare = viewModel::shareTranscription,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TimerDisplay(
    formattedTime: String,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .scale(scale)
                        .background(
                            color = MaterialTheme.colorScheme.error,
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Text(
                text = formattedTime,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = if (isRecording) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }

        if (isRecording) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Recording",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun CurrentSpeakerIndicator(
    speakerLabel: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = speakerLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun TranscriptionDisplay(
    utterances: List<Utterance>,
    partialText: String,
    currentSpeaker: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        if (utterances.isEmpty() && partialText.isBlank()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Tap the microphone to start recording",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                items(utterances, key = { it.id }) { utterance ->
                    UtteranceItem(
                        utterance = utterance,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (partialText.isNotBlank()) {
                    item {
                        PartialTextItem(
                            text = partialText,
                            speakerLabel = currentSpeaker ?: "Speaker",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UtteranceItem(
    utterance: Utterance,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = utterance.speaker.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = utterance.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PartialTextItem(
    text: String,
    speakerLabel: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = speakerLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$text...",
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecordingFab(
    isRecording: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isRecording) {
        // Stop button
        FloatingActionButton(
            onClick = onStop,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            modifier = modifier.size(72.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop recording",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(32.dp)
            )
        }
    } else {
        // Start button
        FloatingActionButton(
            onClick = onStart,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            modifier = modifier.size(72.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Start recording",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun ActionButtons(
    hasTranscription: Boolean,
    isSharing: Boolean,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = modifier
    ) {
        AnimatedVisibility(visible = hasTranscription) {
            IconButton(
                onClick = onShare,
                enabled = !isSharing
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share transcription",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun TitlePrefixSelector(
    prefixes: List<String>,
    selectedPrefix: String,
    onPrefixSelected: (String) -> Unit,
    onAddNewPrefix: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = selectedPrefix,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Select title prefix"
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            prefixes.forEach { prefix ->
                DropdownMenuItem(
                    text = { Text(prefix) },
                    onClick = {
                        onPrefixSelected(prefix)
                        expanded = false
                    }
                )
            }
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add new...")
                    }
                },
                onClick = {
                    expanded = false
                    onAddNewPrefix()
                }
            )
        }
    }
}

@Composable
private fun AddPrefixDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Title Prefix") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Prefix") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

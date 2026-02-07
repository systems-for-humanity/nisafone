package app.s4h.nisafone.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.s4h.nisafone.composeapp.generated.resources.Res
import app.s4h.nisafone.composeapp.generated.resources.nav_history
import app.s4h.nisafone.composeapp.generated.resources.nav_record
import app.s4h.nisafone.composeapp.generated.resources.nav_settings
import app.s4h.nisafone.composeapp.generated.resources.share_title
import app.s4h.nisafone.core.domain.model.Recording
import app.s4h.nisafone.core.sharing.ShareService
import app.s4h.nisafone.feature.history.HistoryScreen
import app.s4h.nisafone.feature.history.RecordingDetailScreen
import app.s4h.nisafone.feature.recording.RecordingScreen
import app.s4h.nisafone.feature.settings.SettingsScreen
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

enum class Screen(
    val titleRes: StringResource,
    val icon: ImageVector
) {
    Recording(Res.string.nav_record, Icons.Default.Mic),
    History(Res.string.nav_history, Icons.Default.History),
    Settings(Res.string.nav_settings, Icons.Default.Settings)
}

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier
) {
    var currentScreen by remember { mutableStateOf(Screen.Recording) }
    var selectedRecording by remember { mutableStateOf<Recording?>(null) }
    val shareService: ShareService = koinInject()
    val scope = rememberCoroutineScope()
    val shareTitle = stringResource(Res.string.share_title)

    // Handle back button - go back to Recording screen from History/Settings
    BackHandler(enabled = currentScreen != Screen.Recording) {
        currentScreen = Screen.Recording
    }

    // Show detail screen if a recording is selected
    if (selectedRecording != null) {
        RecordingDetailScreen(
            recording = selectedRecording!!,
            onBackClick = { selectedRecording = null },
            onShareClick = {
                selectedRecording?.let { recording ->
                    val text = buildTranscriptionText(recording)
                    scope.launch {
                        shareService.shareText(text, shareTitle)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    val title = stringResource(screen.titleRes)
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = title
                            )
                        },
                        label = { Text(title) },
                        selected = currentScreen == screen,
                        onClick = { currentScreen = screen }
                    )
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
            },
            modifier = Modifier.padding(paddingValues)
        ) { screen ->
            when (screen) {
                Screen.Recording -> RecordingScreen(
                    modifier = Modifier.fillMaxSize()
                )
                Screen.History -> HistoryScreen(
                    onRecordingClick = { recording ->
                        selectedRecording = recording
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Screen.Settings -> SettingsScreen(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun buildTranscriptionText(recording: Recording): String {
    return buildString {
        recording.transcription?.utterances?.forEach { utterance ->
            appendLine("[${utterance.speaker.label}]: ${utterance.text}")
            appendLine()
        }
    }
}

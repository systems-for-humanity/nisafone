package com.fomovoi.app.navigation

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.fomovoi.feature.history.HistoryScreen
import com.fomovoi.feature.recording.RecordingScreen
import com.fomovoi.feature.settings.SettingsScreen

enum class Screen(
    val title: String,
    val icon: ImageVector
) {
    Recording("Record", Icons.Default.Mic),
    History("History", Icons.Default.History),
    Settings("Settings", Icons.Default.Settings)
}

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier
) {
    var currentScreen by remember { mutableStateOf(Screen.Recording) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title) },
                        selected = currentScreen == screen,
                        onClick = { currentScreen = screen }
                    )
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        when (currentScreen) {
            Screen.Recording -> RecordingScreen(
                modifier = Modifier.padding(paddingValues)
            )
            Screen.History -> HistoryScreen(
                modifier = Modifier.padding(paddingValues)
            )
            Screen.Settings -> SettingsScreen(
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

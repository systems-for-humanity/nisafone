package app.s4h.nisafone.app.navigation

import androidx.compose.runtime.Composable

@Composable
actual fun AppBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No system back button on iOS
}

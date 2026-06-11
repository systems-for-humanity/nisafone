package app.s4h.nisafone.app.navigation

import androidx.compose.runtime.Composable

// androidx.activity's BackHandler is Android-only; iOS has no system back button
@Composable
expect fun AppBackHandler(enabled: Boolean, onBack: () -> Unit)

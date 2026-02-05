package app.s4h.fomovoi.app

import androidx.compose.runtime.Composable
import app.s4h.fomovoi.app.navigation.AppNavigation
import app.s4h.fomovoi.app.theme.FomovoiTheme

@Composable
fun App() {
    FomovoiTheme {
        AppNavigation()
    }
}

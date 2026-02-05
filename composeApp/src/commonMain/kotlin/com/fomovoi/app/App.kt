package com.fomovoi.app

import androidx.compose.runtime.Composable
import com.fomovoi.app.navigation.AppNavigation
import com.fomovoi.app.theme.FomovoiTheme

@Composable
fun App() {
    FomovoiTheme {
        AppNavigation()
    }
}

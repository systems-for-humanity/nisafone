package com.fomovoi.app

import androidx.compose.ui.window.ComposeUIViewController
import com.fomovoi.app.di.commonModule
import com.fomovoi.app.di.iosModule
import org.koin.core.context.startKoin
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin()
    return ComposeUIViewController { App() }
}

private fun initKoin() {
    startKoin {
        modules(commonModule, iosModule)
    }
}

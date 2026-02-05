package com.fomovoi.app.di

import com.fomovoi.core.audio.AudioRecorder
import com.fomovoi.core.audio.createAudioRecorder
import com.fomovoi.core.data.local.DatabaseDriverFactory
import com.fomovoi.core.sharing.ShareService
import com.fomovoi.core.sharing.createAndroidShareService
import com.fomovoi.core.transcription.ModelManager
import com.fomovoi.core.transcription.TranscriptionService
import com.fomovoi.core.transcription.createSherpaOnnxTranscriptionService
import com.fomovoi.feature.settings.SettingsViewModel
import com.fomovoi.feature.settings.SettingsViewModelInterface
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidModule = module {
    // Platform-specific implementations
    single<AudioRecorder> { createAudioRecorder() }
    // Use Sherpa-ONNX for continuous on-device transcription
    single<TranscriptionService> { createSherpaOnnxTranscriptionService(androidContext()) }
    single<ShareService> { createAndroidShareService(androidContext()) }

    // Model management
    single { ModelManager(androidContext()) }

    // ViewModels - register with interface type for KMP compatibility
    single<SettingsViewModelInterface> { SettingsViewModel() }

    // Database driver
    single { DatabaseDriverFactory(androidContext()) }
}

package app.s4h.fomovoi.app.di

import app.s4h.fomovoi.core.audio.AudioRecorder
import app.s4h.fomovoi.core.audio.createAudioRecorder
import app.s4h.fomovoi.core.data.local.DatabaseDriverFactory
import app.s4h.fomovoi.core.sharing.ShareService
import app.s4h.fomovoi.core.sharing.createAndroidShareService
import app.s4h.fomovoi.core.transcription.ModelManager
import app.s4h.fomovoi.core.transcription.TranscriptionService
import app.s4h.fomovoi.core.transcription.createSherpaOnnxTranscriptionService
import app.s4h.fomovoi.feature.settings.SettingsViewModel
import app.s4h.fomovoi.feature.settings.SettingsViewModelInterface
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidModule = module {
    // Platform-specific implementations
    single<AudioRecorder> { createAudioRecorder() }

    // Model management (must be defined before TranscriptionService)
    single { ModelManager(androidContext()) }

    // Use Sherpa-ONNX for continuous on-device transcription
    single<TranscriptionService> { createSherpaOnnxTranscriptionService(androidContext(), get()) }
    single<ShareService> { createAndroidShareService(androidContext()) }

    // ViewModels - register with interface type for KMP compatibility
    single<SettingsViewModelInterface> { SettingsViewModel() }

    // Database driver
    single { DatabaseDriverFactory(androidContext()) }
}

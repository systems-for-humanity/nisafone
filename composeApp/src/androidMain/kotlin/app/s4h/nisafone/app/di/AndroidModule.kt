package app.s4h.nisafone.app.di

import app.s4h.nisafone.core.audio.AndroidAudioRecorder
import app.s4h.nisafone.core.audio.AudioRecorder
import app.s4h.nisafone.core.data.local.DatabaseDriverFactory
import app.s4h.nisafone.core.sharing.ShareService
import app.s4h.nisafone.core.sharing.createAndroidShareService
import app.s4h.nisafone.core.transcription.ModelManager
import app.s4h.nisafone.core.transcription.TranscriptionService
import app.s4h.nisafone.core.transcription.createSherpaOnnxTranscriptionService
import app.s4h.nisafone.feature.recording.AndroidAutoStartScheduleTimer
import app.s4h.nisafone.feature.recording.AutoStartScheduleTimer
import app.s4h.nisafone.feature.recording.AndroidTitlePrefixRepository
import app.s4h.nisafone.feature.recording.TitlePrefixRepository
import app.s4h.nisafone.feature.settings.AndroidEmailSettingsRepository
import app.s4h.nisafone.feature.settings.EmailSettingsRepository
import app.s4h.nisafone.feature.settings.SettingsViewModel
import app.s4h.nisafone.feature.settings.SettingsViewModelInterface
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidModule = module {
    // Platform-specific implementations
    single<AudioRecorder> { AndroidAudioRecorder(androidContext()) }

    // Model management (must be defined before TranscriptionService)
    single { ModelManager(androidContext()) }

    // Use Sherpa-ONNX for continuous on-device transcription
    single<TranscriptionService> { createSherpaOnnxTranscriptionService(androidContext(), get()) }
    single<ShareService> { createAndroidShareService(androidContext()) }

    // Title prefix repository
    single<TitlePrefixRepository> { AndroidTitlePrefixRepository(androidContext()) }
    single<AutoStartScheduleTimer> { AndroidAutoStartScheduleTimer(androidContext()) }

    // Email settings repository
    single<EmailSettingsRepository> { AndroidEmailSettingsRepository(androidContext()) }

    // App-scoped settings state holder, injected via interface for KMP compatibility
    single<SettingsViewModelInterface> { SettingsViewModel(get(), get(), get()) }

    // Database driver
    single { DatabaseDriverFactory(androidContext()) }
}

package app.s4h.nisafone.app.di

import app.s4h.nisafone.core.audio.AudioRecorder
import app.s4h.nisafone.core.audio.createAudioRecorder
import app.s4h.nisafone.core.data.local.DatabaseDriverFactory
import app.s4h.nisafone.core.sharing.ShareService
import app.s4h.nisafone.core.sharing.createShareService
import app.s4h.nisafone.core.transcription.TranscriptionService
import app.s4h.nisafone.core.transcription.createTranscriptionService
import app.s4h.nisafone.feature.recording.AutoStartScheduleTimer
import app.s4h.nisafone.feature.recording.IosAutoStartScheduleTimer
import app.s4h.nisafone.feature.recording.IosTitlePrefixRepository
import app.s4h.nisafone.feature.recording.TitlePrefixRepository
import app.s4h.nisafone.feature.settings.EmailSettingsRepository
import app.s4h.nisafone.feature.settings.IosEmailSettingsRepository
import app.s4h.nisafone.feature.settings.IosSettingsViewModel
import app.s4h.nisafone.feature.settings.SettingsViewModelInterface
import org.koin.dsl.module

val iosModule = module {
    // Platform-specific implementations
    single<AudioRecorder> { createAudioRecorder() }
    single<TranscriptionService> { createTranscriptionService() }
    single<ShareService> { createShareService() }

    // Title prefix repository
    single<TitlePrefixRepository> { IosTitlePrefixRepository() }
    single<AutoStartScheduleTimer> { IosAutoStartScheduleTimer() }

    // Email settings repository
    single<EmailSettingsRepository> { IosEmailSettingsRepository() }

    // Settings ViewModel
    single<SettingsViewModelInterface> { IosSettingsViewModel(get()) }

    // Database driver
    single { DatabaseDriverFactory() }
}

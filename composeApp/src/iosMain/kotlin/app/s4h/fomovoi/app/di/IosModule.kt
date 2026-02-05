package app.s4h.fomovoi.app.di

import app.s4h.fomovoi.core.audio.AudioRecorder
import app.s4h.fomovoi.core.audio.createAudioRecorder
import app.s4h.fomovoi.core.data.local.DatabaseDriverFactory
import app.s4h.fomovoi.core.sharing.ShareService
import app.s4h.fomovoi.core.sharing.createShareService
import app.s4h.fomovoi.core.transcription.TranscriptionService
import app.s4h.fomovoi.core.transcription.createTranscriptionService
import org.koin.dsl.module

val iosModule = module {
    // Platform-specific implementations
    single<AudioRecorder> { createAudioRecorder() }
    single<TranscriptionService> { createTranscriptionService() }
    single<ShareService> { createShareService() }

    // Database driver
    single { DatabaseDriverFactory() }
}

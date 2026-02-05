package com.fomovoi.app.di

import com.fomovoi.core.audio.AudioRecorder
import com.fomovoi.core.audio.createAudioRecorder
import com.fomovoi.core.data.local.DatabaseDriverFactory
import com.fomovoi.core.sharing.ShareService
import com.fomovoi.core.sharing.createShareService
import com.fomovoi.core.transcription.TranscriptionService
import com.fomovoi.core.transcription.createTranscriptionService
import org.koin.dsl.module

val iosModule = module {
    // Platform-specific implementations
    single<AudioRecorder> { createAudioRecorder() }
    single<TranscriptionService> { createTranscriptionService() }
    single<ShareService> { createShareService() }

    // Database driver
    single { DatabaseDriverFactory() }
}

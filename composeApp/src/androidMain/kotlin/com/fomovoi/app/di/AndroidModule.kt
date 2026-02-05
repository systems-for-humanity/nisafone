package com.fomovoi.app.di

import com.fomovoi.core.audio.AudioRecorder
import com.fomovoi.core.audio.createAudioRecorder
import com.fomovoi.core.data.local.DatabaseDriverFactory
import com.fomovoi.core.sharing.ShareService
import com.fomovoi.core.sharing.createAndroidShareService
import com.fomovoi.core.transcription.TranscriptionService
import com.fomovoi.core.transcription.createAndroidTranscriptionService
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidModule = module {
    // Platform-specific implementations
    single<AudioRecorder> { createAudioRecorder() }
    single<TranscriptionService> { createAndroidTranscriptionService(androidContext()) }
    single<ShareService> { createAndroidShareService(androidContext()) }

    // Database driver
    single { DatabaseDriverFactory(androidContext()) }
}

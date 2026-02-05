package com.fomovoi.app.di

import com.fomovoi.core.data.local.DatabaseDriverFactory
import com.fomovoi.core.data.local.createDatabase
import com.fomovoi.core.data.repository.RecordingRepositoryImpl
import com.fomovoi.core.domain.usecase.DeleteRecordingUseCase
import com.fomovoi.core.domain.usecase.GetAllRecordingsUseCase
import com.fomovoi.core.domain.usecase.GetRecordingByIdUseCase
import com.fomovoi.core.domain.usecase.RecordingRepository
import com.fomovoi.core.domain.usecase.SaveRecordingUseCase
import com.fomovoi.core.domain.usecase.ToggleFavoriteUseCase
import com.fomovoi.feature.history.HistoryViewModel
import com.fomovoi.feature.recording.RecordingViewModel
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val commonModule = module {
    // JSON
    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    // Database
    single { createDatabase(get()) }

    // Repository
    single<RecordingRepository> { RecordingRepositoryImpl(get(), get()) }

    // Use cases
    factory { GetAllRecordingsUseCase(get()) }
    factory { GetRecordingByIdUseCase(get()) }
    factory { SaveRecordingUseCase(get()) }
    factory { DeleteRecordingUseCase(get()) }
    factory { ToggleFavoriteUseCase(get()) }

    // ViewModels
    viewModel {
        RecordingViewModel(
            audioRecorder = get(),
            transcriptionService = get(),
            shareService = get(),
            saveRecordingUseCase = get()
        )
    }

    viewModel {
        HistoryViewModel(
            getAllRecordingsUseCase = get(),
            deleteRecordingUseCase = get(),
            toggleFavoriteUseCase = get(),
            shareService = get()
        )
    }
}

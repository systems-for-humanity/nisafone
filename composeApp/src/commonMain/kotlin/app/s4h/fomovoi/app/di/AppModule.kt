package app.s4h.fomovoi.app.di

import app.s4h.fomovoi.core.data.local.DatabaseDriverFactory
import app.s4h.fomovoi.core.data.local.createDatabase
import app.s4h.fomovoi.core.data.repository.RecordingRepositoryImpl
import app.s4h.fomovoi.core.domain.usecase.DeleteRecordingUseCase
import app.s4h.fomovoi.core.domain.usecase.GetAllRecordingsUseCase
import app.s4h.fomovoi.core.domain.usecase.GetRecordingByIdUseCase
import app.s4h.fomovoi.core.domain.usecase.RecordingRepository
import app.s4h.fomovoi.core.domain.usecase.SaveRecordingUseCase
import app.s4h.fomovoi.core.domain.usecase.ToggleFavoriteUseCase
import app.s4h.fomovoi.feature.history.HistoryViewModel
import app.s4h.fomovoi.feature.recording.RecordingViewModel
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
            saveRecordingUseCase = get(),
            titlePrefixRepository = get()
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

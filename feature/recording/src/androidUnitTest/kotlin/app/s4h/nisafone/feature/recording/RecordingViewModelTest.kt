package app.s4h.nisafone.feature.recording

import app.s4h.nisafone.core.audio.AudioChunk
import app.s4h.nisafone.core.audio.AudioDevice
import app.s4h.nisafone.core.audio.AudioRecorder
import app.s4h.nisafone.core.audio.RecordingState
import app.s4h.nisafone.core.domain.model.Recording
import app.s4h.nisafone.core.domain.usecase.RecordingRepository
import app.s4h.nisafone.core.domain.usecase.SaveRecordingUseCase
import app.s4h.nisafone.core.domain.usecase.UpdateRecordingUseCase
import app.s4h.nisafone.core.sharing.ShareResult
import app.s4h.nisafone.core.sharing.ShareService
import app.s4h.nisafone.core.transcription.LanguageHint
import app.s4h.nisafone.core.transcription.Speaker
import app.s4h.nisafone.core.transcription.SpeechLanguage
import app.s4h.nisafone.core.transcription.TranscriptionEvent
import app.s4h.nisafone.core.transcription.TranscriptionResult
import app.s4h.nisafone.core.transcription.TranscriptionService
import app.s4h.nisafone.core.transcription.TranscriptionState
import app.s4h.nisafone.core.transcription.Utterance
import app.s4h.nisafone.feature.settings.EmailSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeShareService: FakeShareService
    private lateinit var fakeTranscriptionService: FakeTranscriptionService
    private lateinit var viewModel: RecordingViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeShareService = FakeShareService()
        fakeTranscriptionService = FakeTranscriptionService()
        viewModel = RecordingViewModel(
            audioRecorder = FakeAudioRecorder(),
            transcriptionService = fakeTranscriptionService,
            shareService = fakeShareService,
            saveRecordingUseCase = SaveRecordingUseCase(FakeRecordingRepository()),
            updateRecordingUseCase = UpdateRecordingUseCase(FakeRecordingRepository()),
            titlePrefixRepository = FakeTitlePrefixRepository(),
            emailSettingsRepository = FakeEmailSettingsRepository()
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun shareTranscription_passesShareTitleToShareService() = runTest {
        val utterance = Utterance(
            id = "1",
            text = "Hello world",
            speaker = Speaker(id = "s1", label = "Alice"),
            startTimeMs = 0,
            endTimeMs = 1000
        )
        fakeTranscriptionService.emitFinalResult(utterance)
        advanceUntilIdle()

        viewModel.shareTranscription("My Custom Title", "Default Speaker")
        advanceUntilIdle()

        assertEquals("My Custom Title", fakeShareService.lastTitle)
    }

    @Test
    fun shareTranscription_formatsUtterancesWithSpeakerLabels() = runTest {
        val utterance = Utterance(
            id = "1",
            text = "Hello world",
            speaker = Speaker(id = "s1", label = "Alice"),
            startTimeMs = 0,
            endTimeMs = 1000
        )
        fakeTranscriptionService.emitFinalResult(utterance)
        advanceUntilIdle()

        viewModel.shareTranscription("Title", "Default Speaker")
        advanceUntilIdle()

        val sharedText = fakeShareService.lastText!!
        assertTrue(
            sharedText.contains("[Alice]: Hello world"),
            "Expected '[Alice]: Hello world' in: $sharedText"
        )
    }

    @Test
    fun shareTranscription_usesDefaultSpeakerLabelForPartialText() = runTest {
        fakeTranscriptionService.emitPartialResult("partial text here")
        advanceUntilIdle()

        viewModel.shareTranscription("Title", "Sprecher")
        advanceUntilIdle()

        val sharedText = fakeShareService.lastText!!
        assertTrue(
            sharedText.contains("[Sprecher]: partial text here..."),
            "Expected '[Sprecher]: partial text here...' in: $sharedText"
        )
    }

    @Test
    fun shareTranscription_usesActualSpeakerLabelOverDefault() = runTest {
        fakeTranscriptionService.setCurrentSpeaker(Speaker(id = "s1", label = "Bob"))
        fakeTranscriptionService.emitPartialResult("some words")
        advanceUntilIdle()

        viewModel.shareTranscription("Title", "Default Speaker")
        advanceUntilIdle()

        val sharedText = fakeShareService.lastText!!
        assertTrue(
            sharedText.contains("[Bob]: some words..."),
            "Expected '[Bob]: some words...' in: $sharedText"
        )
        assertFalse(
            sharedText.contains("Default Speaker"),
            "Should not contain default speaker label when actual speaker is set"
        )
    }

    @Test
    fun shareTranscription_setsIsSharingBackToFalse() = runTest {
        assertFalse(viewModel.uiState.value.isSharing)

        viewModel.shareTranscription("Title", "Speaker")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSharing)
    }
}

// Test doubles

private class FakeShareService : ShareService {
    var lastText: String? = null
    var lastTitle: String? = null

    override suspend fun shareText(text: String, title: String?): ShareResult {
        lastText = text
        lastTitle = title
        return ShareResult.Success
    }

    override suspend fun shareTranscription(result: TranscriptionResult): ShareResult =
        ShareResult.Success

    override suspend fun sendEmail(to: String, subject: String, body: String): ShareResult =
        ShareResult.Success
}

private class FakeAudioRecorder : AudioRecorder {
    override val state = MutableStateFlow(RecordingState.IDLE)
    override val audioStream: Flow<AudioChunk> = emptyFlow()
    override val availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val selectedDevice = MutableStateFlow<AudioDevice?>(null)

    override suspend fun initialize() {}
    override suspend fun startRecording() {}
    override suspend fun stopRecording() {}
    override suspend fun selectDevice(device: AudioDevice) {}
    override fun release() {}
}

private class FakeTranscriptionService : TranscriptionService {
    override val state = MutableStateFlow(TranscriptionState.IDLE)
    private val _currentSpeaker = MutableStateFlow<Speaker?>(null)
    override val currentSpeaker: StateFlow<Speaker?> = _currentSpeaker
    override val currentLanguage = MutableStateFlow(SpeechLanguage.ENGLISH)
    override val currentLanguageHint = MutableStateFlow(LanguageHint.AUTO_DETECT)
    override val translateToEnglish = MutableStateFlow(true)
    override val availableLanguages = listOf(SpeechLanguage.ENGLISH)
    override val handlesAudioInternally = false

    private var eventCallback: ((TranscriptionEvent) -> Unit)? = null

    override val events: Flow<TranscriptionEvent> = callbackFlow {
        eventCallback = { event -> trySend(event) }
        awaitCancellation()
    }

    fun emitFinalResult(utterance: Utterance) {
        eventCallback?.invoke(TranscriptionEvent.FinalResult(utterance))
    }

    fun emitPartialResult(text: String) {
        eventCallback?.invoke(TranscriptionEvent.PartialResult(text))
    }

    fun setCurrentSpeaker(speaker: Speaker?) {
        _currentSpeaker.value = speaker
    }

    override suspend fun initialize() {}
    override suspend fun startTranscription() {}
    override suspend fun processAudioChunk(chunk: AudioChunk) {}
    override suspend fun stopTranscription(): TranscriptionResult? = null
    override suspend fun setLanguage(language: SpeechLanguage) {}
    override suspend fun setLanguageHint(hint: LanguageHint) {}
    override suspend fun setTranslateToEnglish(translate: Boolean) {}
    override fun release() {}
}

private class FakeRecordingRepository : RecordingRepository {
    override fun getAllRecordings(): Flow<List<Recording>> = emptyFlow()
    override fun getRecordingById(id: String): Flow<Recording?> = emptyFlow()
    override suspend fun saveRecording(recording: Recording) {}
    override suspend fun deleteRecording(id: String) {}
    override suspend fun updateRecording(recording: Recording) {}
}

private class FakeTitlePrefixRepository : TitlePrefixRepository {
    override val prefixes = MutableStateFlow(listOf("Recording"))
    override val selectedPrefix = MutableStateFlow("Recording")

    override fun addPrefix(prefix: String) {}
    override fun removePrefix(prefix: String) {}
    override fun selectPrefix(prefix: String) {}
}

private class FakeEmailSettingsRepository : EmailSettingsRepository {
    override val autoEmailEnabled = MutableStateFlow(false)
    override val emailAddress = MutableStateFlow("")

    override fun setAutoEmailEnabled(enabled: Boolean) {}
    override fun setEmailAddress(address: String) {}
}

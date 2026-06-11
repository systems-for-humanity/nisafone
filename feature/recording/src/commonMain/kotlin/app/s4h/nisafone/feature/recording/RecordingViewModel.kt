package app.s4h.nisafone.feature.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import app.s4h.nisafone.core.audio.AudioDevice
import app.s4h.nisafone.core.audio.AudioRecorder
import app.s4h.nisafone.core.audio.RecordingState
import app.s4h.nisafone.core.domain.model.Recording
import app.s4h.nisafone.core.domain.usecase.DeleteRecordingUseCase
import app.s4h.nisafone.core.domain.usecase.SaveRecordingUseCase
import app.s4h.nisafone.core.domain.usecase.UpdateRecordingUseCase
import app.s4h.nisafone.core.sharing.ShareResult
import app.s4h.nisafone.core.sharing.ShareService
import app.s4h.nisafone.feature.settings.AutoStartSchedule
import app.s4h.nisafone.feature.settings.EmailSettingsRepository
import app.s4h.nisafone.core.transcription.Speaker
import app.s4h.nisafone.core.transcription.SpeechLanguage
import app.s4h.nisafone.core.transcription.TranscriptionEvent
import app.s4h.nisafone.core.transcription.TranscriptionResult
import app.s4h.nisafone.core.transcription.TranscriptionService
import app.s4h.nisafone.core.transcription.TranscriptionState
import app.s4h.nisafone.core.transcription.Utterance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class RecordingUiState(
    val recordingState: RecordingState = RecordingState.IDLE,
    val transcriptionState: TranscriptionState = TranscriptionState.IDLE,
    val currentSpeaker: Speaker? = null,
    val currentLanguage: SpeechLanguage = SpeechLanguage.ENGLISH,
    val availableLanguages: List<SpeechLanguage> = emptyList(),
    val partialText: String = "",
    val utterances: List<Utterance> = emptyList(),
    val elapsedTimeMs: Long = 0,
    val isSharing: Boolean = false,
    val error: String? = null,
    val titlePrefixes: List<String> = listOf("Recording"),
    val selectedTitlePrefix: String = "Recording",
    val showAddPrefixDialog: Boolean = false,
    val availableAudioDevices: List<AudioDevice> = emptyList(),
    val selectedAudioDevice: AudioDevice? = null
) {
    // Recording state considers both audio recorder and transcription service
    // (some transcription services handle audio internally)
    val isRecording: Boolean
        get() = recordingState == RecordingState.RECORDING ||
                transcriptionState == TranscriptionState.TRANSCRIBING

    val canStart: Boolean
        get() = recordingState == RecordingState.IDLE &&
                transcriptionState == TranscriptionState.READY

    val formattedTime: String
        get() {
            val seconds = (elapsedTimeMs / 1000) % 60
            val minutes = (elapsedTimeMs / (1000 * 60)) % 60
            val hours = elapsedTimeMs / (1000 * 60 * 60)
            return if (hours > 0) {
                "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
            } else {
                "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
            }
        }
}

class RecordingViewModel(
    private val audioRecorder: AudioRecorder,
    private val transcriptionService: TranscriptionService,
    private val shareService: ShareService,
    private val saveRecordingUseCase: SaveRecordingUseCase,
    private val updateRecordingUseCase: UpdateRecordingUseCase,
    private val deleteRecordingUseCase: DeleteRecordingUseCase,
    private val titlePrefixRepository: TitlePrefixRepository,
    private val emailSettingsRepository: EmailSettingsRepository,
    private val autoStartScheduleTimer: AutoStartScheduleTimer,
    private val enableAutoStartScheduleMonitor: Boolean = true
) : ViewModel() {

    private val logger = Logger.withTag("RecordingViewModel")

    private companion object {
        const val AUTO_START_RETRY_DELAY_MS = 5_000L
    }

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private var startTimeMs: Long = 0
    private val collectedUtterances = mutableListOf<Utterance>()
    private var isInitialized = false
    private var isInitializing = false
    private var currentRecordingId: String? = null

    private var audioStreamJob: Job? = null
    private var autoStartScheduleJob: Job? = null
    private var autoStopRecordingJob: Job? = null
    private val triggeredScheduleKeys = mutableSetOf<String>()
    private var scheduleKeyDate: LocalDate? = null

    private data class ScheduledAutoStartTrigger(
        val schedule: AutoStartSchedule,
        val triggerAtEpochMillis: Long,
        val date: LocalDate,
        val key: String
    )

    init {
        observeAudioState()
        observeAudioDevices()
        observeTranscriptionState()
        observeTranscriptionEvents()
        observeLanguage()
        observeTitlePrefixes()
        if (enableAutoStartScheduleMonitor) {
            observeAutoStartSchedule()
        }
    }

    private fun observeAudioState() {
        viewModelScope.launch {
            audioRecorder.state.collect { state ->
                _uiState.update { it.copy(recordingState = state) }
            }
        }
    }

    private fun observeAudioDevices() {
        viewModelScope.launch {
            audioRecorder.availableDevices.collect { devices ->
                _uiState.update { it.copy(availableAudioDevices = devices) }
            }
        }
        viewModelScope.launch {
            audioRecorder.selectedDevice.collect { device ->
                _uiState.update { it.copy(selectedAudioDevice = device) }
            }
        }
    }

    private fun observeTranscriptionState() {
        viewModelScope.launch {
            transcriptionService.state.collect { state ->
                _uiState.update { it.copy(transcriptionState = state) }
            }
        }

        viewModelScope.launch {
            transcriptionService.currentSpeaker.collect { speaker ->
                _uiState.update { it.copy(currentSpeaker = speaker) }
            }
        }
    }

    private fun observeLanguage() {
        // Set available languages immediately
        _uiState.update { it.copy(availableLanguages = transcriptionService.availableLanguages) }

        viewModelScope.launch {
            transcriptionService.currentLanguage.collect { language ->
                _uiState.update { it.copy(currentLanguage = language) }
            }
        }
    }

    private fun observeTitlePrefixes() {
        viewModelScope.launch {
            titlePrefixRepository.prefixes.collect { prefixes ->
                _uiState.update { it.copy(titlePrefixes = prefixes) }
            }
        }
        viewModelScope.launch {
            titlePrefixRepository.selectedPrefix.collect { prefix ->
                _uiState.update { it.copy(selectedTitlePrefix = prefix) }
            }
        }
    }

    private fun observeTranscriptionEvents() {
        viewModelScope.launch {
            transcriptionService.events.collect { event ->
                when (event) {
                    is TranscriptionEvent.PartialResult -> {
                        _uiState.update { it.copy(partialText = event.text) }
                        // Save partial transcription to database
                        saveCurrentTranscription()
                    }
                    is TranscriptionEvent.FinalResult -> {
                        collectedUtterances.add(event.utterance)
                        _uiState.update {
                            it.copy(
                                utterances = collectedUtterances.toList(),
                                partialText = ""
                            )
                        }
                        // Save with final utterance
                        saveCurrentTranscription()
                    }
                    is TranscriptionEvent.Info -> {
                        logger.i { "Transcription notice: ${event.message}" }
                        _uiState.update { it.copy(error = event.message) }
                    }
                    is TranscriptionEvent.Error -> {
                        logger.e { "Transcription error: ${event.message}" }
                        _uiState.update { it.copy(error = event.message) }
                    }
                }
            }
        }
    }

    private fun observeAutoStartSchedule() {
        autoStartScheduleJob = viewModelScope.launch {
            combine(
                emailSettingsRepository.autoStartScheduleEnabled,
                emailSettingsRepository.autoStartSchedules
            ) { enabled, schedules ->
                enabled to schedules
            }.collect { (enabled, schedules) ->
                scheduleNextAutoStart(enabled, schedules)
            }
        }
    }

    private fun scheduleNextAutoStart(
        enabled: Boolean = emailSettingsRepository.autoStartScheduleEnabled.value,
        schedules: List<AutoStartSchedule> = emailSettingsRepository.autoStartSchedules.value
    ) {
        autoStartScheduleTimer.cancel()

        if (!enabled) {
            return
        }

        val trigger = findNextAutoStartTrigger(schedules) ?: return
        logger.d {
            "Scheduling auto-start id=${trigger.schedule.id}, time=${trigger.schedule.time}, triggerAt=${trigger.triggerAtEpochMillis}"
        }
        autoStartScheduleTimer.schedule(trigger.triggerAtEpochMillis) {
            viewModelScope.launch {
                handleScheduledAutoStart(trigger)
            }
        }
    }

    private fun handleScheduledAutoStart(trigger: ScheduledAutoStartTrigger) {
        if (!emailSettingsRepository.autoStartScheduleEnabled.value) {
            scheduleNextAutoStart()
            return
        }

        val currentSchedule = emailSettingsRepository.autoStartSchedules.value
            .firstOrNull { it.id == trigger.schedule.id }
            ?.normalized()

        if (currentSchedule == null || currentSchedule != trigger.schedule.normalized()) {
            scheduleNextAutoStart()
            return
        }

        resetTriggeredScheduleKeysIfNeeded(trigger.date)
        if (trigger.key in triggeredScheduleKeys) {
            scheduleNextAutoStart()
            return
        }

        if (_uiState.value.isRecording) {
            triggeredScheduleKeys.add(trigger.key)
            scheduleNextAutoStart()
            return
        }

        if (!_uiState.value.canStart) {
            if (_uiState.value.transcriptionState == TranscriptionState.IDLE ||
                _uiState.value.transcriptionState == TranscriptionState.ERROR
            ) {
                initialize()
            }
            scheduleAutoStartRetry(trigger)
            return
        }

        triggeredScheduleKeys.add(trigger.key)
        logger.d {
            "Starting scheduled recording id=${currentSchedule.id}, days=${currentSchedule.daysOfWeek}, time=${currentSchedule.time}, duration=${currentSchedule.durationMinutes}m"
        }
        startRecordingInternal(currentSchedule.durationMinutes)
        scheduleNextAutoStart()
    }

    private fun scheduleAutoStartRetry(trigger: ScheduledAutoStartTrigger) {
        val retryAtEpochMillis = Clock.System.now().toEpochMilliseconds() + AUTO_START_RETRY_DELAY_MS
        autoStartScheduleTimer.schedule(retryAtEpochMillis) {
            viewModelScope.launch {
                handleScheduledAutoStart(trigger)
            }
        }
    }

    private fun findNextAutoStartTrigger(
        schedules: List<AutoStartSchedule>
    ): ScheduledAutoStartTrigger? {
        val nowInstant = Clock.System.now()
        val nowEpochMillis = nowInstant.toEpochMilliseconds()
        val timeZone = TimeZone.currentSystemDefault()
        val now = nowInstant.toLocalDateTime(timeZone)

        return schedules.mapNotNull { schedule ->
            val parsed = parseScheduledTime(schedule.time) ?: return@mapNotNull null
            if (schedule.durationMinutes <= 0) return@mapNotNull null
            val normalized = schedule.normalized()

            (0..7).mapNotNull { dayOffset ->
                val date = now.date.plus(DatePeriod(days = dayOffset))
                if (dayOfWeekToNumber(date.dayOfWeek) !in normalized.daysOfWeek) {
                    return@mapNotNull null
                }

                val triggerTime = LocalDateTime(
                    year = date.year,
                    monthNumber = date.monthNumber,
                    dayOfMonth = date.dayOfMonth,
                    hour = parsed.first,
                    minute = parsed.second
                )
                val triggerAtEpochMillis = triggerTime.toInstant(timeZone).toEpochMilliseconds()
                if (triggerAtEpochMillis <= nowEpochMillis) {
                    return@mapNotNull null
                }

                ScheduledAutoStartTrigger(
                    schedule = normalized,
                    triggerAtEpochMillis = triggerAtEpochMillis,
                    date = date,
                    key = autoStartScheduleKey(date, normalized.id, parsed)
                )
            }.minByOrNull { it.triggerAtEpochMillis }
        }.minByOrNull { it.triggerAtEpochMillis }
    }

    private fun autoStartScheduleKey(
        date: LocalDate,
        scheduleId: String,
        parsedTime: Pair<Int, Int>
    ): String {
        return "$date-$scheduleId-${parsedTime.first}:${parsedTime.second}"
    }

    private fun resetTriggeredScheduleKeysIfNeeded(date: LocalDate) {
        if (scheduleKeyDate != date) {
            scheduleKeyDate = date
            triggeredScheduleKeys.clear()
        }
    }

    private fun parseScheduledTime(time: String): Pair<Int, Int>? {
        val parts = time.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (parts[0].length != 2 || parts[1].length != 2) return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    private fun dayOfWeekToNumber(dayOfWeek: DayOfWeek): Int {
        return when (dayOfWeek) {
            DayOfWeek.MONDAY -> 1
            DayOfWeek.TUESDAY -> 2
            DayOfWeek.WEDNESDAY -> 3
            DayOfWeek.THURSDAY -> 4
            DayOfWeek.FRIDAY -> 5
            DayOfWeek.SATURDAY -> 6
            DayOfWeek.SUNDAY -> 7
        }
    }

    private suspend fun saveCurrentTranscription() {
        val recordingId = currentRecordingId ?: return
        val state = _uiState.value

        // Build current transcription from utterances and partial text
        val allUtterances = collectedUtterances.toMutableList()
        if (state.partialText.isNotBlank()) {
            val speaker = state.currentSpeaker ?: Speaker(id = "speaker_1", label = "Speaker 1")
            allUtterances.add(
                Utterance(
                    id = "partial",
                    text = state.partialText,
                    speaker = speaker,
                    startTimeMs = 0,
                    endTimeMs = state.elapsedTimeMs
                )
            )
        }

        if (allUtterances.isEmpty()) return

        val now = Clock.System.now()
        val fullText = allUtterances.joinToString(" ") { it.text }

        val transcription = TranscriptionResult(
            id = recordingId,
            utterances = allUtterances,
            fullText = fullText,
            durationMs = state.elapsedTimeMs,
            createdAt = now,
            isComplete = false
        )

        val recording = Recording(
            id = recordingId,
            title = state.selectedTitlePrefix,
            transcription = transcription,
            createdAt = now,
            updatedAt = now,
            durationMs = state.elapsedTimeMs
        )

        try {
            updateRecordingUseCase(recording)
        } catch (e: Exception) {
            logger.e(e) { "Failed to save transcription progress" }
        }
    }

    fun initialize() {
        // Reinitialize if transcription service is in ERROR state (e.g., after downloading a model)
        val transcriptionState = transcriptionService.state.value
        val needsReinit = transcriptionState == TranscriptionState.ERROR || transcriptionState == TranscriptionState.IDLE

        if (isInitializing) {
            logger.d { "initialize() called while initialization is already in progress, skipping" }
            return
        }
        if (isInitialized && !needsReinit) {
            logger.d { "initialize() called but already initialized, skipping" }
            return
        }
        logger.d { "initialize() called (reinit=$needsReinit, state=$transcriptionState)" }
        isInitializing = true
        viewModelScope.launch {
            try {
                logger.d { "Initializing audioRecorder..." }
                audioRecorder.initialize()
                logger.d { "Initializing transcriptionService..." }
                transcriptionService.initialize()
                isInitialized = true
                logger.d { "Initialization complete" }
            } catch (e: Exception) {
                logger.e(e) { "Failed to initialize" }
                _uiState.update { it.copy(error = "Failed to initialize: ${e.message}") }
            } finally {
                isInitializing = false
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun startRecording() {
        startRecordingInternal(autoStopAfterMinutes = null)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun startRecordingInternal(autoStopAfterMinutes: Int?) {
        logger.d { "startRecording() called, canStart=${_uiState.value.canStart}" }
        viewModelScope.launch {
            try {
                autoStopRecordingJob?.cancel()
                autoStopRecordingJob = null

                collectedUtterances.clear()
                _uiState.update {
                    it.copy(
                        utterances = emptyList(),
                        partialText = "",
                        error = null
                    )
                }

                startTimeMs = Clock.System.now().toEpochMilliseconds()
                val now = Clock.System.now()

                // Generate a new recording ID and create initial entry in database
                currentRecordingId = Uuid.random().toString()
                val initialRecording = Recording(
                    id = currentRecordingId!!,
                    title = _uiState.value.selectedTitlePrefix,
                    transcription = null,
                    createdAt = now,
                    updatedAt = now,
                    durationMs = 0
                )
                saveRecordingUseCase(initialRecording)
                logger.d { "Created initial recording entry: ${currentRecordingId}" }

                // Only start audio recorder if transcription service doesn't handle audio internally
                // (e.g., Android SpeechRecognizer handles its own audio capture)
                if (!transcriptionService.handlesAudioInternally) {
                    logger.d { "Starting audioRecorder..." }
                    audioRecorder.startRecording()

                    // Pipe audio stream to transcription service
                    audioStreamJob = viewModelScope.launch {
                        audioRecorder.audioStream.collect { chunk ->
                            transcriptionService.processAudioChunk(chunk)
                        }
                    }
                } else {
                    logger.d { "Transcription service handles audio internally, skipping audioRecorder" }
                }

                logger.d { "Starting transcriptionService..." }
                transcriptionService.startTranscription()
                logger.d { "Recording and transcription started" }

                // Start elapsed time tracking
                startElapsedTimeUpdates()
                scheduleAutoStopIfNeeded(autoStopAfterMinutes)
            } catch (e: Exception) {
                logger.e(e) { "Failed to start recording" }
                _uiState.update { it.copy(error = "Failed to start: ${e.message}") }
            }
        }
    }

    private fun scheduleAutoStopIfNeeded(autoStopAfterMinutes: Int?) {
        if (autoStopAfterMinutes == null || autoStopAfterMinutes <= 0) {
            autoStopRecordingJob?.cancel()
            autoStopRecordingJob = null
            return
        }

        val targetRecordingId = currentRecordingId ?: return
        autoStopRecordingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(autoStopAfterMinutes * 60_000L)
            if (currentRecordingId == targetRecordingId && _uiState.value.isRecording) {
                logger.d { "Auto-stopping scheduled recording after $autoStopAfterMinutes minutes" }
                stopRecording()
            }
        }
    }

    private fun startElapsedTimeUpdates() {
        viewModelScope.launch {
            while (_uiState.value.isRecording) {
                val elapsed = Clock.System.now().toEpochMilliseconds() - startTimeMs
                _uiState.update { it.copy(elapsedTimeMs = elapsed) }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    fun stopRecording() {
        logger.d { "stopRecording() called, isRecording=${_uiState.value.isRecording}" }
        viewModelScope.launch {
            try {
                autoStopRecordingJob?.cancel()
                autoStopRecordingJob = null

                // Stop audio recorder first — this drains any remaining buffered
                // audio (important for Bluetooth SCO which has extra latency)
                if (!transcriptionService.handlesAudioInternally) {
                    audioRecorder.stopRecording()
                }

                // Now stop piping — all drained audio has been forwarded
                audioStreamJob?.cancel()
                audioStreamJob = null
                logger.d { "Calling transcriptionService.stopTranscription()" }
                val result = transcriptionService.stopTranscription()
                logger.d { "stopTranscription returned: ${result?.utterances?.size ?: 0} utterances" }

                // Save final recording with complete transcription
                val recordingId = currentRecordingId
                if (recordingId != null) {
                    if (result != null && result.utterances.isNotEmpty()) {
                        logger.d { "Saving final recording with ${result.utterances.size} utterances" }
                        val finalTranscription = result.copy(
                            id = recordingId,
                            isComplete = true
                        )
                        saveFinalRecording(recordingId, finalTranscription)

                        // Auto-email if enabled
                        sendAutoEmail(finalTranscription)
                    } else if (collectedUtterances.isEmpty()) {
                        // Nothing was ever transcribed — remove the placeholder row
                        // created at start so history doesn't fill with empty entries
                        logger.d { "No transcription captured, deleting empty recording $recordingId" }
                        deleteRecordingUseCase(recordingId)
                    }
                }
                currentRecordingId = null
            } catch (e: Exception) {
                logger.e(e) { "Failed to stop recording" }
                _uiState.update { it.copy(error = "Failed to stop: ${e.message}") }
            }
        }
    }

    private suspend fun saveFinalRecording(recordingId: String, transcription: TranscriptionResult) {
        val now = Clock.System.now()
        val recording = Recording(
            id = recordingId,
            title = _uiState.value.selectedTitlePrefix,
            transcription = transcription,
            createdAt = now,
            updatedAt = now,
            durationMs = transcription.durationMs
        )
        updateRecordingUseCase(recording)
    }

    private suspend fun sendAutoEmail(transcription: TranscriptionResult) {
        val autoEmailEnabled = emailSettingsRepository.autoEmailEnabled.value
        val emailAddress = emailSettingsRepository.emailAddress.value

        if (!autoEmailEnabled || emailAddress.isBlank()) {
            logger.d { "Auto-email disabled or no email address configured" }
            return
        }

        logger.d { "Sending auto-email to: $emailAddress" }
        val title = _uiState.value.selectedTitlePrefix
        val subject = "nisafone: $title"
        val body = buildTranscriptionText()

        when (val result = shareService.sendEmail(emailAddress, subject, body)) {
            is ShareResult.Success -> {
                logger.d { "Auto-email opened successfully" }
            }
            is ShareResult.Error -> {
                logger.e { "Auto-email failed: ${result.message}" }
                _uiState.update { it.copy(error = "Failed to send email: ${result.message}") }
            }
            is ShareResult.Cancelled -> {
                logger.d { "Auto-email cancelled" }
            }
        }
    }

    fun shareTranscription(shareTitle: String, defaultSpeakerLabel: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSharing = true) }

            val text = buildTranscriptionText(defaultSpeakerLabel)
            shareService.shareText(text, shareTitle)

            _uiState.update { it.copy(isSharing = false) }
        }
    }

    private fun buildTranscriptionText(defaultSpeakerLabel: String = "Speaker"): String {
        return buildString {
            _uiState.value.utterances.forEach { utterance ->
                appendLine("[${utterance.speaker.label}]: ${utterance.text}")
                appendLine()
            }
            if (_uiState.value.partialText.isNotBlank()) {
                appendLine("[${_uiState.value.currentSpeaker?.label ?: defaultSpeakerLabel}]: ${_uiState.value.partialText}...")
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun selectAudioDevice(device: AudioDevice) {
        viewModelScope.launch {
            try {
                audioRecorder.selectDevice(device)
            } catch (e: Exception) {
                logger.e(e) { "Failed to select audio device" }
                _uiState.update { it.copy(error = e.message ?: "Failed to select device") }
            }
        }
    }

    fun selectTitlePrefix(prefix: String) {
        titlePrefixRepository.selectPrefix(prefix)
    }

    fun addTitlePrefix(prefix: String) {
        titlePrefixRepository.addPrefix(prefix)
        _uiState.update { it.copy(showAddPrefixDialog = false) }
    }

    fun showAddPrefixDialog() {
        _uiState.update { it.copy(showAddPrefixDialog = true) }
    }

    fun hideAddPrefixDialog() {
        _uiState.update { it.copy(showAddPrefixDialog = false) }
    }

    fun setLanguage(language: SpeechLanguage) {
        viewModelScope.launch {
            try {
                transcriptionService.setLanguage(language)
            } catch (e: Exception) {
                logger.e(e) { "Failed to set language" }
                _uiState.update { it.copy(error = "Failed to change language: ${e.message}") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoStartScheduleJob?.cancel()
        autoStartScheduleTimer.cancel()
        autoStopRecordingJob?.cancel()
        // audioRecorder and transcriptionService are app-scoped singletons shared
        // with the next ViewModel instance, so don't release() them here — just
        // stop an in-flight recording so the microphone isn't left running
        if (_uiState.value.isRecording) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                runCatching { audioRecorder.stopRecording() }
                    .onFailure { logger.e(it) { "Failed to stop recorder on clear" } }
                runCatching { transcriptionService.stopTranscription() }
                    .onFailure { logger.e(it) { "Failed to stop transcription on clear" } }
            }
        }
    }
}

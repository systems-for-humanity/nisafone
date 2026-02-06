package app.s4h.nisafone.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

actual fun createAudioRecorder(): AudioRecorder = AndroidAudioRecorder()

class AndroidAudioRecorder(
    private val context: Context? = null
) : AudioRecorder {
    private val logger = Logger.withTag("AndroidAudioRecorder")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var audioManager: AudioManager? = null
    private var selectedDeviceInfo: AudioDeviceInfo? = null

    private val _state = MutableStateFlow(RecordingState.IDLE)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _audioStream = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)
    override val audioStream: Flow<AudioChunk> = _audioStream.asSharedFlow()

    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioDevice?>(null)
    override val selectedDevice: StateFlow<AudioDevice?> = _selectedDevice.asStateFlow()

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            enumerateDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            enumerateDevices()
        }
    }

    override suspend fun initialize() {
        logger.d { "Initializing audio recorder" }
        if (context != null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager?.registerAudioDeviceCallback(deviceCallback, null)
            enumerateDevices()
        } else {
            val defaultDevice = AudioDevice(
                id = "default",
                name = "Default Microphone",
                isDefault = true
            )
            _availableDevices.value = listOf(defaultDevice)
            _selectedDevice.value = defaultDevice
        }
    }

    private fun enumerateDevices() {
        val manager = audioManager ?: return
        val inputDevices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val devices = inputDevices.map { info ->
            AudioDevice(
                id = info.id.toString(),
                name = buildDeviceName(info),
                isDefault = false
            )
        }

        if (devices.isEmpty()) {
            val fallback = AudioDevice(id = "default", name = "Default Microphone", isDefault = true)
            _availableDevices.value = listOf(fallback)
            if (_selectedDevice.value == null) {
                _selectedDevice.value = fallback
            }
            return
        }

        _availableDevices.value = devices
        // If no device selected yet, or current selection was removed, pick the first (built-in mic)
        val currentId = _selectedDevice.value?.id
        if (currentId == null || devices.none { it.id == currentId }) {
            _selectedDevice.value = devices.first()
            selectedDeviceInfo = inputDevices.first()
        }
    }

    private fun buildDeviceName(info: AudioDeviceInfo): String {
        val typeName = when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            else -> "Microphone"
        }
        val productName = info.productName?.toString()?.takeIf { it.isNotBlank() && it != "0" }
        return if (productName != null && productName != typeName) {
            "$typeName ($productName)"
        } else {
            typeName
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startRecording() {
        if (_state.value == RecordingState.RECORDING) {
            logger.w { "Already recording" }
            return
        }

        logger.d { "Starting recording" }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ).coerceAtLeast(4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            logger.e { "Failed to initialize AudioRecord" }
            _state.value = RecordingState.ERROR
            return
        }

        // Apply preferred device if one is selected
        selectedDeviceInfo?.let { deviceInfo ->
            audioRecord?.setPreferredDevice(deviceInfo)
            logger.d { "Set preferred device: ${deviceInfo.id}" }
        }

        audioRecord?.startRecording()
        _state.value = RecordingState.RECORDING

        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            val startTime = System.currentTimeMillis()

            while (isActive && _state.value == RecordingState.RECORDING) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    val chunk = AudioChunk(
                        data = buffer.copyOf(bytesRead),
                        timestampMs = System.currentTimeMillis() - startTime,
                        sampleRate = SAMPLE_RATE,
                        channels = 1
                    )
                    _audioStream.emit(chunk)
                }
            }
        }
    }

    override suspend fun stopRecording() {
        logger.d { "Stopping recording" }
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        _state.value = RecordingState.IDLE
    }

    override suspend fun selectDevice(device: AudioDevice) {
        logger.d { "Selecting device: ${device.name}" }
        _selectedDevice.value = device

        // Find the matching AudioDeviceInfo
        val manager = audioManager
        if (manager != null) {
            val inputDevices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            selectedDeviceInfo = inputDevices.firstOrNull { it.id.toString() == device.id }

            // If currently recording, apply the device change immediately
            audioRecord?.let { record ->
                selectedDeviceInfo?.let { info ->
                    record.setPreferredDevice(info)
                    logger.d { "Applied preferred device change during recording: ${info.id}" }
                }
            }
        }
    }

    override fun release() {
        logger.d { "Releasing audio recorder" }
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        scope.cancel()
        audioRecord?.release()
        audioRecord = null
    }
}

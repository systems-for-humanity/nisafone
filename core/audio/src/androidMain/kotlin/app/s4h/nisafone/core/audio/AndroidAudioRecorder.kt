package app.s4h.nisafone.core.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
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
    private var useBluetooth = false

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

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    enumerateDevices()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun initialize() {
        logger.d { "Initializing audio recorder" }
        if (context != null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager?.registerAudioDeviceCallback(deviceCallback, null)

            // Listen for BT connection changes
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
            context.registerReceiver(bluetoothReceiver, filter)

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

    @SuppressLint("MissingPermission")
    private fun enumerateDevices() {
        val manager = audioManager ?: return
        val inputDevices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        // On API 31+, also discover Bluetooth devices via communication device API
        val allDeviceInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevices = manager.availableCommunicationDevices
            val deviceMap = linkedMapOf<Int, AudioDeviceInfo>()
            inputDevices.forEach { deviceMap[it.id] = it }
            // Only add communication devices that are audio sources (skip sink-only)
            commDevices.filter { it.isSource }.forEach { deviceMap.putIfAbsent(it.id, it) }
            deviceMap.values.toList()
        } else {
            inputDevices.toList()
        }

        val devices = allDeviceInfos.map { info ->
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
            selectedDeviceInfo = allDeviceInfos.firstOrNull()
        }
    }

    private fun buildDeviceName(info: AudioDeviceInfo): String {
        val typeName = when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            else -> "Microphone " + info.type
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

        logger.d { "Starting recording (bluetooth=$useBluetooth)" }

        val audioSource = if (useBluetooth) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ).coerceAtLeast(4096)

        audioRecord = AudioRecord(
            audioSource,
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

        val manager = audioManager ?: return

        // Clean up any existing BT state
        cleanupBluetooth(manager)

        // Check if this is a Bluetooth device
        val allDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val deviceMap = linkedMapOf<Int, AudioDeviceInfo>()
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { deviceMap[it.id] = it }
            manager.availableCommunicationDevices.filter { it.isSource }.forEach { deviceMap.putIfAbsent(it.id, it) }
            deviceMap.values.toList()
        } else {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }
        val deviceInfo = allDevices.firstOrNull { it.id.toString() == device.id }
        selectedDeviceInfo = deviceInfo

        // For Bluetooth SCO devices, set up communication mode and start SCO
        if (deviceInfo != null && (deviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    deviceInfo.type == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
            useBluetooth = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.setCommunicationDevice(deviceInfo)
                logger.d { "Set communication device for Bluetooth: ${deviceInfo.id}" }
            }
            @Suppress("DEPRECATION")
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            manager.startBluetoothSco()
            logger.d { "Started Bluetooth SCO" }
        } else {
            useBluetooth = false
        }

        // If currently recording, apply the device change immediately
        audioRecord?.let { record ->
            selectedDeviceInfo?.let { info ->
                record.setPreferredDevice(info)
                logger.d { "Applied preferred device change during recording: ${info.id}" }
            }
        }
    }

    private fun cleanupBluetooth(manager: AudioManager) {
        useBluetooth = false
        @Suppress("DEPRECATION")
        manager.stopBluetoothSco()
        manager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.clearCommunicationDevice()
        }
    }

    override fun release() {
        logger.d { "Releasing audio recorder" }
        audioManager?.let { cleanupBluetooth(it) }
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        try {
            context?.unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) { }
        scope.cancel()
        audioRecord?.release()
        audioRecord = null
    }
}

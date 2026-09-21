package com.audiopro.djmrec.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.DjmRecApplication
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.audio.ChannelLevel
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.audio.RecordingHealth
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.audio.StereoLevels
import com.audiopro.djmrec.service.RecordingService
import com.audiopro.djmrec.streaming.LiveStreamConfig
import com.audiopro.djmrec.streaming.LiveStreamState
import com.audiopro.djmrec.streaming.LiveStreamStatus
import com.audiopro.djmrec.streaming.LivePlatform
import com.audiopro.djmrec.streaming.StreamSetupState
import com.audiopro.djmrec.streaming.StreamSetupStatus
import com.audiopro.djmrec.streaming.StreamingSetupRepository
import com.audiopro.djmrec.streaming.YouTubePrivacy
import com.audiopro.djmrec.streaming.YouTubeBroadcastState
import com.audiopro.djmrec.streaming.YouTubeBroadcastStatus
import com.audiopro.djmrec.streaming.YouTubeFinishResult
import com.audiopro.djmrec.streaming.YouTubeLiveSession
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import com.audiopro.djmrec.usb.UsbAudioManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Wires the USB device stream, the bound [RecordingService], and the Compose UI together.
 * Transport commands are always sent as service `Intent`s (works whether or not the bind has
 * completed yet); the bind is only used to *observe* the service's StateFlows.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS_NAME = "settings"
        private const val KEY_USB_CHANNEL_OFFSET = "usb_channel_offset"
        private const val KEY_WAVEFORM_ENABLED = "waveform_enabled"
        private const val KEY_INCLUDE_MIC = "include_mic_in_mix"
        private fun captureLevelKey(device: UsbAudioDeviceInfo) = "usb_capture_level_${device.vendorId}_${device.productId}"
        private fun sampleRateKey(device: UsbAudioDeviceInfo) = "sample_rate_${device.vendorId}_${device.productId}"
    }

    private val usbAudioManager = (application as DjmRecApplication).usbAudioManager
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val deviceState: StateFlow<UsbAudioDeviceInfo?> = usbAudioManager.deviceState
    val usbInputs = usbAudioManager.inputs
    val connectionNotice = usbAudioManager.connectionNotice
    fun refreshInputs() = usbAudioManager.refreshInputs()

    fun selectInput(deviceName: String) {
        if (saving.value || liveStreamState.value.isActive ||
            _recordingState.value is RecordingState.Recording || _recordingState.value is RecordingState.Paused ||
            _recordingState.value is RecordingState.Preparing || deviceState.value?.deviceName == deviceName) return
        val service = boundService ?: return
        _recordingState.value = RecordingState.Preparing
        viewModelScope.launch {
            if (service.state.value is RecordingState.Monitoring || service.state.value is RecordingState.Error) {
                sendCommand(RecordingService.ACTION_STOP)
                if (withTimeoutOrNull(5_000L) { service.state.first { it is RecordingState.Idle } } == null) {
                    _recordingState.value = RecordingState.Error("Input change timed out. Reconnect your device.")
                    return@launch
                }
            }
            _recordingState.value = RecordingState.Idle
            if (!usbAudioManager.selectDevice(deviceName))
                _recordingState.value = RecordingState.Error("Input unavailable. Refresh the device list.")
        }
    }

    private fun captureChannelOffset(device: UsbAudioDeviceInfo): Int =
        _usbChannelOffset.value.takeIf { it >= 0 }
            ?: device.allInOneProfile?.recordChannelOffset
            ?: if (device.pioneerMixerProfile != null) UsbAudioManager.AUTO_CHANNEL_OFFSET else 0

    /** User-chosen capture rate for this mixer when it advertises several, else the device default. */
    private fun sampleRateFor(device: UsbAudioDeviceInfo): Int {
        val chosen = _selectedSampleRate.value
        return if (chosen > 0 && chosen in device.supportedSampleRates) chosen else device.preferredSampleRate
    }
    private val sessionEvents = (application as DjmRecApplication).sessionEvents
    val lastSaved = sessionEvents.lastSaved.asStateFlow()
    val markerCount = sessionEvents.markerCount.asStateFlow()
    val keepScreenOn = MutableStateFlow(prefs.getBoolean("keep_screen_on", false))
    val smoothWaveform = MutableStateFlow(prefs.getBoolean("smooth_waveform", true))
    val confirmStop = MutableStateFlow(prefs.getBoolean("confirm_stop", true))

    fun setKeepScreenOn(value: Boolean) { prefs.edit().putBoolean("keep_screen_on", value).apply(); keepScreenOn.value = value }
    fun setSmoothWaveform(value: Boolean) { prefs.edit().putBoolean("smooth_waveform", value).apply(); smoothWaveform.value = value }
    fun setConfirmStop(value: Boolean) { prefs.edit().putBoolean("confirm_stop", value).apply(); confirmStop.value = value }
    fun dismissSavedRecording() { sessionEvents.lastSaved.value = null }
    fun addTrackMarker() = sendCommand(RecordingService.ACTION_MARK_TRACK)
    fun stopAndClose() = sendCommand(RecordingService.ACTION_STOP_ALL)


    private val floorLevel = ChannelLevel(peakDb = -60f, rmsDb = -60f, isClipping = false)

    val saving = MutableStateFlow(false)
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _levels = MutableStateFlow(StereoLevels(floorLevel, floorLevel))
    val levels: StateFlow<StereoLevels> = _levels.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    private val emptyWaveform = FloatArray(0)
    private val _waveformBins = MutableStateFlow(emptyWaveform)
    val waveformBins: StateFlow<FloatArray> = _waveformBins.asStateFlow()

    private val _recordingHealth = MutableStateFlow(RecordingHealth.Ready)
    val recordingHealth: StateFlow<RecordingHealth> = _recordingHealth.asStateFlow()

    private val _liveStreamState = MutableStateFlow(LiveStreamState())
    val liveStreamState: StateFlow<LiveStreamState> = _liveStreamState.asStateFlow()

    private val youtubeCoordinator = (application as DjmRecApplication).youtubeCoordinator
    val streamSetupState = youtubeCoordinator.streamSetupState
    val youtubeBroadcastState = youtubeCoordinator.youtubeBroadcastState
    val liveStreamKey = androidx.compose.runtime.mutableStateOf("")

    private val _waveformEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_WAVEFORM_ENABLED, true)
    )
    val waveformEnabled: StateFlow<Boolean> = _waveformEnabled.asStateFlow()

    // 0 dB by default: REC OUT is already at line level and the old +12 dB boost hard-clipped hot
    // masters. Mixer-side USB level lives in the vendor register (see captureLevelStep).
    private val _recordingGainDb = MutableStateFlow(prefs.getInt("recording_gain_db", 0).coerceIn(-12, 24))
    val recordingGainDb: StateFlow<Int> = _recordingGainDb.asStateFlow()

    /** Route REC OUT including the mic bus (vendor source 0x0a) vs. without mic (0x0e). */
    private val _includeMicInMix = MutableStateFlow(prefs.getBoolean(KEY_INCLUDE_MIC, true))
    val includeMicInMix: StateFlow<Boolean> = _includeMicInMix.asStateFlow()

    /** Index into [com.audiopro.djmrec.usb.PioneerMixerProfile.CAPTURE_LEVEL_STEPS_DB]; -1 = leave mixer setting. */
    private val _captureLevelStep = MutableStateFlow(-1)
    val captureLevelStep: StateFlow<Int> = _captureLevelStep.asStateFlow()

    /** 0 = device default (48 kHz when offered); otherwise a rate from the device's advertised list. */
    private val _selectedSampleRate = MutableStateFlow(0)
    val selectedSampleRate: StateFlow<Int> = _selectedSampleRate.asStateFlow()

    private val _selectedFormat = MutableStateFlow(
        RecordingFormat.entries.firstOrNull { it.name == prefs.getString("recording_format", "WAV") } ?: RecordingFormat.WAV)
    val selectedFormat: StateFlow<RecordingFormat> = _selectedFormat.asStateFlow()
    val availableFormats: List<RecordingFormat> = RecordingFormat.entries

    private val _usbChannelOffset = MutableStateFlow(
        prefs.getInt(KEY_USB_CHANNEL_OFFSET, UsbAudioManager.AUTO_CHANNEL_OFFSET)
    )
    val usbChannelOffset: StateFlow<Int> = _usbChannelOffset.asStateFlow()

    @SuppressLint("StaticFieldLeak")
    private var boundService: RecordingService? = null
    private var isBound = false
    private var uiVisible = false
    private var waveformVisible = false

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        boundService?.setVisualsVisible(uiVisible, waveformVisible)
    }

    fun setWaveformVisible(visible: Boolean) {
        waveformVisible = visible
        boundService?.setVisualsVisible(uiVisible, waveformVisible)
    }
    private var livePreview: SurfaceView? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as RecordingService.LocalBinder).getService()
            boundService = service
            isBound = true
            _recordingState.value = service.state.value
            service.setVisualsVisible(uiVisible, waveformVisible)
            service.setWaveformEnabled(_waveformEnabled.value)
            service.setRecordingGainDb(_recordingGainDb.value)
            viewModelScope.launch { service.saving.collect { saving.value = it } }
            viewModelScope.launch { service.state.collect { _recordingState.value = it } }
            viewModelScope.launch { service.levels.collect { _levels.value = it } }
            viewModelScope.launch { service.elapsedMillis.collect { _elapsedMillis.value = it } }
            viewModelScope.launch { service.waveformBins.collect { _waveformBins.value = it } }
            viewModelScope.launch { service.health.collect { _recordingHealth.value = it } }
            viewModelScope.launch { service.liveState.collect { _liveStreamState.value = it } }
            livePreview?.let(service::attachLivePreview)
            ensureLiveMonitoring()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
        }
    }

    init {
        val context = getApplication<Application>()
        // Obsolete experiments (Android-audio-stack capture, top DJM-REC port). The DJM-REC /
        // MULTI I/O port is a USB *host* port for iPhone/iPad; Android cannot act as a USB audio
        // device, so only the rear PC/Mac port can ever work.
        prefs.edit().remove("force_android_capture").remove("djmrec_port_mode").apply()
        context.bindService(
            Intent(context, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        viewModelScope.launch {
            var activeDeviceKey: String? = null
            deviceState.collect { device ->
                if (device == null) {
                    activeDeviceKey = null
                    return@collect
                }
                val key = "${device.deviceName}:${device.vendorId}:${device.productId}"
                if (key == activeDeviceKey) return@collect
                activeDeviceKey = key
                val pairKey = "channel_pair_${device.vendorId}_${device.productId}"
                val storedPair = prefs.getInt(pairKey, UsbAudioManager.AUTO_CHANNEL_OFFSET)
                _usbChannelOffset.value = storedPair.takeIf { it >= 0 && it % 2 == 0 && it + 1 < device.channelCount }
                    ?: UsbAudioManager.AUTO_CHANNEL_OFFSET
                _captureLevelStep.value = prefs.getInt(captureLevelKey(device), -1)
                    .takeIf { device.pioneerMixerProfile?.supportsCaptureLevel == true } ?: -1
                _selectedSampleRate.value = prefs.getInt(sampleRateKey(device), 0)
                    .takeIf { it in device.supportedSampleRates } ?: 0
                delay(250L)
                if (_recordingState.value is RecordingState.Idle ||
                    _recordingState.value is RecordingState.Error) {
                    ensureLiveMonitoring()
                }
            }
        }
    }

    fun selectFormat(format: RecordingFormat) {
        if ((_recordingState.value is RecordingState.Idle ||
                _recordingState.value is RecordingState.Monitoring ||
                _recordingState.value is RecordingState.Error) &&
            format in availableFormats) {
            _selectedFormat.value = format
            prefs.edit().putString("recording_format", format.name).apply()
        }
    }

    fun setRecordingGainDb(gainDb: Int) {
        if (saving.value) return
        if (_recordingState.value is RecordingState.Recording ||
            _recordingState.value is RecordingState.Paused ||
            _recordingState.value is RecordingState.Preparing) return
        val value = gainDb.coerceIn(-12, 24)
        _recordingGainDb.value = value
        prefs.edit().putInt("recording_gain_db", value).apply()
        boundService?.setRecordingGainDb(value)
    }

    fun setWaveformEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAVEFORM_ENABLED, enabled).apply()
        _waveformEnabled.value = enabled
        if (!enabled) _waveformBins.value = emptyWaveform
        boundService?.setWaveformEnabled(enabled)
    }

    fun rescanUsbDevices() {
        if (saving.value) return
        if (_recordingState.value is RecordingState.Recording ||
            _recordingState.value is RecordingState.Paused ||
            _recordingState.value is RecordingState.Preparing) return
        usbAudioManager.scanForConnectedMixer()
        ensureLiveMonitoring()
    }

    fun ensureLiveMonitoring() {
        if (sessionEvents.closeRequested.value || boundService == null) return
        val context = getApplication<Application>()
        if (deviceState.value != null &&
            (_recordingState.value is RecordingState.Idle ||
                _recordingState.value is RecordingState.Error)) {
            startMonitoringDevice(context)
        }
    }

    fun setUsbChannelOffset(offset: Int) {
        if (saving.value || liveStreamState.value.isActive) return
        if (_recordingState.value is RecordingState.Recording ||
            _recordingState.value is RecordingState.Paused ||
            _recordingState.value is RecordingState.Preparing) return
        val channels = deviceState.value?.channelCount ?: return
        if (offset >= 0 && (offset % 2 != 0 || offset + 1 >= channels)) return
        val sanitized = if (offset < 0) UsbAudioManager.AUTO_CHANNEL_OFFSET else offset
        if (sanitized == _usbChannelOffset.value) return
        val device = deviceState.value ?: return
        prefs.edit().putInt("channel_pair_${device.vendorId}_${device.productId}", sanitized).apply()
        _usbChannelOffset.value = sanitized

        // The offset is only read when the native capture session opens (baked into the
        // service Intent), so an already-running monitor stream won't pick up the new pair on
        // its own. Restart it here so the VU meter reflects the new pair immediately -- this is
        // the whole point of exposing the picker: audition pairs against live audio, the same
        // way the Windows Setting Utility lets you flip MIX/REC OUT between USB pairs and watch
        // levels move. Never auto-restart out of Recording/Paused -- that would kill a take.
        if (_recordingState.value is RecordingState.Monitoring) {
            val context = getApplication<Application>()
            _recordingState.value = RecordingState.Preparing
            viewModelScope.launch {
                sendCommand(RecordingService.ACTION_STOP)
                val stopped = withTimeoutOrNull(5_000L) {
                    boundService?.state?.first { it is RecordingState.Idle || it is RecordingState.Error }
                }
                if (stopped == null) {
                    _recordingState.value = RecordingState.Error("Could not change the USB pair. Stop capture and reconnect the mixer.")
                } else {
                    _recordingState.value = stopped
                    startMonitoringDevice(context)
                }
            }
        }
    }

    private fun captureSettingsLocked(): Boolean =
        saving.value || liveStreamState.value.isActive ||
            _recordingState.value is RecordingState.Recording ||
            _recordingState.value is RecordingState.Paused ||
            _recordingState.value is RecordingState.Preparing

    /** Restarts a live monitor so a changed vendor setting (route, level, rate) takes effect. */
    private fun restartMonitorIfRunning() {
        if (_recordingState.value !is RecordingState.Monitoring) return
        val context = getApplication<Application>()
        _recordingState.value = RecordingState.Preparing
        viewModelScope.launch {
            sendCommand(RecordingService.ACTION_STOP)
            val stopped = withTimeoutOrNull(5_000L) {
                boundService?.state?.first { it is RecordingState.Idle || it is RecordingState.Error }
            }
            if (stopped == null) {
                _recordingState.value = RecordingState.Error("Could not apply the capture setting. Stop capture and reconnect the mixer.")
            } else {
                _recordingState.value = stopped
                startMonitoringDevice(context)
            }
        }
    }

    fun setIncludeMicInMix(enabled: Boolean) {
        if (captureSettingsLocked()) return
        if (enabled == _includeMicInMix.value) return
        prefs.edit().putBoolean(KEY_INCLUDE_MIC, enabled).apply()
        _includeMicInMix.value = enabled
        restartMonitorIfRunning()
    }

    fun setCaptureLevelStep(step: Int) {
        if (captureSettingsLocked()) return
        val device = deviceState.value ?: return
        if (device.pioneerMixerProfile?.supportsCaptureLevel != true) return
        val sanitized = step.coerceIn(-1, com.audiopro.djmrec.usb.PioneerMixerProfile.CAPTURE_LEVEL_STEPS_DB.size - 1)
        if (sanitized == _captureLevelStep.value) return
        prefs.edit().putInt(captureLevelKey(device), sanitized).apply()
        _captureLevelStep.value = sanitized
        restartMonitorIfRunning()
    }

    fun setSampleRate(rate: Int) {
        if (captureSettingsLocked()) return
        val device = deviceState.value ?: return
        val sanitized = if (rate in device.supportedSampleRates) rate else 0
        if (sanitized == _selectedSampleRate.value) return
        prefs.edit().putInt(sampleRateKey(device), sanitized).apply()
        _selectedSampleRate.value = sanitized
        restartMonitorIfRunning()
    }

    fun startRecording() {
        val context = getApplication<Application>()

        if (_recordingState.value is RecordingState.Preparing) {
            startServiceSafely(
                context,
                Intent(context, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_FORMAT, _selectedFormat.value.nativeValue)
            )
            return
        }

        // If already monitoring, begin encoding to file.
        if (_recordingState.value is RecordingState.Monitoring) {
            startServiceSafely(
                context,
                Intent(context, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_FORMAT, _selectedFormat.value.nativeValue)
            )
            return
        }

        val device = deviceState.value ?: run {
            usbAudioManager.scanForConnectedMixer("record-button-rescan")
            return
        }

        val intent = buildCaptureIntent(context, device, RecordingService.ACTION_START) ?: return
        intent.putExtra(RecordingService.EXTRA_FORMAT, _selectedFormat.value.nativeValue)
        val hadIsoHandle = intent.hasExtra(RecordingService.EXTRA_USB_FD)
        if (startForegroundServiceSafely(context, intent, hadIsoHandle)) {
            boundService?.setDeviceLabel(device.productName)
        }
    }

    /**
     * Shared by [startRecording] and [startMonitoringDevice]: opens the raw USB handle (routing
     * the selected MIX pair, mic preference and capture level on the way) or falls back to the
     * AAudio device id for plain stereo class devices. Returns null after publishing an error.
     */
    private fun buildCaptureIntent(context: Context, device: UsbAudioDeviceInfo, action: String): Intent? {
        val sampleRate = sampleRateFor(device)
        val channelOffset = captureChannelOffset(device)
        val includeMic = _includeMicInMix.value
        val intent = Intent(context, RecordingService::class.java).apply {
            this.action = action
            putExtra(RecordingService.EXTRA_SAMPLE_RATE, sampleRate)
            putExtra(RecordingService.EXTRA_BIT_DEPTH, device.bitResolution)
        }
        val handle = if (device.requiresIsoCapture) {
            usbAudioManager.openIsoCaptureHandle(channelOffset, includeMic, _captureLevelStep.value)
        } else {
            null
        }
        if (handle == null && (device.requiresIsoCapture || device.audioManagerDeviceId < 0)) {
            _recordingState.value = RecordingState.Error("Cannot open this USB input. Reconnect the mixer and rescan; check USB permission.")
            return null
        }
        if (handle != null) {
            intent.putExtra(RecordingService.EXTRA_CAPTURE_MODE, RecordingService.CAPTURE_MODE_USB_ISO)
            intent.putExtra(RecordingService.EXTRA_USB_FD, handle.fd)
            intent.putExtra(RecordingService.EXTRA_USB_INTERFACE, handle.interfaceNumber)
            intent.putExtra(RecordingService.EXTRA_USB_ALT_SETTING, handle.alternateSetting)
            intent.putExtra(RecordingService.EXTRA_USB_ENDPOINT, handle.endpointAddress)
            intent.putExtra(RecordingService.EXTRA_USB_MAX_PACKET_SIZE, handle.maxPacketSize)
            intent.putExtra(RecordingService.EXTRA_USB_TOTAL_CHANNELS, handle.totalChannels)
            intent.putExtra(RecordingService.EXTRA_USB_SUBFRAME_SIZE, handle.subframeSize)
            intent.putExtra(RecordingService.EXTRA_USB_CHANNEL_OFFSET, channelOffset)
            intent.putExtra(RecordingService.EXTRA_USB_INCLUDE_MIC, includeMic)
            intent.putExtra(RecordingService.EXTRA_USB_CLOCK_CONTROL_INTERFACE, handle.clockControlInterfaceNumber)
            intent.putExtra(RecordingService.EXTRA_USB_CLOCK_SOURCE_ID, handle.clockSourceId)
            intent.putExtra(RecordingService.EXTRA_USB_CLOCK_FREQUENCY_SETTABLE, handle.clockSupportsFrequencySet)
            intent.putExtra(RecordingService.EXTRA_USB_FEEDBACK_ENDPOINT, handle.feedbackEndpointAddress)
            intent.putExtra(RecordingService.EXTRA_USB_FEEDBACK_MAX_PACKET_SIZE, handle.feedbackMaxPacketSize)
            intent.putExtra(RecordingService.EXTRA_USB_VENDOR_ID, handle.vendorId)
            intent.putExtra(RecordingService.EXTRA_USB_PRODUCT_ID, handle.productId)
            intent.putExtra(RecordingService.EXTRA_USB_RAW_DESCRIPTORS, handle.rawDescriptors)
        } else {
            intent.putExtra(RecordingService.EXTRA_CAPTURE_MODE, RecordingService.CAPTURE_MODE_AAUDIO)
            intent.putExtra(RecordingService.EXTRA_DEVICE_ID, device.audioManagerDeviceId)
            intent.putExtra(RecordingService.EXTRA_CHANNEL_COUNT, if (device.isPioneer) 2 else device.channelCount)
        }
        return intent
    }

    /** Opens the audio stream for live monitoring (meters + waveform) without writing a file. */
    private fun startMonitoringDevice(context: Context) {
        if (sessionEvents.closeRequested.value) return
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (_recordingState.value !is RecordingState.Idle && _recordingState.value !is RecordingState.Error) return
        val device = deviceState.value ?: return
        _recordingState.value = RecordingState.Preparing
        val intent = buildCaptureIntent(context, device, RecordingService.ACTION_MONITOR) ?: return
        val hadIsoHandle = intent.hasExtra(RecordingService.EXTRA_USB_FD)
        if (startForegroundServiceSafely(context, intent, hadIsoHandle)) {
            boundService?.setDeviceLabel(device.productName)
            Log.i(TAG, "USB attached: auto-starting live monitor for ${device.productName}")
        }
    }

    fun pauseRecording() = sendCommand(RecordingService.ACTION_PAUSE)
    fun resumeRecording() = sendCommand(RecordingService.ACTION_RESUME)
    fun stopRecording() = sendCommand(RecordingService.ACTION_STOP)

    private fun captureReady(state: RecordingState): Boolean =
        state is RecordingState.Monitoring ||
            state is RecordingState.Recording ||
            state is RecordingState.Paused

    fun startLiveStream(config: LiveStreamConfig) {
        val context = getApplication<Application>()
        _liveStreamState.value = LiveStreamState(
            status = LiveStreamStatus.PREPARING,
            message = "Arming USB mixer",
            platform = config.platform,
            videoMode = config.videoMode
        )
        viewModelScope.launch {
            if (!captureReady(_recordingState.value)) {
                if (deviceState.value == null) {
                    _liveStreamState.value = LiveStreamState(
                        status = LiveStreamStatus.ERROR,
                        message = "Connect a USB mixer before going live",
                        platform = config.platform,
                        videoMode = config.videoMode
                    )
                    return@launch
                }
                startMonitoringDevice(context)
                val readyState = withTimeoutOrNull(15_000L) {
                    recordingState.first { state ->
                        captureReady(state) || state is RecordingState.Error
                    }
                }
                if (readyState == null || !captureReady(readyState)) {
                    _liveStreamState.value = LiveStreamState(
                        status = LiveStreamStatus.ERROR,
                        message = (readyState as? RecordingState.Error)?.message
                            ?: "USB mixer did not become ready. Reconnect and try again.",
                        platform = config.platform,
                        videoMode = config.videoMode
                    )
                    return@launch
                }
            }

            _liveStreamState.value = LiveStreamState(
                status = LiveStreamStatus.PREPARING,
                message = "Starting ${config.platform.label} encoders",
                platform = config.platform,
                videoMode = config.videoMode
            )
            context.startService(
                Intent(context, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START_LIVE)
                    .putExtra(RecordingService.EXTRA_LIVE_PLATFORM, config.platform.name)
                    .putExtra(RecordingService.EXTRA_LIVE_SERVER_URL, config.serverUrl)
                    .putExtra(RecordingService.EXTRA_LIVE_STREAM_KEY, config.streamKey)
                    .putExtra(RecordingService.EXTRA_LIVE_VIDEO_MODE, config.videoMode.name)
                    .putExtra(RecordingService.EXTRA_LIVE_PORTRAIT, config.portrait)
                    .putExtra(RecordingService.EXTRA_LIVE_ARTWORK_URI, config.artworkUri)
                    .putExtra(RecordingService.EXTRA_LIVE_AUDIO_BITRATE, config.audioBitrate)
            )
        }
    }

    fun stopLiveStream() {
        sendCommand(RecordingService.ACTION_STOP_LIVE)
        youtubeCoordinator.finishYouTubeSession()
    }

    fun prepareYouTubeDestination(accessToken: String, title: String, privacy: YouTubePrivacy) =
        youtubeCoordinator.prepareYouTubeDestination(accessToken, title, privacy)
    fun setStreamSetupError(platform: LivePlatform, message: String) = youtubeCoordinator.setStreamSetupError(platform, message)
    fun consumeStreamCredentials() = youtubeCoordinator.consumeStreamCredentials()
    fun cancelStreamSetup() = youtubeCoordinator.cancelStreamSetup()

    fun attachLivePreview(surfaceView: SurfaceView) {
        livePreview = surfaceView
        boundService?.attachLivePreview(surfaceView)
    }

    fun detachLivePreview() {
        boundService?.detachLivePreview()
        livePreview = null
    }

    fun switchLiveCamera() = boundService?.switchLiveCamera()

    private fun sendCommand(action: String) {
        val context = getApplication<Application>()
        startServiceSafely(context, Intent(context, RecordingService::class.java).setAction(action))
    }

    /**
     * Same background-start restriction as [startForegroundServiceSafely], but for plain
     * `startService()`: observed crashing with `BackgroundServiceStartNotAllowedException` when
     * a USB detach (`usbDeviceReceiver`, see `UsbAudioManager`) triggers an ACTION_STOP a few
     * minutes after the user last touched the app -- a background `BroadcastReceiver` doesn't
     * count as enough "foreground-ness" for Android to allow it. Whatever command this was
     * carrying (start/pause/resume/stop) is either already moot (service already gone) or not
     * actionable by the user right now (they're not looking at the app); either way, this only
     * needs to not crash it.
     */
    private fun startServiceSafely(context: Context, intent: Intent) {
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startService(${intent.action}) refused by the OS: ${e.message}")
        }
    }

    /**
     * Android 12+ refuses `startForegroundService()` outright (throwing
     * `ForegroundServiceStartNotAllowedException`, an `IllegalStateException`) when it decides
     * the app isn't in a state that justifies it -- observed on-device as an intermittent crash
     * right when the record button (or an auto-restart after a USB channel-pair change) tried to
     * start the service. There's no reliable way to predict the OS's call in advance, so this
     * just makes the failure a visible error instead of a fatal crash. `hadIsoHandle` releases
     * the just-opened libusb connection on failure -- otherwise it leaks open (never handed to a
     * service that would close it) and blocks the next attempt from claiming the interface.
     */
    private fun startForegroundServiceSafely(
        context: Context,
        intent: Intent,
        hadIsoHandle: Boolean = false
    ): Boolean {
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startForegroundService refused by the OS: ${e.message}")
            if (hadIsoHandle) usbAudioManager.releaseIsoCaptureConnection()
            _recordingState.value = RecordingState.Error(
                "Android blocked starting the recording service -- try pressing record again"
            )
            false
        }
    }

    override fun onCleared() {
        detachLivePreview()
        if (isBound) {
            getApplication<Application>().unbindService(connection)
            isBound = false
        }
        boundService = null
        super.onCleared()
    }
}

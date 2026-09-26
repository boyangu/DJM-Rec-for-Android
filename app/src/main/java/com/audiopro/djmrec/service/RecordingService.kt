package com.audiopro.djmrec.service

import android.Manifest

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.audiopro.djmrec.DjmRecApplication
import com.audiopro.djmrec.MainActivity
import com.audiopro.djmrec.R
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.audio.ChannelLevel
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.audio.RecordingHealth
import com.audiopro.djmrec.audio.RecordingHealthEvaluator
import com.audiopro.djmrec.audio.RecordingHealthInput
import com.audiopro.djmrec.audio.RecordingHealthLevel
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.audio.SignalDetector
import com.audiopro.djmrec.audio.StereoLevels
import com.audiopro.djmrec.storage.PendingRecordingOutput
import com.audiopro.djmrec.storage.RecordingOutputManager
import com.audiopro.djmrec.storage.RecordingSessionStore
import com.audiopro.djmrec.storage.RecordingStoragePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Foreground service hosting the entire recording session so the OS cannot kill the process
 * mid-capture. Exposes a [LocalBinder] for the UI's ViewModel to observe state directly, and
 * also reacts to notification action buttons (Pause/Resume/Stop) via `onStartCommand`.
 */
class RecordingService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.audiopro.djmrec.action.START"
        const val ACTION_MONITOR = "com.audiopro.djmrec.action.MONITOR"
        const val ACTION_PAUSE = "com.audiopro.djmrec.action.PAUSE"
        const val ACTION_RESUME = "com.audiopro.djmrec.action.RESUME"
        const val ACTION_STOP_ALL = "com.audiopro.djmrec.action.STOP_ALL"
        const val ACTION_MARK_TRACK = "com.audiopro.djmrec.action.MARK_TRACK"
        const val ACTION_STOP = "com.audiopro.djmrec.action.STOP"

        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_SAMPLE_RATE = "extra_sample_rate"
        const val EXTRA_BIT_DEPTH = "extra_bit_depth"
        const val EXTRA_CHANNEL_COUNT = "extra_channel_count"
        const val EXTRA_FORMAT = "extra_format"

        /** [EXTRA_CAPTURE_MODE] value: standard AAudio/AudioRecord path via [EXTRA_DEVICE_ID]. */
        const val CAPTURE_MODE_AAUDIO = 0
        /** [EXTRA_CAPTURE_MODE] value: raw libusb isochronous path via the EXTRA_USB_* extras. */
        const val CAPTURE_MODE_USB_ISO = 1
        const val EXTRA_CAPTURE_MODE = "extra_capture_mode"

        // --- Raw USB iso capture params (only used when EXTRA_CAPTURE_MODE == CAPTURE_MODE_USB_ISO) ---
        /** `UsbDeviceConnection.getFileDescriptor()`; see [UsbAudioManager.openIsoCaptureHandle]. */
        const val EXTRA_USB_FD = "extra_usb_fd"
        const val EXTRA_USB_INTERFACE = "extra_usb_interface"
        const val EXTRA_USB_ALT_SETTING = "extra_usb_alt_setting"
        const val EXTRA_USB_ENDPOINT = "extra_usb_endpoint"
        const val EXTRA_USB_MAX_PACKET_SIZE = "extra_usb_max_packet_size"
        const val EXTRA_USB_TOTAL_CHANNELS = "extra_usb_total_channels"
        const val EXTRA_USB_SUBFRAME_SIZE = "extra_usb_subframe_size"
        const val EXTRA_USB_CHANNEL_OFFSET = "extra_usb_channel_offset"
        /** Route REC OUT with (true) or without (false) the mic bus on models offering both. */
        const val EXTRA_USB_INCLUDE_MIC = "extra_usb_include_mic"
        /** Manual overrides: -1 follow profile, 0 off, 1 on; see CaptureOverride. */
        const val EXTRA_USB_PLAYBACK_OVERRIDE = "extra_usb_playback_override"
        const val EXTRA_USB_ENDPOINT_RATE_OVERRIDE = "extra_usb_endpoint_rate_override"
        const val EXTRA_USB_ALLOW_FORMAT_MISMATCH = "extra_usb_allow_format_mismatch"
        const val EXTRA_USB_CLOCK_CONTROL_INTERFACE = "extra_usb_clock_control_interface"
        const val EXTRA_USB_CLOCK_SOURCE_ID = "extra_usb_clock_source_id"
        const val EXTRA_USB_CLOCK_FREQUENCY_SETTABLE = "extra_usb_clock_frequency_settable"
        const val EXTRA_USB_FEEDBACK_ENDPOINT = "extra_usb_feedback_endpoint"
        const val EXTRA_USB_FEEDBACK_MAX_PACKET_SIZE = "extra_usb_feedback_max_packet_size"
        const val EXTRA_USB_VENDOR_ID = "extra_usb_vendor_id"
        const val EXTRA_USB_PRODUCT_ID = "extra_usb_product_id"
        const val EXTRA_USB_RAW_DESCRIPTORS = "extra_usb_raw_descriptors"

        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001
        private const val METER_UPDATE_INTERVAL_MS = 66L // ~15 fps, plenty for a VU meter
        private const val WAVEFORM_UPDATE_INTERVAL_MS = 33L // ~30 snapshots/s; UI scrolls at up to 60 fps
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L
        private const val HEALTH_UPDATE_INTERVAL_MS = 2_000L
        private const val CHECKPOINT_INTERVAL_MS = 5_000L
        private const val MAX_STALLED_USB_CHECKS = 3
        /** Shared with MainViewModel, which owns the Settings toggle. */
        const val KEY_DND_WHILE_RECORDING = "dnd_while_recording"
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val floorLevel = ChannelLevel(peakDb = -60f, rmsDb = -60f, isClipping = false)
    private val _levels = MutableStateFlow(StereoLevels(floorLevel, floorLevel))
    val levels: StateFlow<StereoLevels> = _levels.asStateFlow()

    /**
     * The single answer to "is the mixer feeding us audio", shared by the recorder label, the
     * health evaluator and the notification so they can never disagree. Owned by the monitor
     * thread; see [SignalDetector] for why a raw threshold comparison is not good enough.
     */
    private val signalDetector = SignalDetector()
    private val _signalPresent = MutableStateFlow(false)
    val signalPresent: StateFlow<Boolean> = _signalPresent.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    private val emptyWaveform = FloatArray(0)
    private val _waveformBins = MutableStateFlow(emptyWaveform)
    val waveformBins: StateFlow<FloatArray> = _waveformBins.asStateFlow()

    private val _health = MutableStateFlow(RecordingHealth.Ready)
    val health: StateFlow<RecordingHealth> = _health.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null

    /** Silences calls and notifications for the length of a set; see DoNotDisturbController. */
    private val doNotDisturb by lazy { DoNotDisturbController(this) }

    /**
     * Read at the moment it is needed rather than cached at startup, so toggling the setting
     * applies to the very next recording without any plumbing between the UI and the service.
     * SharedPreferences is an in-memory map after the first load, so this is not file I/O.
     */
    private fun readSetting(key: String, fallback: Boolean): Boolean =
        getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(key, fallback)

    // Dedicated urgent-audio-priority thread for pulling meter/elapsed data off the native
    // engine and refreshing the notification — kept separate from the main/UI thread so meter
    // polling never gets starved by UI work, matching the spec's thread-priority requirement.
    private lateinit var monitorThread: HandlerThread
    private lateinit var monitorHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentFormat: RecordingFormat = RecordingFormat.WAV
    private var currentBitDepth: Int = 24
    private var pendingRecordingFormat: RecordingFormat? = null
    private var currentOutput: PendingRecordingOutput? = null
    private var currentSessionId: String? = null
    private var currentPartIndex = 0
    private var currentPartStartedElapsed = 0L
    private var currentSampleRate = 48_000
    private var currentOutputChannels = 2
    private var bytesPerSecond = RecordingStoragePolicy.worstCaseBytesPerSecond(48_000, 2, 24)
    private var deviceLabel: String = "USB Mixer"
    /** True when the in-progress session opened via [startUsbIsoSession] rather than [startSession]. */
    private var isUsbIsoSession = false
    /** True when the audio stream is open for monitoring but no file is being written. */
    private var isMonitoringOnly = false
    @Volatile
    private var waveformEnabled = true
    @Volatile private var uiVisible = false
    @Volatile private var waveformVisible = false
    private var lastCheckpointRealtime = 0L
    /** Mic preference of the current USB session, needed if the route fallback fires later. */
    @Volatile private var currentIncludeMic = true
    private var lastUsbStats = LongArray(7)
    private var usbHealthInitialized = false
    private var stalledUsbChecks = 0
    private var lastXRunCount = 0
    @Volatile
    private var safetyStopPending = false

    /** True while a capture session exists in any form (arming, monitoring, recording, paused). */
    private fun sessionAlive(): Boolean =
        _state.value !is RecordingState.Idle && _state.value !is RecordingState.Error

    /** True while audio is actually flowing (the states the health evaluator understands). */
    private fun captureActive(): Boolean =
        _state.value is RecordingState.Recording || _state.value is RecordingState.Paused ||
            _state.value is RecordingState.Monitoring

    private val meterRunnable = object : Runnable {
        override fun run() {
            // Re-post for the whole session. Bailing out on a transient Preparing state used to
            // stop metering, notification refresh and -- worst -- the health/safety supervision
            // permanently until the next startPolling().
            if (!sessionAlive()) return
            if (captureActive()) {
                // getLevels() is a draining read: this is the peak of every callback since the
                // previous tick, not a snapshot, so nothing between polls is missed.
                val raw = AudioEngine.getLevels()
                val clipping = AudioEngine.isClipping()
                _levels.value = StereoLevels(
                    left = ChannelLevel(peakDb = raw[0], rmsDb = raw[1], isClipping = clipping),
                    right = ChannelLevel(peakDb = raw[2], rmsDb = raw[3], isClipping = clipping)
                )
                _signalPresent.value = signalDetector.update(
                    SystemClock.elapsedRealtime(), maxOf(raw[0], raw[2])
                )
                _elapsedMillis.value = AudioEngine.getElapsedMillis()
            }
            monitorHandler.postDelayed(this, if (uiVisible) METER_UPDATE_INTERVAL_MS else 1_000L)
        }
    }

    private val waveformRunnable = object : Runnable {
        override fun run() {
            if (captureActive()) {
                if (waveformEnabled && uiVisible && waveformVisible) {
                    _waveformBins.value = AudioEngine.getWaveformBins()
                    monitorHandler.postDelayed(this, WAVEFORM_UPDATE_INTERVAL_MS)
                }
            }
        }
    }

    private val notificationRunnable = object : Runnable {
        override fun run() {
            if (!sessionAlive()) return
            if (captureActive()) updateNotification()
            monitorHandler.postDelayed(this, NOTIFICATION_UPDATE_INTERVAL_MS)
        }
    }

    private val healthRunnable = object : Runnable {
        override fun run() {
            if (!sessionAlive()) return
            // Renews the wake lock's safety timeout every tick; see acquireWakeLock().
            acquireWakeLock()
            if (!captureActive()) {
                monitorHandler.postDelayed(this, HEALTH_UPDATE_INTERVAL_MS)
                return
            }

            // Native asked for the "route every MIX pair" fallback after a silent first window.
            // It must run here (Java UsbDeviceConnection path), never on the libusb event thread.
            if (isUsbIsoSession && AudioEngine.takeRouteFallbackRequest()) {
                (application as DjmRecApplication).usbAudioManager.applyRouteFallback(currentIncludeMic)
            }

            val recording = _state.value is RecordingState.Recording || _state.value is RecordingState.Paused
            val freeBytes = RecordingOutputManager.freeBytes()
            val remaining = if (freeBytes < 0) Long.MAX_VALUE
            else RecordingStoragePolicy.remainingSeconds(freeBytes, bytesPerSecond)
            val stats = AudioEngine.getUsbIsoTransferStats()
            val packetDelta = if (usbHealthInitialized) stats.getOrElse(0) { 0 } - lastUsbStats.getOrElse(0) { 0 } else 1
            val byteDelta = if (usbHealthInitialized) stats.getOrElse(4) { 0 } - lastUsbStats.getOrElse(4) { 0 } else 1
            val nonZeroDelta = if (usbHealthInitialized) stats.getOrElse(5) { 0 } - lastUsbStats.getOrElse(5) { 0 } else 1
            val missedDelta = if (usbHealthInitialized) stats.getOrElse(1) { 0 } - lastUsbStats.getOrElse(1) { 0 } else 0
            val resubmitDelta = if (usbHealthInitialized) stats.getOrElse(6) { 0 } - lastUsbStats.getOrElse(6) { 0 } else 0
            val xRunCount = AudioEngine.getXRunCount()
            val xRunDelta = (xRunCount - lastXRunCount).coerceAtLeast(0)
            lastUsbStats = stats
            lastXRunCount = xRunCount
            usbHealthInitialized = true

            val health = RecordingHealthEvaluator.evaluate(
                RecordingHealthInput(
                    recording = recording,
                    usbIso = isUsbIsoSession,
                    streamOpen = AudioEngine.isStreamOpen(),
                    freeBytes = freeBytes,
                    remainingSeconds = remaining,
                    packetDelta = packetDelta,
                    byteDelta = byteDelta,
                    nonZeroByteDelta = nonZeroDelta,
                    missedPacketDelta = missedDelta,
                    resubmitFailures = resubmitDelta,
                    xRuns = xRunDelta,
                    writerErrorCode = AudioEngine.getRecordingErrorCode(),
                    signalPresent = _signalPresent.value
                )
            )
            _health.value = health
            com.audiopro.djmrec.diagnostics.RemoteDiagnostics.health("${health.level}: ${health.message}")

            stalledUsbChecks = if (isUsbIsoSession && packetDelta <= 0) stalledUsbChecks + 1 else 0
            if (recording) {
                checkpointIfDue()
                when {
                    health.level == RecordingHealthLevel.ERROR -> requestSafetyStop(health.message)
                    health.level == RecordingHealthLevel.LOW_STORAGE -> requestSafetyStop(health.message)
                    stalledUsbChecks >= MAX_STALLED_USB_CHECKS ->
                        requestSafetyStop("USB audio stopped. Recording finalized safely.")
                }
            }
            monitorHandler.postDelayed(this, HEALTH_UPDATE_INTERVAL_MS)
        }
    }

    private val WAKE_LOCK_TIMEOUT_MS = TimeUnit.HOURS.toMillis(6)

    private val events get() = (application as DjmRecApplication).sessionEvents
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private var closeAfterSave = false
    /** The mixer went away while a recording was being saved; handle it once the save lands. */
    private var detachAfterSave = false

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            _state.collect { state ->
                com.audiopro.djmrec.diagnostics.RemoteDiagnostics.event("RecordingState", state.toString())
                if (state is RecordingState.Error)
                    com.audiopro.djmrec.diagnostics.RemoteDiagnostics.issue("Recording failure", state.toString())
                // Driven from the state itself rather than from the individual start/stop paths:
                // recording can end through a normal save, an error, a USB unplug or the service
                // being destroyed, and the phone must come off Do Not Disturb in every one of
                // them. The controller ignores repeat calls, so pause/resume costs nothing.
                if (state is RecordingState.Recording || state is RecordingState.Paused) {
                    doNotDisturb.engage(readSetting(KEY_DND_WHILE_RECORDING, true))
                } else {
                    doNotDisturb.release()
                }
            }
        }
        val settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        setRecordingGainDb(settings.getInt("recording_gain_db", 0))
        setSilenceHoldMs(settings.getLong("silence_hold_ms", SignalDetector.DEFAULT_HOLD_MS))
        createNotificationChannel()
        lifecycleScope.launch {
            var previous: String? = null
            (application as DjmRecApplication).usbAudioManager.deviceState.collect { device ->
                if (previous != null && device == null && _state.value !is RecordingState.Idle) handleDeviceDetached()
                previous = device?.deviceName
            }
        }
        monitorThread = HandlerThread("AudioMonitorThread", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
        monitorHandler = Handler(monitorThread.looper)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun setWaveformEnabled(enabled: Boolean) {
        waveformEnabled = enabled
        updateVisualWork()
        if (!enabled) _waveformBins.value = emptyWaveform
    }

    fun setVisualsVisible(ui: Boolean, waveform: Boolean) {
        uiVisible = ui
        waveformVisible = waveform
        updateVisualWork()
    }

    private fun updateVisualWork() {
        if (!::monitorHandler.isInitialized) return
        monitorHandler.post {
            AudioEngine.setWaveformEnabled(waveformEnabled && uiVisible && waveformVisible)
            monitorHandler.removeCallbacks(waveformRunnable)
            if (waveformEnabled && uiVisible && waveformVisible) monitorHandler.post(waveformRunnable)
        }
    }

    fun setRecordingGainDb(gainDb: Int) {
        AudioEngine.setRecordingGainDb(gainDb)
    }

    /** Applies the user's Silence hold preference; takes effect on the current gap immediately. */
    fun setSilenceHoldMs(holdMs: Long) {
        val sanitized = SignalDetector.sanitizeHoldMs(holdMs)
        onMonitorThread { signalDetector.holdMs = sanitized }
    }

    private fun resetSignalDetector() {
        onMonitorThread { signalDetector.reset() }
        _signalPresent.value = false
    }

    /** SignalDetector is not thread-safe and belongs to the monitor thread that updates it. */
    private fun onMonitorThread(block: () -> Unit) {
        if (::monitorHandler.isInitialized) monitorHandler.post(block) else block()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (_saving.value && intent?.action != ACTION_STOP_ALL) {
            discardUnusedIsoHandle(intent)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START || intent?.action == ACTION_MONITOR) {
            if (events.closeRequested.value) {
                discardUnusedIsoHandle(intent)
                return START_NOT_STICKY
            }
            if (_state.value is RecordingState.Idle || _state.value is RecordingState.Error) {
                isUsbIsoSession = intent.getIntExtra(EXTRA_CAPTURE_MODE, CAPTURE_MODE_AAUDIO) == CAPTURE_MODE_USB_ISO
            }
        }
        when (intent?.action) {
            ACTION_STOP_ALL -> stopAndClose()
            ACTION_MARK_TRACK -> synchronized(this) {
                if (_state.value is RecordingState.Recording) currentOutput?.let { output ->
                    runCatching {
                        events.markerCount.value = com.audiopro.djmrec.storage.TrackMarkerStore.add(
                            this, output.uri, AudioEngine.getElapsedMillis() - currentPartStartedElapsed)
                    }.onFailure { _health.value = RecordingHealth(RecordingHealthLevel.ERROR, "Could not save track marker; audio is still recording") }
                }
            }
            ACTION_MONITOR -> {
                if (_state.value is RecordingState.Monitoring ||
                    _state.value is RecordingState.Recording ||
                    _state.value is RecordingState.Paused ||
                    _state.value is RecordingState.Preparing) {
                    discardUnusedIsoHandle(intent)
                    return START_NOT_STICKY
                }
                pendingRecordingFormat = null
                _state.value = RecordingState.Preparing
                // Promote before native USB open/rate probing can block.
                if (!startForegroundNotification()) {
                    _state.value = RecordingState.Error("Android blocked the recording service -- open the app and try again")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48000)
                val bitDepth = intent.getIntExtra(EXTRA_BIT_DEPTH, 24)
                val captureMode = intent.getIntExtra(EXTRA_CAPTURE_MODE, CAPTURE_MODE_AAUDIO)
                if (captureMode == CAPTURE_MODE_USB_ISO) {
                    startUsbIsoSession(
                        fd = intent.getIntExtra(EXTRA_USB_FD, -1),
                        interfaceNumber = intent.getIntExtra(EXTRA_USB_INTERFACE, -1),
                        alternateSetting = intent.getIntExtra(EXTRA_USB_ALT_SETTING, -1),
                        endpointAddress = intent.getIntExtra(EXTRA_USB_ENDPOINT, -1),
                        maxPacketSize = intent.getIntExtra(EXTRA_USB_MAX_PACKET_SIZE, -1),
                        totalChannels = intent.getIntExtra(EXTRA_USB_TOTAL_CHANNELS, 2),
                        subframeSize = intent.getIntExtra(EXTRA_USB_SUBFRAME_SIZE, 4),
                        clockControlInterfaceNumber = intent.getIntExtra(EXTRA_USB_CLOCK_CONTROL_INTERFACE, -1),
                        clockSourceId = intent.getIntExtra(EXTRA_USB_CLOCK_SOURCE_ID, -1),
                        clockSupportsFrequencySet = intent.getBooleanExtra(EXTRA_USB_CLOCK_FREQUENCY_SETTABLE, false),
                        feedbackEndpointAddress = intent.getIntExtra(EXTRA_USB_FEEDBACK_ENDPOINT, -1),
                        feedbackMaxPacketSize = intent.getIntExtra(EXTRA_USB_FEEDBACK_MAX_PACKET_SIZE, -1),
                        vendorId = intent.getIntExtra(EXTRA_USB_VENDOR_ID, -1),
                        productId = intent.getIntExtra(EXTRA_USB_PRODUCT_ID, -1),
                        rawDescriptors = intent.getByteArrayExtra(EXTRA_USB_RAW_DESCRIPTORS) ?: byteArrayOf(),
                        bitDepth = bitDepth,
                        channelOffset = intent.getIntExtra(EXTRA_USB_CHANNEL_OFFSET, 0),
                        sampleRateHint = sampleRate,
                        includeMic = intent.getBooleanExtra(EXTRA_USB_INCLUDE_MIC, true),
                        playbackOverride = intent.getIntExtra(EXTRA_USB_PLAYBACK_OVERRIDE, -1),
                        endpointRateOverride = intent.getIntExtra(EXTRA_USB_ENDPOINT_RATE_OVERRIDE, -1),
                        allowFormatMismatch = intent.getBooleanExtra(EXTRA_USB_ALLOW_FORMAT_MISMATCH, false),
                        monitorOnly = true
                    )
                } else {
                    val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
                    val channelCount = intent.getIntExtra(EXTRA_CHANNEL_COUNT, 2)
                    startSession(deviceId, sampleRate, channelCount, bitDepth, monitorOnly = true)
                }
            }

            ACTION_START -> {
                // If already monitoring, just begin encoding.
                if (_state.value is RecordingState.Monitoring) {
                    currentFormat = recordingFormatFrom(intent)
                    beginRecordingNow()
                    return START_NOT_STICKY
                }
                // A record press while automatic monitoring is opening is queued. Opening a
                // second UsbDeviceConnection here would invalidate the first raw USB stream.
                if (_state.value is RecordingState.Preparing) {
                    pendingRecordingFormat = recordingFormatFrom(intent)
                    discardUnusedIsoHandle(intent)
                    return START_NOT_STICKY
                }
                if (_state.value is RecordingState.Recording ||
                    _state.value is RecordingState.Paused) {
                    discardUnusedIsoHandle(intent)
                    return START_NOT_STICKY
                }
                _state.value = RecordingState.Preparing
                if (!startForegroundNotification()) {
                    _state.value = RecordingState.Error("Android blocked the recording service -- open the app and try again")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Otherwise, open stream + encode immediately (full recording from idle).
                val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48000)
                val bitDepth = intent.getIntExtra(EXTRA_BIT_DEPTH, 24)
                val format = recordingFormatFrom(intent)
                val captureMode = intent.getIntExtra(EXTRA_CAPTURE_MODE, CAPTURE_MODE_AAUDIO)

                if (captureMode == CAPTURE_MODE_USB_ISO) {
                    startUsbIsoSession(
                        fd = intent.getIntExtra(EXTRA_USB_FD, -1),
                        interfaceNumber = intent.getIntExtra(EXTRA_USB_INTERFACE, -1),
                        alternateSetting = intent.getIntExtra(EXTRA_USB_ALT_SETTING, -1),
                        endpointAddress = intent.getIntExtra(EXTRA_USB_ENDPOINT, -1),
                        maxPacketSize = intent.getIntExtra(EXTRA_USB_MAX_PACKET_SIZE, -1),
                        totalChannels = intent.getIntExtra(EXTRA_USB_TOTAL_CHANNELS, 2),
                        subframeSize = intent.getIntExtra(EXTRA_USB_SUBFRAME_SIZE, 4),
                        clockControlInterfaceNumber = intent.getIntExtra(EXTRA_USB_CLOCK_CONTROL_INTERFACE, -1),
                        clockSourceId = intent.getIntExtra(EXTRA_USB_CLOCK_SOURCE_ID, -1),
                        clockSupportsFrequencySet = intent.getBooleanExtra(EXTRA_USB_CLOCK_FREQUENCY_SETTABLE, false),
                        feedbackEndpointAddress = intent.getIntExtra(EXTRA_USB_FEEDBACK_ENDPOINT, -1),
                        feedbackMaxPacketSize = intent.getIntExtra(EXTRA_USB_FEEDBACK_MAX_PACKET_SIZE, -1),
                        vendorId = intent.getIntExtra(EXTRA_USB_VENDOR_ID, -1),
                        productId = intent.getIntExtra(EXTRA_USB_PRODUCT_ID, -1),
                        rawDescriptors = intent.getByteArrayExtra(EXTRA_USB_RAW_DESCRIPTORS) ?: byteArrayOf(),
                        bitDepth = bitDepth,
                        channelOffset = intent.getIntExtra(EXTRA_USB_CHANNEL_OFFSET, 0),
                        sampleRateHint = sampleRate,
                        includeMic = intent.getBooleanExtra(EXTRA_USB_INCLUDE_MIC, true),
                        playbackOverride = intent.getIntExtra(EXTRA_USB_PLAYBACK_OVERRIDE, -1),
                        endpointRateOverride = intent.getIntExtra(EXTRA_USB_ENDPOINT_RATE_OVERRIDE, -1),
                        allowFormatMismatch = intent.getBooleanExtra(EXTRA_USB_ALLOW_FORMAT_MISMATCH, false),
                        format = format,
                        monitorOnly = false
                    )
                } else {
                    val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
                    val channelCount = intent.getIntExtra(EXTRA_CHANNEL_COUNT, 2)
                    startSession(deviceId, sampleRate, channelCount, bitDepth, format, monitorOnly = false)
                }
            }

            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_STOP -> stopSession()
        }
        // Deliberately not sticky: if the process is killed mid-recording we do not want to
        // silently resume capturing without the user re-confirming — safer default for a
        // professional recording tool than risking a corrupt/incomplete file being extended.
        return START_NOT_STICKY
    }

    /**
     * An Intent that arrived with a freshly opened USB connection but is being dropped (service
     * busy, saving, closing) must release that connection, otherwise it leaks and the next
     * interface claim fails with BUSY. Only safe while no native session holds the fd.
     */
    private fun discardUnusedIsoHandle(intent: Intent?) {
        if (intent?.hasExtra(EXTRA_USB_FD) == true && !AudioEngine.isStreamOpen()) {
            (application as DjmRecApplication).usbAudioManager.releaseIsoCaptureConnection()
        }
    }

    private fun recordingFormatFrom(intent: Intent): RecordingFormat {
        val value = intent.getIntExtra(EXTRA_FORMAT, currentFormat.nativeValue)
        return RecordingFormat.entries.firstOrNull { it.nativeValue == value } ?: RecordingFormat.WAV
    }

    fun startSession(
        audioManagerDeviceId: Int,
        sampleRateHint: Int,
        channelCount: Int,
        bitDepth: Int,
        format: RecordingFormat = RecordingFormat.WAV,
        monitorOnly: Boolean = false
    ) {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Monitoring) return
        _state.value = RecordingState.Preparing
        isUsbIsoSession = false
        isMonitoringOnly = monitorOnly
        currentBitDepth = bitDepth
        currentOutputChannels = 2

        val negotiatedRate =
            if (com.audiopro.djmrec.usb.DemoMixer.enabled &&
                audioManagerDeviceId == com.audiopro.djmrec.usb.DemoMixer.AUDIO_DEVICE_ID) {
                AudioEngine.openDemo(sampleRateHint, bitDepth)
            } else {
                AudioEngine.open(audioManagerDeviceId, sampleRateHint, channelCount, bitDepth)
            }
        if (negotiatedRate <= 0) {
            failPreparation("Failed to open exclusive audio stream")
            return
        }
        updateRecordingFormat(negotiatedRate, bitDepth)

        if (monitorOnly) {
            beginMonitoring()
        } else {
            beginEncodingOrFail(bitDepth, format)
        }
    }

    fun startUsbIsoSession(
        fd: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        totalChannels: Int,
        subframeSize: Int,
        clockControlInterfaceNumber: Int,
        clockSourceId: Int,
        clockSupportsFrequencySet: Boolean,
        feedbackEndpointAddress: Int,
        feedbackMaxPacketSize: Int,
        vendorId: Int,
        productId: Int,
        rawDescriptors: ByteArray,
        bitDepth: Int,
        channelOffset: Int,
        sampleRateHint: Int,
        includeMic: Boolean = true,
        playbackOverride: Int = -1,
        endpointRateOverride: Int = -1,
        allowFormatMismatch: Boolean = false,
        format: RecordingFormat = RecordingFormat.WAV,
        monitorOnly: Boolean = false
    ) {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Monitoring) return
        _state.value = RecordingState.Preparing
        isUsbIsoSession = true
        currentIncludeMic = includeMic
        isMonitoringOnly = monitorOnly
        currentBitDepth = bitDepth
        currentOutputChannels = 2

        if (fd < 0 || interfaceNumber < 0 || endpointAddress < 0 || maxPacketSize <= 0) {
            failPreparation("Invalid USB capture parameters")
            releaseIsoConnectionIfNeeded()
            return
        }
        val negotiatedRate = AudioEngine.openUsbIso(
            fd, interfaceNumber, alternateSetting, endpointAddress, maxPacketSize,
            totalChannels, subframeSize, bitDepth, channelOffset,
            clockControlInterfaceNumber, clockSourceId, clockSupportsFrequencySet,
            feedbackEndpointAddress, feedbackMaxPacketSize, vendorId, productId,
            rawDescriptors, sampleRateHint, includeMic,
            playbackOverride, endpointRateOverride, allowFormatMismatch
        )
        if (negotiatedRate <= 0) {
            com.audiopro.djmrec.diagnostics.RemoteDiagnostics.health(
                "ERROR: Failed to open USB isochronous capture", AudioEngine.getDiagnosticSummary()
            )
            failPreparation("Failed to open USB isochronous capture")
            releaseIsoConnectionIfNeeded()
            return
        }
        updateRecordingFormat(negotiatedRate, bitDepth)

        if (monitorOnly) {
            beginMonitoring()
        } else {
            // Recording a quiet intro is valid. Monitoring/health report silence separately.
            beginEncodingOrFail(bitDepth, format)
        }
    }

    /** Transitions from Monitoring to Recording: starts the encoder writing to a file. */
    fun beginRecordingNow() {
        if (_state.value !is RecordingState.Monitoring) return
        isMonitoringOnly = false
        beginEncodingOrFail(currentBitDepth, currentFormat)
    }

    /** Shared setup after stream open for monitoring: starts metering without file output. */
    private fun beginMonitoring() {
        val queuedFormat = pendingRecordingFormat
        if (queuedFormat != null) {
            pendingRecordingFormat = null
            isMonitoringOnly = false
            beginEncodingOrFail(currentBitDepth, queuedFormat)
            return
        }
        acquireWakeLock()
        if (!startForegroundNotification()) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            failPreparation("Android blocked the recording service -- open the app and try again")
            stopSelf()
            return
        }
        _state.value = RecordingState.Monitoring
        _health.value = RecordingHealth(
            RecordingHealthLevel.GOOD,
            "USB signal ready",
            RecordingOutputManager.freeBytes(),
            Long.MAX_VALUE
        )
        startPolling()
    }

    /** Shared tail of both [startSession] and [startUsbIsoSession] once the native capture
     *  source is open: creates the output file, starts the encoder, and flips to Recording. */
    private fun beginEncodingOrFail(bitDepth: Int, format: RecordingFormat) {
        val freeBytes = RecordingOutputManager.freeBytes()
        val requiredBytes = RecordingStoragePolicy.requiredStartBytes(bytesPerSecond)
        if (freeBytes < 0) Log.w(TAG, "Free storage could not be measured; recording without a low-space guard")
        if (freeBytes >= 0 && freeBytes < requiredBytes) {
            failEncoding("Not enough free storage. At least 256 MB is required.")
            return
        }

        val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        events.markerCount.value = 0
        events.lastSaved.value = null
        val output = RecordingOutputManager.create(this, sessionId, format, 1)
        if (output == null) {
            failEncoding("Failed to create recording in Music/DJMRec")
            return
        }

        val journalStarted = runCatching {
            RecordingSessionStore.begin(
                this,
                sessionId,
                format,
                currentSampleRate,
                bitDepth,
                deviceLabel,
                output.toRecord()
            )
        }.isSuccess
        if (!journalStarted) {
            RecordingOutputManager.abandon(this, output)
            failEncoding("Failed to create crash-recovery journal")
            return
        }
        currentFormat = format
        val started = AudioEngine.startRecordingFd(output.descriptor.fd, format.nativeValue)
        runCatching { output.descriptor.close() }
        if (!started) {
            RecordingOutputManager.abandon(this, output)
            RecordingSessionStore.complete(this)
            failEncoding("Failed to start ${format.name} encoder")
            return
        }

        currentOutput = output
        currentSessionId = sessionId
        currentPartIndex = 1
        currentPartStartedElapsed = 0L
        lastCheckpointRealtime = 0L
        safetyStopPending = false

        acquireWakeLock()
        if (!startForegroundNotification()) {
            AudioEngine.stopRecording()
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            RecordingOutputManager.abandon(this, output)
            RecordingSessionStore.complete(this)
            currentOutput = null
            currentSessionId = null
            failPreparation("Android blocked the recording service -- open the app and try again")
            stopSelf()
            return
        }
        _state.value = RecordingState.Recording
        _health.value = RecordingHealth(
            RecordingHealthLevel.GOOD,
            "Recording healthy",
            freeBytes,
            RecordingStoragePolicy.remainingSeconds(freeBytes, bytesPerSecond)
        )
        startPolling()
    }

    private fun failEncoding(message: String) {
        AudioEngine.close()
        releaseIsoConnectionIfNeeded()
        currentOutput = null
        currentSessionId = null
        failPreparation(message)
    }

    private fun updateRecordingFormat(sampleRate: Int, bitDepth: Int) {
        currentSampleRate = sampleRate
        (application as DjmRecApplication).usbAudioManager.updateNegotiatedSampleRate(sampleRate)
        currentBitDepth = bitDepth
        bytesPerSecond = RecordingStoragePolicy.worstCaseBytesPerSecond(
            sampleRate,
            currentOutputChannels,
            bitDepth
        )
    }

    private fun startPolling() {
        resetHealthTracking()
        monitorHandler.removeCallbacks(meterRunnable)
        monitorHandler.removeCallbacks(waveformRunnable)
        monitorHandler.removeCallbacks(notificationRunnable)
        monitorHandler.removeCallbacks(healthRunnable)
        monitorHandler.post(meterRunnable)
        monitorHandler.post(waveformRunnable)
        monitorHandler.post(notificationRunnable)
        monitorHandler.post(healthRunnable)
    }

    private fun resetHealthTracking() {
        lastUsbStats = LongArray(7)
        usbHealthInitialized = false
        stalledUsbChecks = 0
        lastXRunCount = 0
        safetyStopPending = false
    }

    @Synchronized
    private fun checkpointIfDue() {
        if (_saving.value) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckpointRealtime < CHECKPOINT_INTERVAL_MS) return
        lastCheckpointRealtime = now
        val partBytes = AudioEngine.checkpointRecording()
        if (partBytes < 0) {
            requestSafetyStop("Could not checkpoint recording. File finalized at last safe point.")
            return
        }
        val journalSaved = runCatching {
            RecordingSessionStore.checkpoint(this, AudioEngine.getElapsedMillis())
        }.isSuccess
        if (!journalSaved) {
            requestSafetyStop("Could not save recovery checkpoint. Recording finalized safely.")
            return
        }
        if (currentFormat == RecordingFormat.WAV && RecordingStoragePolicy.shouldRollWav(partBytes)) {
            rollWavPart()
        }
    }

    private fun rollWavPart() {
        val sessionId = currentSessionId ?: return
        val previous = currentOutput ?: return
        val nextIndex = currentPartIndex + 1
        val next = RecordingOutputManager.create(this, sessionId, RecordingFormat.WAV, nextIndex)
        if (next == null) {
            requestSafetyStop("Could not create next WAV part. Recording finalized safely.")
            return
        }
        val rolled = AudioEngine.rollRecordingFd(next.descriptor.fd, RecordingFormat.WAV.nativeValue)
        runCatching { next.descriptor.close() }
        if (!rolled) {
            RecordingOutputManager.abandon(this, next)
            requestSafetyStop("Could not continue WAV recording. Current part finalized safely.")
            return
        }

        val elapsed = AudioEngine.getElapsedMillis()
        val partJournaled = runCatching { RecordingSessionStore.addPart(this, next.toRecord()) }.isSuccess
        val previousFinalized = RecordingOutputManager.finalize(
            this,
            previous,
            elapsed - currentPartStartedElapsed
        )
        if (previousFinalized) RecordingSessionStore.markFinalized(this, previous.uri)
        currentOutput = next
        currentPartIndex = nextIndex
        currentPartStartedElapsed = elapsed
        events.markerCount.value = 0
        if (!partJournaled) {
            requestSafetyStop("Could not journal next WAV part. Recording stopped safely.")
        } else if (!previousFinalized) {
            requestSafetyStop("Previous WAV part could not be published. Recording stopped safely.")
        }
    }

    private fun requestSafetyStop(message: String) {
        if (safetyStopPending || _saving.value) return
        safetyStopPending = true
        mainHandler.post {
            if (!_saving.value && (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused)) {
                stopSessionWithError(message)
            } else {
                safetyStopPending = false
            }
        }
    }

    /** Closes the [UsbAudioManager] connection backing native libusb capture, if this session used it. */
    private fun releaseIsoConnectionIfNeeded() {
        if (isUsbIsoSession) {
            (application as DjmRecApplication).usbAudioManager.releaseIsoCaptureConnection()
        }
    }

    private fun failPreparation(message: String) {
        pendingRecordingFormat = null
        _state.value = RecordingState.Error(message)
        _health.value = RecordingHealth(RecordingHealthLevel.ERROR, message)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    fun pauseSession() {
        if (_state.value !is RecordingState.Recording) return
        AudioEngine.pauseRecording()
        _state.value = RecordingState.Paused
        updateNotification()
    }

    fun resumeSession() {
        if (_state.value !is RecordingState.Paused) return
        AudioEngine.resumeRecording()
        _state.value = RecordingState.Recording
        updateNotification()
    }

    fun stopSession() {
        if (_saving.value) return
        pendingRecordingFormat = null
        if (_state.value is RecordingState.Preparing || _state.value is RecordingState.Error) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            _state.value = RecordingState.Idle
            _health.value = RecordingHealth.Ready
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (_state.value is RecordingState.Idle || _state.value is RecordingState.Monitoring) {
            // Full stop from monitoring: close the stream.
            if (_state.value is RecordingState.Monitoring) {
                AudioEngine.close()
                releaseIsoConnectionIfNeeded()
                releaseWakeLock()
                _state.value = RecordingState.Idle
                _elapsedMillis.value = 0L
                _levels.value = StereoLevels(floorLevel, floorLevel)
                resetSignalDetector()
                _waveformBins.value = emptyWaveform
                _health.value = RecordingHealth.Ready
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        _saving.value = true
        updateNotification()
        lifecycleScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                synchronized(this@RecordingService) { runCatching {
                    val savedOutput = currentOutput
                    val partStart = currentPartStartedElapsed
                    val duration = AudioEngine.stopRecording()
                    val finalized = finalizeCurrentOutput(duration)
                    val complete = finalized && RecordingSessionStore.completeIfFinalized(this@RecordingService)
                    Triple((duration - partStart).coerceAtLeast(0L), complete, savedOutput)
                } }
            }
            currentSessionId = null
            currentPartIndex = 0
            val (duration, complete, savedOutput) = result.getOrDefault(Triple(0L, false, null))
            if (!complete) {
                stopSessionWithError("Recording stopped; publication failed. Recovery will retry on next launch.", alreadyStopped = true)
            } else {
                savedOutput?.let { events.lastSaved.value = com.audiopro.djmrec.audio.SavedRecording(it.uri, it.displayName, duration) }
                _state.value = RecordingState.Monitoring
                isMonitoringOnly = true
                _elapsedMillis.value = 0L
                _health.value = RecordingHealth(RecordingHealthLevel.GOOD, "Saved to Music/DJMRec", RecordingOutputManager.freeBytes(), Long.MAX_VALUE)
                safetyStopPending = false
            }
            _saving.value = false
            if (complete) updateNotification()
            if (closeAfterSave) {
                closeCaptureAndTask()
            } else if (detachAfterSave) {
                detachAfterSave = false
                handleDeviceDetached()
            }
        }
    }

    private fun stopAndClose() {
        closeAfterSave = true
        events.closeRequested.value = true
        if (_saving.value) return
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused) stopSession()
        else closeCaptureAndTask()
    }

    private fun closeCaptureAndTask() {
        closeAfterSave = false
        detachAfterSave = false
        AudioEngine.close()
        releaseIsoConnectionIfNeeded()
        releaseWakeLock()
        monitorHandler.removeCallbacksAndMessages(null)
        _state.value = RecordingState.Idle
        _levels.value = StereoLevels(floorLevel, floorLevel)
        resetSignalDetector()
        _waveformBins.value = emptyWaveform
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).appTasks.forEach { it.finishAndRemoveTask() }
        stopSelf()
    }

    private fun finalizeCurrentOutput(totalDurationMillis: Long): Boolean {
        val output = currentOutput ?: return true
        val partDuration = (totalDurationMillis - currentPartStartedElapsed).coerceAtLeast(0)
        val finalized = RecordingOutputManager.finalize(this, output, partDuration)
        if (finalized) runCatching { RecordingSessionStore.markFinalized(this, output.uri) }
        currentOutput = null
        return finalized
    }

    @Synchronized
    private fun stopSessionWithError(message: String, alreadyStopped: Boolean = false) {
        val duration = if (alreadyStopped) AudioEngine.getElapsedMillis() else AudioEngine.stopRecording()
        val finalized = finalizeCurrentOutput(duration)
        if (finalized) RecordingSessionStore.completeIfFinalized(this)
        AudioEngine.close()
        releaseIsoConnectionIfNeeded()
        releaseWakeLock()
        currentSessionId = null
        currentPartIndex = 0
        isMonitoringOnly = false
        _state.value = RecordingState.Error(message)
        _health.value = RecordingHealth(
            RecordingHealthLevel.ERROR,
            message,
            RecordingOutputManager.freeBytes(),
            0
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        safetyStopPending = false
    }

    private fun handleDeviceDetached() {
        // Not closeAfterSave: that closes the whole app. An unplug during a save should end in the
        // normal "mixer disconnected" state once the file is safe.
        if (_saving.value) { detachAfterSave = true; return }
        pendingRecordingFormat = null
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused) {
            stopSessionWithError("USB mixer disconnected. Recording finalized safely.")
            return
        }
        AudioEngine.close()
        releaseIsoConnectionIfNeeded()
        releaseWakeLock()
        _state.value = RecordingState.Error("USB mixer disconnected")
        _health.value = RecordingHealth(RecordingHealthLevel.ERROR, "USB mixer disconnected")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    fun setDeviceLabel(label: String) {
        deviceLabel = label
    }

    @Synchronized
    override fun onDestroy() {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused) {
            val duration = AudioEngine.stopRecording()
            if (finalizeCurrentOutput(duration)) RecordingSessionStore.completeIfFinalized(this)
        }
        if (_state.value !is RecordingState.Idle) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
        }
        releaseWakeLock()
        // The state collector is already cancelled by the time we get here, so the phone would
        // otherwise stay silenced after the service goes away.
        doNotDisturb.release()
        monitorHandler.removeCallbacksAndMessages(null)
        monitorThread.quitSafely()
        super.onDestroy()
    }

    // --- WakeLock -----------------------------------------------------------------------

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = wakeLock ?: powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "djmrec:recording"
        ).apply { setReferenceCounted(false) }.also { wakeLock = it }
        // Non-reference-counted: calling acquire(timeout) on an already-held lock simply pushes
        // the safety timeout out again. healthRunnable calls this every tick for the life of the
        // session, so a 6 h ceiling can never expire underneath a long set as long as the
        // service is alive; if the process dies the lock dies with it.
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // --- Notification --------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, android.app.NotificationManager.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_name))
            .setDescription(getString(R.string.notification_channel_desc))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    /**
     * Returns false instead of crashing when the OS refuses foreground promotion. On Android 14+
     * a microphone-type foreground service also needs the app to have been interacted with
     * recently, which a device-attach auto-start can miss; the refusal is a SecurityException
     * from `startForeground()` itself, after the caller's start already succeeded.
     */
    private fun startForegroundNotification(): Boolean {
        val notification = buildNotification()
        // minSdk is 29 (Q), so the ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE overload is
        // always available — no legacy startForeground(id, notification) fallback needed.
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isUsbIsoSession) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground refused by the OS: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startForeground refused by the OS: ${e.message}")
            false
        }
    }

    private fun updateNotification() {
        if (events.closeRequested.value && !_saving.value) return
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val isPaused = _state.value is RecordingState.Paused
        val isRecording = _state.value is RecordingState.Recording || isPaused
        val elapsed = formatElapsed(_elapsedMillis.value)
        val hasSignal = _signalPresent.value

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleAction = if (isPaused) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, getString(R.string.action_resume),
                servicePendingIntent(ACTION_RESUME)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, getString(R.string.action_pause),
                servicePendingIntent(ACTION_PAUSE)
            )
        }
        val title = when {
            _saving.value -> "Saving your set..."
            isPaused -> getString(R.string.notification_title_paused)
            isRecording -> getString(R.string.notification_title_recording, deviceLabel)
            else -> "$deviceLabel connected"
        }
        val text = when {
            isRecording -> getString(R.string.notification_text_elapsed, elapsed) +
                if (hasSignal && !isPaused) " | signal" else ""
            else -> if (hasSignal) "USB signal ready" else "Waiting for mixer signal"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (isRecording) {
            builder.addAction(toggleAction)
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Save & close",
                    servicePendingIntent(ACTION_STOP_ALL)
                )
            )
        }
        if (!isRecording) {
            builder.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop & close",
                    servicePendingIntent(ACTION_STOP_ALL)
                )
            )
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatElapsed(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}

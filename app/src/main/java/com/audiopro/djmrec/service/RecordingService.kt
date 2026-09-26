package com.audiopro.djmrec.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.audiopro.djmrec.DjmRecApplication
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.audio.ChannelLevel
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.audio.RecordingHealth
import com.audiopro.djmrec.audio.RecordingHealthLevel
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.audio.SignalDetector
import com.audiopro.djmrec.audio.StereoLevels
import com.audiopro.djmrec.domain.CaptureSessionParams
import com.audiopro.djmrec.domain.CaptureSource
import com.audiopro.djmrec.domain.HealthSample
import com.audiopro.djmrec.domain.HealthSupervisor
import com.audiopro.djmrec.storage.RecordingOutputManager
import com.audiopro.djmrec.storage.RecordingStoragePolicy
import com.audiopro.djmrec.storage.RecordingWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service hosting the entire recording session so the OS cannot kill the process
 * mid-capture. Exposes a [LocalBinder] for the UI's ViewModel to observe state directly, and
 * also reacts to notification action buttons (Pause/Resume/Stop) via `onStartCommand`.
 *
 * It orchestrates; the work is done by [RecordingWriter] (files and journal),
 * [RecordingNotifications], [WakeLockHolder], [HealthSupervisor] and [DoNotDisturbController].
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

        /** Id of a [CaptureSessionParams] offered to [CaptureSessionHandoff]; START and MONITOR only. */
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_FORMAT = "extra_format"
        private const val DEFAULT_DEVICE_LABEL = "USB Mixer"

        private const val TAG = "RecordingService"
        private const val METER_UPDATE_INTERVAL_MS = 66L // ~15 fps, plenty for a VU meter
        private const val WAVEFORM_UPDATE_INTERVAL_MS = 33L // ~30 snapshots/s; UI scrolls at up to 60 fps
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L
        private const val HEALTH_UPDATE_INTERVAL_MS = 2_000L
        private const val BLOCKED_MESSAGE = "Android blocked the recording service -- open the app and try again"
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

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val writer by lazy { RecordingWriter(this) }
    private val notifications by lazy { RecordingNotifications(this) }
    private val wakeLock by lazy { WakeLockHolder(getSystemService(Context.POWER_SERVICE) as PowerManager) }
    /** Owned by the monitor thread, like [signalDetector]. */
    private val healthSupervisor = HealthSupervisor()

    /** Silences calls and notifications for the length of a set; see DoNotDisturbController. */
    private val doNotDisturb by lazy { DoNotDisturbController(this) }

    /** Keeps the phone out of deep sleep while raw USB capture runs; see UsbIdleGuard. */
    private val idleGuard by lazy { UsbIdleGuard(this) }

    private val events get() = (application as DjmRecApplication).sessionEvents

    /**
     * Read at the moment it is needed rather than cached at startup, so toggling the setting
     * applies to the very next recording without any plumbing between the UI and the service.
     * SharedPreferences is an in-memory map after the first load, so this is not file I/O.
     */
    private fun readSetting(key: String, fallback: Boolean): Boolean =
        getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(key, fallback)

    // Dedicated thread for pulling meter/elapsed data off the native engine, refreshing the
    // notification and running the health checks, so none of it is starved by UI work.
    private lateinit var monitorThread: HandlerThread
    private lateinit var monitorHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentFormat: RecordingFormat = RecordingFormat.WAV
    private var currentBitDepth: Int = 24
    private var pendingRecordingFormat: RecordingFormat? = null
    private var currentSampleRate = 48_000
    private var currentOutputChannels = 2
    private var bytesPerSecond = RecordingStoragePolicy.worstCaseBytesPerSecond(48_000, 2, 24)
    /**
     * The session being (or last) captured. Written on the main thread, read by the monitor
     * thread for the notification and health checks. Kept after the session ends so the release
     * paths still know it was USB; the next start replaces it.
     */
    @Volatile private var session: CaptureSessionParams? = null
    private val deviceLabel: String get() = session?.deviceLabel ?: DEFAULT_DEVICE_LABEL
    private val isUsbIsoSession: Boolean get() = session?.isUsbIso == true
    /** True when the audio stream is open for monitoring but no file is being written. */
    private var isMonitoringOnly = false
    @Volatile
    private var waveformEnabled = true
    @Volatile private var uiVisible = false
    @Volatile private var waveformVisible = false
    @Volatile
    private var safetyStopPending = false
    private var closeAfterSave = false
    /** The mixer went away while a recording was being saved; handle it once the save lands. */
    private var detachAfterSave = false

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
            // Renews the wake lock's safety timeout every tick; see WakeLockHolder.renew().
            wakeLock.renew()
            if (!captureActive()) {
                monitorHandler.postDelayed(this, HEALTH_UPDATE_INTERVAL_MS)
                return
            }

            // Native asked for the "route every MIX pair" fallback after a silent first window.
            // It must run here (Java UsbDeviceConnection path), never on the libusb event thread.
            val usbSource = session?.source as? CaptureSource.UsbIso
            if (usbSource != null && AudioEngine.takeRouteFallbackRequest()) {
                (application as DjmRecApplication).usbAudioManager.applyRouteFallback(usbSource.includeMic)
            }

            val recording = _state.value is RecordingState.Recording || _state.value is RecordingState.Paused
            val freeBytes = RecordingOutputManager.freeBytes()
            val remaining = if (freeBytes < 0) Long.MAX_VALUE
            else RecordingStoragePolicy.remainingSeconds(freeBytes, bytesPerSecond)
            val verdict = healthSupervisor.evaluate(
                HealthSample(
                    recording = recording,
                    usbIso = isUsbIsoSession,
                    streamOpen = AudioEngine.isStreamOpen(),
                    freeBytes = freeBytes,
                    remainingSeconds = remaining,
                    usbStats = AudioEngine.getUsbIsoTransferStats(),
                    xRunCount = AudioEngine.getXRunCount(),
                    writerErrorCode = AudioEngine.getRecordingErrorCode(),
                    signalPresent = _signalPresent.value
                )
            )
            _health.value = verdict.health

            if (recording) {
                checkpointIfDue()
                verdict.safetyStopReason?.let { requestSafetyStop(it) }
            }
            monitorHandler.postDelayed(this, HEALTH_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            _state.collect { state ->
                Log.i(TAG, "Recording state: $state")
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
        notifications.createChannel()
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
        // Taken up front so every early return below can release what it carries.
        val offered = intent?.getLongExtra(EXTRA_SESSION_ID, CaptureSessionHandoff.NO_ID)
            ?.takeIf { it != CaptureSessionHandoff.NO_ID }
            ?.let { (application as DjmRecApplication).captureSessionHandoff.take(it) }
        if (_saving.value && intent?.action != ACTION_STOP_ALL) {
            discardUnusedIsoHandle(offered)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START || intent?.action == ACTION_MONITOR) {
            if (events.closeRequested.value) {
                discardUnusedIsoHandle(offered)
                return START_NOT_STICKY
            }
            // Before foreground promotion, which picks the service type from the session.
            if (_state.value is RecordingState.Idle || _state.value is RecordingState.Error) {
                session = offered
            }
        }
        when (intent?.action) {
            ACTION_STOP_ALL -> stopAndClose()
            ACTION_MARK_TRACK -> if (_state.value is RecordingState.Recording) {
                runCatching { writer.addMarker() }
                    .onSuccess { count -> if (count != null) events.markerCount.value = count }
                    .onFailure { _health.value = RecordingHealth(RecordingHealthLevel.ERROR, "Could not save track marker; audio is still recording") }
            }
            ACTION_MONITOR -> {
                if (_state.value is RecordingState.Monitoring ||
                    _state.value is RecordingState.Recording ||
                    _state.value is RecordingState.Paused ||
                    _state.value is RecordingState.Preparing) {
                    discardUnusedIsoHandle(offered)
                    return START_NOT_STICKY
                }
                pendingRecordingFormat = null
                _state.value = RecordingState.Preparing
                // Promote before native USB open/rate probing can block.
                if (!startForegroundNotification()) {
                    discardUnusedIsoHandle(offered)
                    _state.value = RecordingState.Error(BLOCKED_MESSAGE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCapture(offered, currentFormat, monitorOnly = true)
            }

            ACTION_START -> {
                // If already monitoring, just begin encoding.
                if (_state.value is RecordingState.Monitoring) {
                    discardUnusedIsoHandle(offered)
                    currentFormat = recordingFormatFrom(intent)
                    beginRecordingNow()
                    return START_NOT_STICKY
                }
                // A record press while automatic monitoring is opening is queued. Opening a
                // second UsbDeviceConnection here would invalidate the first raw USB stream.
                if (_state.value is RecordingState.Preparing) {
                    pendingRecordingFormat = recordingFormatFrom(intent)
                    discardUnusedIsoHandle(offered)
                    return START_NOT_STICKY
                }
                if (_state.value is RecordingState.Recording ||
                    _state.value is RecordingState.Paused) {
                    discardUnusedIsoHandle(offered)
                    return START_NOT_STICKY
                }
                _state.value = RecordingState.Preparing
                if (!startForegroundNotification()) {
                    discardUnusedIsoHandle(offered)
                    _state.value = RecordingState.Error(BLOCKED_MESSAGE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Otherwise, open stream + encode immediately (full recording from idle).
                startCapture(offered, recordingFormatFrom(intent), monitorOnly = false)
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
    private fun discardUnusedIsoHandle(offered: CaptureSessionParams?) {
        if (offered?.isUsbIso == true && !AudioEngine.isStreamOpen()) {
            (application as DjmRecApplication).usbAudioManager.releaseIsoCaptureConnection()
        }
    }

    private fun recordingFormatFrom(intent: Intent): RecordingFormat {
        val value = intent.getIntExtra(EXTRA_FORMAT, currentFormat.nativeValue)
        return RecordingFormat.entries.firstOrNull { it.nativeValue == value } ?: RecordingFormat.WAV
    }

    /**
     * Opens the source [params] describes, then starts monitoring or encoding. The caller has
     * already promoted the service to the foreground; null [params] means the start Intent
     * arrived without a session (nothing was offered, or it was already taken).
     */
    private fun startCapture(params: CaptureSessionParams?, format: RecordingFormat, monitorOnly: Boolean) {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Monitoring) return
        if (params == null) {
            failPreparation("No mixer to open -- reconnect the mixer and try again")
            return
        }
        _state.value = RecordingState.Preparing
        session = params
        isMonitoringOnly = monitorOnly
        currentBitDepth = params.bitDepth
        currentOutputChannels = 2

        params.invalidReason()?.let { reason ->
            failPreparation(reason)
            releaseIsoConnectionIfNeeded()
            return
        }
        val negotiatedRate = when (val source = params.source) {
            is CaptureSource.UsbIso -> AudioEngine.openUsbIso(source, params.sampleRateHint)
            is CaptureSource.AudioStack ->
                AudioEngine.open(source.deviceId, params.sampleRateHint, source.channelCount, params.bitDepth)
            CaptureSource.Demo ->
                if (com.audiopro.djmrec.usb.DemoMixer.enabled) AudioEngine.openDemo(params.sampleRateHint, params.bitDepth) else -1
        }
        if (negotiatedRate <= 0) {
            if (params.isUsbIso) {
                failPreparation("Failed to open USB isochronous capture")
                releaseIsoConnectionIfNeeded()
            } else {
                failPreparation("Failed to open exclusive audio stream")
            }
            return
        }
        updateRecordingFormat(negotiatedRate, params.bitDepth)
        if (params.isUsbIso) idleGuard.start()

        if (monitorOnly) {
            beginMonitoring()
        } else {
            // Recording a quiet intro is valid. Monitoring/health report silence separately.
            beginEncodingOrFail(params.bitDepth, format)
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
        wakeLock.renew()
        if (!startForegroundNotification()) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            failPreparation(BLOCKED_MESSAGE)
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

    /** Shared tail of [startCapture] and [beginRecordingNow] once the native capture
     *  source is open: creates the output file, starts the encoder, and flips to Recording. */
    private fun beginEncodingOrFail(bitDepth: Int, format: RecordingFormat) {
        val freeBytes = RecordingOutputManager.freeBytes()
        val requiredBytes = RecordingStoragePolicy.requiredStartBytes(bytesPerSecond)
        if (freeBytes < 0) Log.w(TAG, "Free storage could not be measured; recording without a low-space guard")
        if (freeBytes >= 0 && freeBytes < requiredBytes) {
            failEncoding("Not enough free storage. At least 256 MB is required.")
            return
        }

        events.markerCount.value = 0
        events.lastSaved.value = null
        currentFormat = format
        val started = writer.start(format, currentSampleRate, bitDepth, deviceLabel)
        if (started is RecordingWriter.StartResult.Failed) {
            failEncoding(started.message)
            return
        }
        safetyStopPending = false

        wakeLock.renew()
        if (!startForegroundNotification()) {
            writer.abandonStart()
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            failPreparation(BLOCKED_MESSAGE)
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
        safetyStopPending = false
        monitorHandler.removeCallbacks(meterRunnable)
        monitorHandler.removeCallbacks(waveformRunnable)
        monitorHandler.removeCallbacks(notificationRunnable)
        monitorHandler.removeCallbacks(healthRunnable)
        monitorHandler.post { healthSupervisor.reset() }
        monitorHandler.post(meterRunnable)
        monitorHandler.post(waveformRunnable)
        monitorHandler.post(notificationRunnable)
        monitorHandler.post(healthRunnable)
    }

    /** Monitor thread, while recording. */
    private fun checkpointIfDue() {
        if (_saving.value) return
        when (val result = writer.checkpointIfDue(SystemClock.elapsedRealtime())) {
            RecordingWriter.CheckpointResult.Ok -> Unit
            is RecordingWriter.CheckpointResult.Rolled -> {
                events.markerCount.value = 0
                result.problem?.let { requestSafetyStop(it) }
            }
            is RecordingWriter.CheckpointResult.Failed -> requestSafetyStop(result.message)
        }
    }

    private fun requestSafetyStop(message: String) {
        if (safetyStopPending || _saving.value) return
        safetyStopPending = true
        mainHandler.post {
            if (!_saving.value && (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused)) {
                finishRecording(message)
            } else {
                safetyStopPending = false
            }
        }
    }

    /** Closes the [UsbAudioManager] connection backing native libusb capture, if this session used it. */
    private fun releaseIsoConnectionIfNeeded() {
        idleGuard.stop()
        if (isUsbIsoSession) {
            (application as DjmRecApplication).usbAudioManager.releaseIsoCaptureConnection()
        }
    }

    private fun failPreparation(message: String) {
        pendingRecordingFormat = null
        _state.value = RecordingState.Error(message)
        _health.value = RecordingHealth(RecordingHealthLevel.ERROR, message)
        notifications.stopForeground()
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
            notifications.stopForeground()
            stopSelf()
            return
        }
        if (_state.value is RecordingState.Idle || _state.value is RecordingState.Monitoring) {
            // Full stop from monitoring: close the stream.
            if (_state.value is RecordingState.Monitoring) {
                AudioEngine.close()
                releaseIsoConnectionIfNeeded()
                wakeLock.release()
                _state.value = RecordingState.Idle
                _elapsedMillis.value = 0L
                _levels.value = StereoLevels(floorLevel, floorLevel)
                resetSignalDetector()
                _waveformBins.value = emptyWaveform
                _health.value = RecordingHealth.Ready
                notifications.stopForeground()
                stopSelf()
            }
            return
        }
        finishRecording()
    }

    /**
     * The one way a recording ends: stops the encoder and publishes the file on an IO thread,
     * then either returns to monitoring (a normal save) or closes capture and shows
     * [errorMessage] (a safety stop or an unplug). Commands are refused while [saving].
     */
    private fun finishRecording(errorMessage: String? = null) {
        if (_saving.value) return
        pendingRecordingFormat = null
        _saving.value = true
        updateNotification()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { writer.finish() }
            val failure = errorMessage
                ?: if (!result.complete) "Recording stopped; publication failed. Recovery will retry on next launch." else null
            if (failure == null) {
                result.saved?.let { events.lastSaved.value = it }
                _state.value = RecordingState.Monitoring
                isMonitoringOnly = true
                _elapsedMillis.value = 0L
                _health.value = RecordingHealth(RecordingHealthLevel.GOOD, "Saved to Music/DJMRec", RecordingOutputManager.freeBytes(), Long.MAX_VALUE)
            } else {
                AudioEngine.close()
                releaseIsoConnectionIfNeeded()
                wakeLock.release()
                isMonitoringOnly = false
                _state.value = RecordingState.Error(failure)
                _health.value = RecordingHealth(RecordingHealthLevel.ERROR, failure, RecordingOutputManager.freeBytes(), 0)
                notifications.stopForeground()
            }
            safetyStopPending = false
            _saving.value = false
            if (failure == null) updateNotification()
            if (closeAfterSave) {
                closeCaptureAndTask()
            } else if (detachAfterSave) {
                detachAfterSave = false
                // After an error stop capture is already closed; the detach has nothing left to do
                // and would only replace the more specific message.
                if (failure == null) handleDeviceDetached()
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
        wakeLock.release()
        monitorHandler.removeCallbacksAndMessages(null)
        _state.value = RecordingState.Idle
        _levels.value = StereoLevels(floorLevel, floorLevel)
        resetSignalDetector()
        _waveformBins.value = emptyWaveform
        notifications.stopForeground()
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).appTasks.forEach { it.finishAndRemoveTask() }
        stopSelf()
    }

    private fun handleDeviceDetached() {
        // Not closeAfterSave: that closes the whole app. An unplug during a save should end in the
        // normal "mixer disconnected" state once the file is safe.
        if (_saving.value) { detachAfterSave = true; return }
        pendingRecordingFormat = null
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused) {
            finishRecording("USB mixer disconnected. Recording finalized safely.")
            return
        }
        AudioEngine.close()
        releaseIsoConnectionIfNeeded()
        wakeLock.release()
        _state.value = RecordingState.Error("USB mixer disconnected")
        _health.value = RecordingHealth(RecordingHealthLevel.ERROR, "USB mixer disconnected")
        notifications.stopForeground()
    }

    override fun onDestroy() {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused) {
            // Synchronous on purpose: the lifecycle scope is already cancelled, and the file must
            // be published before the process can go.
            writer.finish()
        }
        if (_state.value !is RecordingState.Idle) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
        }
        wakeLock.release()
        idleGuard.stop()
        // The state collector is already cancelled by the time we get here, so the phone would
        // otherwise stay silenced after the service goes away.
        doNotDisturb.release()
        monitorHandler.removeCallbacksAndMessages(null)
        monitorThread.quitSafely()
        super.onDestroy()
    }

    // --- Notification --------------------------------------------------------------------

    private fun notificationModel(): NotificationModel = NotificationModel.from(
        recording = _state.value is RecordingState.Recording,
        paused = _state.value is RecordingState.Paused,
        saving = _saving.value,
        elapsedMillis = _elapsedMillis.value,
        signalPresent = _signalPresent.value,
        deviceLabel = deviceLabel
    )

    private fun startForegroundNotification(): Boolean =
        notifications.startForeground(notificationModel(), isUsbIsoSession)

    private fun updateNotification() {
        if (events.closeRequested.value && !_saving.value) return
        notifications.update(notificationModel())
    }
}

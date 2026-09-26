package com.audiopro.djmrec.audio

import com.audiopro.djmrec.domain.CaptureSource

/**
 * Thin Kotlin/JNI boundary over the native `UsbAudioEngine`. The engine owns exactly one
 * AAudio exclusive stream + one ring buffer + one encoder at a time, so this wrapper is a
 * singleton object rather than a class — mirrors the native side's lifetime.
 *
 * All native calls are safe to invoke from any thread; the native side takes its own locks
 * around state transitions. However callers should still serialize start/stop/pause calls
 * (the ViewModel does this) to avoid nonsensical overlapping transitions.
 */
object AudioEngine {

    init {
        System.loadLibrary("djmrec_audio")
    }

    /**
     * Opens the exclusive, low-latency AAudio input stream bound to [audioManagerDeviceId] and
     * allocates the ring buffer. Must be called before [startRecordingFd].
     *
     * @return the sample rate AAudio actually negotiated (may differ from a hint if the
     *   hardware does not support it), or -1 on failure.
     */
    external fun open(
        audioManagerDeviceId: Int,
        sampleRateHint: Int,
        channelCount: Int,
        bitDepth: Int
    ): Int

    /**
     * Debug builds only: opens the demo mixer, a synthetic music-like signal fed through the same
     * path as USB audio, so the app runs in an emulator. @return the sample rate, or -1.
     */
    external fun openDemo(sampleRate: Int, bitDepth: Int): Int

    /**
     * Opens the raw libusb isochronous capture path instead of AAudio/AudioRecord, extracting
     * a stereo pair out of a wider multichannel USB Audio interface. This exists because
     * AAudio has no API to select an arbitrary channel *offset* out of a multichannel UAC2
     * interface -- it only ever gives you channels 1/2 (or all N channels, undifferentiated).
     * Mixers like the Pioneer DJM-A9 expose a combined 12-channel interface. This path can
     * select any stereo pair and, for the DJM-A9, routes MIX (REC OUT) to that pair first.
     *
     * The connection behind [CaptureSource.UsbIso.fd] must stay open (not `.close()`'d) for the
     * entire capture session; see `UsbAudioManager.openIsoCaptureHandle()`.
     *
     * @param sampleRateHint the rate Kotlin chose; the source then measures the endpoint's real
     *   packet cadence and that measurement wins.
     * @return the measured sample rate on success, or -1 on failure.
     */
    fun openUsbIso(source: CaptureSource.UsbIso, sampleRateHint: Int): Int = nativeOpenUsbIso(
        source.fd,
        source.interfaceNumber,
        source.alternateSetting,
        source.endpointAddress,
        source.maxPacketSize,
        source.totalChannels,
        source.subframeSize,
        source.bitResolution,
        source.channelOffset,
        source.clock?.interfaceNumber ?: -1,
        source.clock?.sourceId ?: -1,
        source.clock?.supportsFrequencySet == true,
        source.vendorId,
        source.productId,
        source.rawDescriptors,
        sampleRateHint,
        source.includeMic,
        source.playbackOverride.nativeValue,
        source.endpointRateOverride.nativeValue,
        source.allowFormatMismatch
    )

    /** Positional JNI form of [openUsbIso]; the order must match `AudioEngineJNI.cpp`. */
    private external fun nativeOpenUsbIso(
        fd: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        totalChannels: Int,
        subframeSize: Int,
        bitResolution: Int,
        extractChannelOffset: Int,
        clockControlInterfaceNumber: Int,
        clockSourceId: Int,
        clockSupportsFrequencySet: Boolean,
        vendorId: Int,
        productId: Int,
        rawDescriptors: ByteArray,
        sampleRateHint: Int,
        includeMicInMix: Boolean,
        playbackOverride: Int,
        endpointRateOverride: Int,
        allowFormatMismatch: Boolean
    ): Int

    /** Starts encoding into the open [fd] ([format] is [RecordingFormat.nativeValue]). */
    external fun startRecordingFd(fd: Int, format: Int): Boolean

    external fun rollRecordingFd(fd: Int, format: Int): Boolean

    /** Flushes a recoverable checkpoint and returns current part bytes, or -1 on failure. */
    external fun checkpointRecording(): Long

    external fun getRecordingErrorCode(): Int

    /** False once the stream is closed *or* the raw USB transport has died (unplug, repeated
     *  transfer errors); the health check treats either as "stream closed". */
    external fun isStreamOpen(): Boolean

    /**
     * True exactly once per request from the native capture thread asking the host to route every
     * configurable Pioneer MIX pair (fallback after a fully silent first window). The caller
     * performs the vendor control transfers over its own `UsbDeviceConnection`; native code never
     * issues them from the libusb event thread.
     */
    external fun takeRouteFallbackRequest(): Boolean

    external fun pauseRecording()

    external fun resumeRecording()

    /** Stops the encoder, flushes/patches file headers, and returns the final duration in ms. */
    external fun stopRecording(): Long

    /** Stops whichever source is open and frees the ring buffer. Safe to call even if never opened. */
    external fun close()

    /**
     * Stereo meter reading `[leftPeakDb, leftRmsDb, rightPeakDb, rightRmsDb]` in dBFS: the maximum
     * since the previous call (the call resets it).
     */
    external fun getLevels(): FloatArray

    external fun isClipping(): Boolean

    external fun getElapsedMillis(): Long

    /** Underrun/overrun counters on the ring buffer, useful for diagnosing dropped audio. */
    external fun getXRunCount(): Int

    /** [completed, missed, empty, partial, bytes, nonZeroBytes, resubmitFailures] for raw USB capture. */
    external fun getUsbIsoTransferStats(): LongArray

    /** Structured native pipeline/session snapshot included in release diagnostic reports. */
    external fun getDiagnosticSummary(): String

    /**
     * RGB waveform snapshot: returns `kWaveformBinCount * 4 + 2` floats in the layout
     * `[amp0, low0, mid0, high0, amp1, low1, mid1, high1, ...]`, each in [0, 1].
     * Low ≈ red, mid ≈ green, high ≈ blue — the CDJ-3000 color mapping.
     * Trailing floats: committed cursor modulo 1048576, then bin duration in milliseconds.
     * Polled by the monitoring thread; UI scrolling uses the display frame clock.
     */
    external fun getWaveformBins(): FloatArray

    external fun setRecordingGainDb(gainDb: Int)

    /** Enables native frequency analysis for the optional live waveform. */
    external fun setWaveformEnabled(enabled: Boolean)
}

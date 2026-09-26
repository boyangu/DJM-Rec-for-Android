#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include <oboe/Oboe.h>

#include "MeterCalculator.h"
#include "RingBuffer.h"
#include "UsbIsoAudioSource.h"
#include "WaveformAnalyzer.h"
#include "writers/AudioWriter.h"

namespace djmrec {

enum class ContainerFormat : int {
    Wav = 0,
    Flac = 1
};

/**
 * Which producer is currently feeding mRingBuffer. Oboe is the default AAudio/AudioRecord
 * path (used for plain stereo UAC2 devices); UsbIso is the libusb raw-isochronous path used
 * to reach into a multichannel interface for a specific channel pair that AAudio itself has
 * no way to select.
 */
enum class SourceMode { None, Oboe, UsbIso, Demo };

/**
 * The whole native audio pipeline in one place:
 *
 *   AAudio exclusive MMAP callback (producer, realtime)
 *        -> RingBuffer (lock-free hand-off)
 *        -> encoder thread (consumer; inherits pthread defaults and is deliberately NOT
 *           realtime since file I/O/encoding must be free to block). The libusb event thread
 *           that feeds the USB-iso path raises its own priority in UsbIsoAudioSource.
 *        -> AudioWriter (WAV/FLAC)
 *
 * Exactly one recording session is supported at a time, matching the app's single-mixer,
 * single-session use case.
 */
class UsbAudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    static UsbAudioEngine& instance();

    /** Opens the exclusive AAudio input stream. Returns the negotiated sample rate, or -1. */
    int open(int32_t audioManagerDeviceId, int32_t sampleRateHint, int32_t channelCount, int32_t bitDepthHint);

    /**
     * Opens the raw libusb isochronous capture path instead of AAudio, extracting a stereo
     * pair out of a wider multichannel USB Audio interface. The source briefly measures actual frame cadence
     * before returning, which covers UAC2 devices that do not answer clock-frequency queries.
     * Returns the measured sample rate on success, or -1 on failure. The rate Kotlin asked for
     * is isoConfig.requestedSampleRate.
     */
    int openUsbIso(const UsbIsoAudioSource::Config& isoConfig);

    /**
     * Debug-only demo mixer: feeds a synthetic music-like signal (DemoSignalGenerator) through
     * the same path as USB audio, in real time, so the app can run in an emulator. Only reachable
     * from debug builds (the Kotlin side gates it). Returns the sample rate, or -1.
     */
    int openDemo(int32_t sampleRate, int32_t bitDepth);

    bool startRecordingFd(int fd, ContainerFormat format);
    bool rollRecordingFd(int fd, ContainerFormat format);
    int64_t checkpointRecording();
    int32_t getRecordingErrorCode() const;
    bool isStreamOpen() const;
    void pauseRecording();
    void resumeRecording();
    /** Stops encoding, finalizes the file, and returns total recorded duration in ms. */
    int64_t stopRecording();
    void closeEngine();

    /**
     * [leftPeakDb, leftRmsDb, rightPeakDb, rightRmsDb] — safe to call from any thread.
     *
     * DESTRUCTIVE: returns the maximum over every audio callback since the previous call and
     * resets the accumulator to the meter floor. The UI polls at ~15 Hz while USB-iso callbacks
     * arrive up to 8000x/second, so a plain "last callback wins" read examined roughly 0.1% of
     * the audio -- it missed real transients and reported the floor whenever a poll happened to
     * land in a zero crossing. Not const, because consuming the accumulator is a side effect.
     */
    void getLevels(float outLevels[4]);
    /** DESTRUCTIVE: true if any callback clipped since the previous call; clears the flag. */
    bool isClipping();
    int64_t getElapsedMillis() const;
    int32_t getXRunCount() const;
    void getUsbIsoTransferStats(uint64_t outStats[7]) const;
    /** True once per pending request: the USB-iso source wants every configurable MIX pair
     *  re-routed via the Kotlin-side vendor control path (never from the libusb event thread). */
    bool takeRouteFallbackRequest();
    std::string getDiagnosticSummary();

    /** Copies the RGB waveform snapshot into @p outBins (kBinCount * 4 + 2 floats: bins, cursor, bin duration ms).
     *  Safe to call from any thread. */
    void getWaveformBins(float* outBins) const;
    void setRecordingGainDb(int gainDb);
    void setWaveformEnabled(bool enabled);
    static constexpr int kWaveformBinCount = WaveformAnalyzer::kBinCount;

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    UsbAudioEngine() = default;

    void encoderThreadLoop();
    /** Pins an AUTO channel-pair pick so it cannot change mid-file. Call with mControlMutex held. */
    void pinCaptureChannelPair();
    static size_t bytesPerFrameFor(oboe::AudioFormat format, int32_t channelCount);

    /** Shared tail of both capture paths once a canonical stereo I32 frame batch is in hand:
     *  updates the VU meter atomics and (if recording) writes into mRingBuffer. Called from
     *  the libusb event thread by UsbIsoAudioSource's callback. */
    void onUsbIsoFrames(const int32_t* interleavedStereo, size_t frameCount);
    /** Stops and joins the demo generator thread, if running. Call with mControlMutex held. */
    void stopDemoLocked();
    void demoThreadLoop(int32_t sampleRate);

    std::shared_ptr<oboe::AudioStream> mStream;
    std::unique_ptr<UsbIsoAudioSource> mUsbIsoSource;
    std::string mLastUsbSetupFailure; // retained after failed source teardown; guarded by mControlMutex
    SourceMode mSourceMode = SourceMode::None;
    std::unique_ptr<RingBuffer> mRingBuffer;
    std::unique_ptr<AudioWriter> mWriter;
    std::unique_ptr<WaveformAnalyzer> mWaveformAnalyzer;
    std::thread mEncoderThread;
    std::thread mDemoThread;
    std::atomic<bool> mDemoRunning{false};

    mutable std::mutex mControlMutex; // guards start/stop/pause transitions (not the realtime path)
    mutable std::mutex mWriterMutex;
    std::atomic<bool> mStreamOpen{false};
    // True only while mRingBuffer and mWaveformAnalyzer belong to the running source. A source's
    // frames can arrive before its open() has finished allocating them (the USB source streams
    // during its rate probe); onUsbIsoFrames drops those rather than touch the previous session's
    // buffers while they are being replaced.
    std::atomic<bool> mSinkReady{false};
    std::atomic<bool> mRecording{false};
    std::atomic<bool> mPaused{false};
    std::atomic<bool> mStopRequested{false};
    std::atomic<bool> mWaveformEnabled{true};
    std::atomic<float> mRecordingGainLinear{1.0f};
    std::atomic<int32_t> mRecordingErrorCode{0};

    AudioFormatInfo mFormat;
    oboe::AudioFormat mOboeFormat = oboe::AudioFormat::I32;
    int32_t mChannelCount = 2;

    uint64_t mAaudioFramesSinceLog = 0;
    uint64_t mAaudioBytesSinceLog = 0;
    uint64_t mAaudioNonZeroBytesSinceLog = 0;
    float mAaudioLeftPeakSinceLog = -60.0f;
    float mAaudioRightPeakSinceLog = -60.0f;

    std::atomic<int32_t> mXRunCount{0};
    std::atomic<int64_t> mElapsedMillis{0};

    // --- Recording-path instrumentation, reported by getDiagnosticSummary() ---------------
    // These exist to tell three failure modes apart from a support report alone, without
    // needing the audio file: a ring that is overrunning (dropped frames, heard as clicks),
    // an encoder stalled behind the writer lock (the cause of the 5 s checkpoint clicks), and
    // a writer that is simply slow.
    std::atomic<int32_t> mRecordingGainDb{0};
    std::atomic<uint64_t> mRingHighWaterBytes{0};
    std::atomic<uint64_t> mEncoderLockWaitMaxMicros{0};
    std::atomic<uint64_t> mEncoderLockWaitTotalMicros{0};
    std::atomic<uint64_t> mWriteMaxMicros{0};
    std::atomic<uint64_t> mCheckpointCount{0};
    std::atomic<uint64_t> mCheckpointMaxMicros{0};
    std::atomic<uint64_t> mCheckpointLastMicros{0};

    /** Lock-free "keep the larger value" fold for the counters above. */
    static void storeMaxU64(std::atomic<uint64_t>& target, uint64_t value) {
        uint64_t previous = target.load(std::memory_order_relaxed);
        while (value > previous &&
               !target.compare_exchange_weak(previous, value, std::memory_order_relaxed)) {
        }
    }

    /** Zeroes the instrumentation so each recording reports its own figures, not the session's. */
    void resetRecordingInstrumentation() {
        mRingHighWaterBytes.store(0, std::memory_order_relaxed);
        mEncoderLockWaitMaxMicros.store(0, std::memory_order_relaxed);
        mEncoderLockWaitTotalMicros.store(0, std::memory_order_relaxed);
        mWriteMaxMicros.store(0, std::memory_order_relaxed);
        mCheckpointCount.store(0, std::memory_order_relaxed);
        mCheckpointMaxMicros.store(0, std::memory_order_relaxed);
        mCheckpointLastMicros.store(0, std::memory_order_relaxed);
    }

    // Meter state: a max-since-last-read accumulator, not a snapshot. Every realtime callback
    // folds its reading in with storeMax(); getLevels() drains it back to the floor. See the
    // getLevels() contract above for why.
    std::atomic<float> mLeftPeakDb{kMeterFloorDb};
    std::atomic<float> mLeftRmsDb{kMeterFloorDb};
    std::atomic<float> mRightPeakDb{kMeterFloorDb};
    std::atomic<float> mRightRmsDb{kMeterFloorDb};
    std::atomic<bool> mClipping{false};

    /** Lock-free "keep the larger value" fold, safe to call from a realtime audio callback. */
    static void storeMax(std::atomic<float>& target, float value) {
        float previous = target.load(std::memory_order_relaxed);
        while (value > previous &&
               !target.compare_exchange_weak(previous, value, std::memory_order_relaxed)) {
            // compare_exchange_weak refreshed `previous`; retry only while we still win.
        }
    }

    /** Folds one callback's reading into the accumulator. Shared by both capture paths. */
    void accumulateMeter(const StereoMeterReading& reading) {
        storeMax(mLeftPeakDb, reading.leftPeakDb);
        storeMax(mLeftRmsDb, reading.leftRmsDb);
        storeMax(mRightPeakDb, reading.rightPeakDb);
        storeMax(mRightRmsDb, reading.rightRmsDb);
        if (reading.clipping) mClipping.store(true, std::memory_order_relaxed);
    }

};

} // namespace djmrec

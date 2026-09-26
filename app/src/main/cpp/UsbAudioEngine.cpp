#include "UsbAudioEngine.h"

#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <cstring>
#include <sstream>

#include "MeterCalculator.h"
#include "AudioGain.h"
#include "DemoSignalGenerator.h"
#include "writers/WavWriter.h"
#include "writers/FlacWriter.h"

#define TAG "UsbAudioEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace djmrec {

UsbAudioEngine& UsbAudioEngine::instance() {
    static UsbAudioEngine engine;
    return engine;
}

size_t UsbAudioEngine::bytesPerFrameFor(oboe::AudioFormat format, int32_t channelCount) {
    int bytesPerSample;
    switch (format) {
        case oboe::AudioFormat::I16: bytesPerSample = 2; break;
        case oboe::AudioFormat::I24: bytesPerSample = 3; break;
        case oboe::AudioFormat::Float:
        case oboe::AudioFormat::I32:
        default: bytesPerSample = 4; break;
    }
    return static_cast<size_t>(bytesPerSample) * channelCount;
}

int UsbAudioEngine::open(int32_t audioManagerDeviceId, int32_t sampleRateHint, int32_t channelCount,
                          int32_t bitDepthHint) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    mLastUsbSetupFailure.clear();
    mSinkReady.store(false, std::memory_order_release);
    if (mStreamOpen.load()) {
        LOGW("open() called while a stream is already open; closing the previous one first");
    }
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    if (mUsbIsoSource) {
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
    }
    stopDemoLocked();
    mSourceMode = SourceMode::Oboe;

    mChannelCount = channelCount;
    switch (bitDepthHint) {
        case 16: mOboeFormat = oboe::AudioFormat::I16; break;
        case 24: mOboeFormat = oboe::AudioFormat::I24; break;
        default: mOboeFormat = oboe::AudioFormat::I32; break;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setAudioApi(oboe::AudioApi::AAudio) // only AAudio exposes exclusive MMAP + device binding
        ->setDeviceId(audioManagerDeviceId)
        ->setInputPreset(oboe::InputPreset::Unprocessed)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive) // bypasses AudioFlinger's mixer entirely
        ->setSampleRate(sampleRateHint)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None) // never silently resample
        ->setChannelCount(channelCount)
        ->setChannelConversionAllowed(false)
        ->setFormat(mOboeFormat)
        ->setFormatConversionAllowed(false) // never silently bit-crush/expand
        ->setDataCallback(this)
        ->setErrorCallback(this);

    if (audioManagerDeviceId <= 0) {
        LOGE("Refusing default Android input: a specific USB device is required");
        return -1;
    }
    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = builder.openStream(stream);

    if (result != oboe::Result::OK && mOboeFormat != oboe::AudioFormat::I32) {
        // Some AAudio HAL implementations only expose exclusive-mode UAC2 endpoints as I32
        // even when the wire format is 24-bit (the 4th byte is just the subslot padding
        // reported in the descriptor) -- retry once before giving up.
        LOGW("Exclusive open failed for format %d (%s); retrying with I32",
             static_cast<int>(mOboeFormat), oboe::convertToText(result));
        mOboeFormat = oboe::AudioFormat::I32;
        builder.setFormat(mOboeFormat);
        result = builder.openStream(stream);
    }

    if (result != oboe::Result::OK) {
        LOGW("Exclusive open failed (%s); retrying shared mode with channel conversion allowed",
             oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared)
            ->setInputPreset(oboe::InputPreset::Generic)
            ->setChannelConversionAllowed(true)
            ->setFormatConversionAllowed(true)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        result = builder.openStream(stream);
    }

    if (result != oboe::Result::OK) {
        LOGE("Failed to open exclusive low-latency AAudio input stream: %s", oboe::convertToText(result));
        return -1;
    }

    if (stream->getDeviceId() != audioManagerDeviceId) {
        LOGE("Android opened device %d instead of requested USB input %d",
             stream->getDeviceId(), audioManagerDeviceId);
        stream->close();
        return -1;
    }
    mStream = stream;
    mFormat.sampleRate = mStream->getSampleRate();
    mFormat.channelCount = mStream->getChannelCount();
    mChannelCount = mFormat.channelCount;
    mOboeFormat = mStream->getFormat();
    // We keep the hardware-reported bit depth (from the USB descriptor) for file headers even
    // though the wire format might be padded into I32 -- this is the *true* fidelity of the source.
    mFormat.bitsPerSample = bitDepthHint;

    mAaudioFramesSinceLog = 0;
    mAaudioBytesSinceLog = 0;
    mAaudioNonZeroBytesSinceLog = 0;
    mAaudioLeftPeakSinceLog = -60.0f;
    mAaudioRightPeakSinceLog = -60.0f;

    const size_t canonicalBytesPerFrame = bytesPerFrameFor(oboe::AudioFormat::I32, mFormat.channelCount);
    const size_t ringBufferFrames = static_cast<size_t>(mFormat.sampleRate) * 2; // 2s of headroom
    mRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * canonicalBytesPerFrame);
    mWaveformAnalyzer = std::make_unique<WaveformAnalyzer>(mFormat.sampleRate);

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        mRingBuffer.reset();
        return -1;
    }

    mStreamOpen.store(true, std::memory_order_release);
    LOGI("AAudio input open: %d Hz, %d ch, actual format=%d, sharing=%s, perf=%s",
         mFormat.sampleRate, mFormat.channelCount, static_cast<int>(mOboeFormat),
         oboe::convertToText(mStream->getSharingMode()), oboe::convertToText(mStream->getPerformanceMode()));

    return mFormat.sampleRate;
}

int UsbAudioEngine::openUsbIso(const UsbIsoAudioSource::Config& isoConfig) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    mLastUsbSetupFailure.clear();
    mSinkReady.store(false, std::memory_order_release);
    if (mStreamOpen.load()) {
        LOGW("openUsbIso() called while a stream is already open; closing the previous one first");
    }
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    if (mUsbIsoSource) {
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
    }
    stopDemoLocked();
    mSourceMode = SourceMode::UsbIso;

    // The extracted output is always exactly one stereo pair, regardless of how many channels
    // are actually present on the wire (isoConfig.totalChannels) -- that wire channel count is
    // only used internally by UsbIsoAudioSource for its demux math.
    mUsbIsoSource = std::make_unique<UsbIsoAudioSource>();
    const std::string error = mUsbIsoSource->start(
        isoConfig, [this](const int32_t* frames, size_t count) { onUsbIsoFrames(frames, count); });

    if (!error.empty()) {
        LOGE("Failed to start USB iso capture: %s", error.c_str());
        mLastUsbSetupFailure = "usb_setup_error=" + error + "\n" + mUsbIsoSource->diagnosticSummary();
        mUsbIsoSource.reset();
        mSourceMode = SourceMode::None;
        return -1;
    }

    // UAC2 clock queries are optional and the DJM-A9 rejects GET_RANGE. Measure the active
    // endpoint cadence before creating an output file so its header matches the real stream.
    const int measuredSampleRate = mUsbIsoSource->waitForMeasuredSampleRate(/*timeoutMs=*/1500);
    if (measuredSampleRate <= 0) {
        LOGE("USB iso capture produced no usable sample-rate measurement");
        mUsbIsoSource->stop();
        mLastUsbSetupFailure = "usb_setup_error=No usable sample-rate measurement\n" +
            mUsbIsoSource->diagnosticSummary();
        mUsbIsoSource.reset();
        mSourceMode = SourceMode::None;
        return -1;
    }

    mChannelCount = 2;
    mOboeFormat = oboe::AudioFormat::I32;
    mFormat.sampleRate = measuredSampleRate;
    mFormat.channelCount = 2;
    mFormat.bitsPerSample = isoConfig.bitResolution;
    const size_t canonicalBytesPerFrame = bytesPerFrameFor(oboe::AudioFormat::I32, 2);
    const size_t ringBufferFrames = static_cast<size_t>(mFormat.sampleRate) * 2;
    mRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * canonicalBytesPerFrame);
    mWaveformAnalyzer = std::make_unique<WaveformAnalyzer>(mFormat.sampleRate);
    mSinkReady.store(true, std::memory_order_release);

    mStreamOpen.store(true, std::memory_order_release);
    LOGI("USB iso capture open: %d Hz, 2ch extracted from a %dch wire "
         "format, format=I32 canonical",
            mFormat.sampleRate, isoConfig.totalChannels);

        return mFormat.sampleRate;
}

int UsbAudioEngine::openDemo(int32_t sampleRate, int32_t bitDepth) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    mLastUsbSetupFailure.clear();
    mSinkReady.store(false, std::memory_order_release);
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    if (mUsbIsoSource) {
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
    }
    stopDemoLocked();

    mSourceMode = SourceMode::Demo;
    mChannelCount = 2;
    mOboeFormat = oboe::AudioFormat::I32;
    mFormat.sampleRate = sampleRate > 0 ? sampleRate : 48000;
    mFormat.channelCount = 2;
    mFormat.bitsPerSample = bitDepth > 0 ? bitDepth : 24;
    const size_t ringBufferFrames = static_cast<size_t>(mFormat.sampleRate) * 2;
    mRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * bytesPerFrameFor(oboe::AudioFormat::I32, 2));
    mWaveformAnalyzer = std::make_unique<WaveformAnalyzer>(mFormat.sampleRate);
    mSinkReady.store(true, std::memory_order_release);

    mDemoRunning.store(true, std::memory_order_release);
    mDemoThread = std::thread(&UsbAudioEngine::demoThreadLoop, this, mFormat.sampleRate);
    mStreamOpen.store(true, std::memory_order_release);
    LOGI("Demo mixer open: %d Hz, %d-bit synthetic signal", mFormat.sampleRate, mFormat.bitsPerSample);
    return mFormat.sampleRate;
}

void UsbAudioEngine::demoThreadLoop(int32_t sampleRate) {
    // 5 ms blocks on an absolute schedule, so the stream runs at exactly the nominal rate no
    // matter how late any single wake-up is.
    DemoSignalGenerator generator(sampleRate);
    constexpr int kBlocksPerSecond = 200;
    const size_t framesPerBlock = static_cast<size_t>(sampleRate / kBlocksPerSecond);
    std::vector<int32_t> block(framesPerBlock * 2);
    auto next = std::chrono::steady_clock::now();
    while (mDemoRunning.load(std::memory_order_acquire)) {
        generator.render(block.data(), framesPerBlock);
        onUsbIsoFrames(block.data(), framesPerBlock);
        next += std::chrono::microseconds(1000000 / kBlocksPerSecond);
        std::this_thread::sleep_until(next);
    }
}

void UsbAudioEngine::stopDemoLocked() {
    mDemoRunning.store(false, std::memory_order_release);
    if (mDemoThread.joinable()) mDemoThread.join();
}

void UsbAudioEngine::onUsbIsoFrames(const int32_t* interleavedStereo, size_t frameCount) {
    // --- Invoked on UsbIsoAudioSource's libusb event thread: no blocking I/O below. ---
    // Mirrors the tail of onAudioReady() below -- meter update + optional ring-buffer write --
    // but always against a canonical, already-2-channel buffer (no per-format decode needed
    // here; UsbIsoAudioSource already produced left-justified, sign-extended int32 samples).
    if (!mSinkReady.load(std::memory_order_acquire)) return;
    static thread_local std::vector<int32_t> amplified;
    const size_t sampleCount = frameCount * 2;
    if (amplified.size() < sampleCount) amplified.resize(sampleCount);
    std::memcpy(amplified.data(), interleavedStereo, sampleCount * sizeof(int32_t));
    applyRecordingGain(amplified.data(), sampleCount, mRecordingGainLinear.load(std::memory_order_relaxed));
    const int32_t* processedStereo = amplified.data();

    const StereoMeterReading reading =
        MeterCalculator::analyze(processedStereo, static_cast<int32_t>(frameCount), oboe::AudioFormat::I32);
    accumulateMeter(reading);

    if (mWaveformEnabled.load(std::memory_order_relaxed) && mWaveformAnalyzer) {
        mWaveformAnalyzer->pushFrames(processedStereo, frameCount);
    }
    if (mRecording.load(std::memory_order_relaxed) &&
        !mPaused.load(std::memory_order_relaxed) &&
        mRingBuffer) {
        const size_t bytesToWrite = frameCount * 2 * sizeof(int32_t);
        const size_t written =
            mRingBuffer->write(reinterpret_cast<const uint8_t*>(processedStereo), bytesToWrite);
        if (written < bytesToWrite) {
            mXRunCount.fetch_add(1, std::memory_order_relaxed);
        }
        // How close the ring came to overrunning. An xrun says frames were lost; this says how
        // much headroom was left on every other callback, which is what tells us whether the
        // encoder is comfortably keeping up or riding the edge.
        storeMaxU64(mRingHighWaterBytes, static_cast<uint64_t>(mRingBuffer->availableToRead()));
    }
}

oboe::DataCallbackResult UsbAudioEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData,
                                                       int32_t numFrames) {
    // --- REALTIME THREAD: no allocation after warmup, no locks, no blocking I/O below. ---
    static thread_local std::vector<int32_t> canonical;
    const size_t sampleCount = static_cast<size_t>(numFrames) * mChannelCount;
    if (canonical.size() < sampleCount) canonical.resize(sampleCount);

    const size_t inputBytes = bytesPerFrameFor(mOboeFormat, mChannelCount) * static_cast<size_t>(numFrames);
    const auto* inputBytesPtr = static_cast<const uint8_t*>(audioData);
    for (size_t i = 0; i < inputBytes; ++i) {
        if (inputBytesPtr[i] != 0) {
            ++mAaudioNonZeroBytesSinceLog;
        }
    }
    mAaudioBytesSinceLog += inputBytes;

    switch (mOboeFormat) {
        case oboe::AudioFormat::I16: {
            const auto* src = static_cast<const int16_t*>(audioData);
            for (size_t i = 0; i < sampleCount; ++i) {
                canonical[i] = static_cast<int32_t>(src[i]) << 16;
            }
            break;
        }
        case oboe::AudioFormat::I24: {
            // Packed 3-byte little-endian PCM: sign-extend to 32 bits, then left-justify.
            const auto* src = static_cast<const uint8_t*>(audioData);
            for (size_t i = 0; i < sampleCount; ++i) {
                const size_t o = i * 3;
                int32_t v = src[o] | (src[o + 1] << 8) | (src[o + 2] << 16);
                if (v & 0x00800000) v |= static_cast<int32_t>(0xFF000000);
                canonical[i] = v << 8;
            }
            break;
        }
        case oboe::AudioFormat::Float: {
            const auto* src = static_cast<const float*>(audioData);
            for (size_t i = 0; i < sampleCount; ++i) {
                const float clamped = std::max(-1.0f, std::min(1.0f, src[i]));
                canonical[i] = static_cast<int32_t>(clamped * 2147483647.0f);
            }
            break;
        }
        case oboe::AudioFormat::I32:
        default:
            std::memcpy(canonical.data(), audioData, sampleCount * sizeof(int32_t));
            break;
    }

    applyRecordingGain(canonical.data(), sampleCount, mRecordingGainLinear.load(std::memory_order_relaxed));


    // Live stereo metering -- always computed, even while paused/stopped, so the UI VU meter
    // reflects the signal actually present at the mixer's output at all times.
    const StereoMeterReading reading =
        MeterCalculator::analyze(canonical.data(), numFrames, oboe::AudioFormat::I32);
    accumulateMeter(reading);

    mAaudioLeftPeakSinceLog = std::max(mAaudioLeftPeakSinceLog, reading.leftPeakDb);
    mAaudioRightPeakSinceLog = std::max(mAaudioRightPeakSinceLog, reading.rightPeakDb);
    mAaudioFramesSinceLog += static_cast<uint64_t>(numFrames);
    if (mAaudioFramesSinceLog >= static_cast<uint64_t>(std::max(1, mFormat.sampleRate))) {
        LOGI("AAudio payload nonzero bytes=%llu/%llu; decoded peaks L=%.1f dBFS R=%.1f dBFS; "
             "format=%d ch=%d rate=%d",
             static_cast<unsigned long long>(mAaudioNonZeroBytesSinceLog),
             static_cast<unsigned long long>(mAaudioBytesSinceLog),
             mAaudioLeftPeakSinceLog, mAaudioRightPeakSinceLog,
             static_cast<int>(mOboeFormat), mChannelCount, mFormat.sampleRate);
        mAaudioFramesSinceLog = 0;
        mAaudioBytesSinceLog = 0;
        mAaudioNonZeroBytesSinceLog = 0;
        mAaudioLeftPeakSinceLog = -60.0f;
        mAaudioRightPeakSinceLog = -60.0f;
    }

    if (mWaveformEnabled.load(std::memory_order_relaxed) && mWaveformAnalyzer) {
        mWaveformAnalyzer->pushFrames(canonical.data(), numFrames);
    }

    if (mRecording.load(std::memory_order_relaxed) &&
        !mPaused.load(std::memory_order_relaxed) &&
        mRingBuffer) {
        const size_t bytesToWrite = sampleCount * sizeof(int32_t);
        const size_t written =
            mRingBuffer->write(reinterpret_cast<const uint8_t*>(canonical.data()), bytesToWrite);
        if (written < bytesToWrite) {
            // Encoder thread fell behind (e.g. slow storage): count it, never block the
            // audio thread to catch up.
            mXRunCount.fetch_add(1, std::memory_order_relaxed);
        }
    }

    return oboe::DataCallbackResult::Continue;
}

void UsbAudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // Typically fired when the USB mixer is unplugged mid-session. We can't safely reopen
    // from this callback thread; flag state so the Kotlin layer can react (stop cleanly,
    // show "device disconnected") on its next status check.
    LOGE("Stream closed unexpectedly: %s", oboe::convertToText(error));
    mStreamOpen.store(false, std::memory_order_release);
}

void UsbAudioEngine::pinCaptureChannelPair() {
    if (mSourceMode != SourceMode::UsbIso || !mUsbIsoSource) return;
    if (!mUsbIsoSource->freezeResolvedChannelOffset()) {
        // Nothing audible has been seen yet, so AUTO has not chosen. Leaving it free means the
        // file may contain one channel-pair switch when signal first arrives -- but that instant
        // is a silence-to-music transition anyway, whereas pinning the provisional pair now
        // could commit the whole recording to the wrong (possibly silent) channels.
        LOGI("AUTO capture pair not resolved yet at record start; it will lock on first signal");
    }
}

bool UsbAudioEngine::startRecordingFd(int fd, ContainerFormat format) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mStreamOpen.load() || mRecording.load() || fd < 0) return false;

    switch (format) {
        case ContainerFormat::Wav: mWriter = std::make_unique<WavWriter>(); break;
        case ContainerFormat::Flac: mWriter = std::make_unique<FlacWriter>(); break;
    }
    if (!mWriter->openFd(fd, mFormat)) {
        LOGE("Writer failed to open MediaStore fd");
        mWriter.reset();
        return false;
    }

    mXRunCount.store(0, std::memory_order_relaxed);
    mRecordingErrorCode.store(0, std::memory_order_relaxed);
    mElapsedMillis.store(0, std::memory_order_relaxed);
    mStopRequested.store(false, std::memory_order_relaxed);
    mPaused.store(false, std::memory_order_relaxed);
    mRingBuffer->reset();
    resetRecordingInstrumentation();
    pinCaptureChannelPair();
    mRecordingStartFrame.store(mSourceMode == SourceMode::UsbIso && mUsbIsoSource ? mUsbIsoSource->framesEmitted() : 0,
                               std::memory_order_relaxed);
    if (mSourceMode == SourceMode::UsbIso && mUsbIsoSource) {
        LOGI("Recording starts at source frame %llu",
             static_cast<unsigned long long>(mRecordingStartFrame.load(std::memory_order_relaxed)));
    }
    // Keep live history: monitoring is already writing the analyzer on the audio thread.
    mRecording.store(true, std::memory_order_release);
    mEncoderThread = std::thread(&UsbAudioEngine::encoderThreadLoop, this);
    return true;
}

bool UsbAudioEngine::rollRecordingFd(int fd, ContainerFormat format) {
    std::lock_guard<std::mutex> controlLock(mControlMutex);
    if (!mRecording.load() || fd < 0) return false;

    std::unique_ptr<AudioWriter> next;
    switch (format) {
        case ContainerFormat::Wav: next = std::make_unique<WavWriter>(); break;
        case ContainerFormat::Flac: next = std::make_unique<FlacWriter>(); break;
    }
    if (!next->openFd(fd, mFormat)) return false;

    std::unique_ptr<AudioWriter> previous;
    {
        std::lock_guard<std::mutex> writerLock(mWriterMutex);
        previous = std::move(mWriter);
        mWriter = std::move(next);
    }
    const bool finalized = !previous || previous->close();
    if (!finalized) mRecordingErrorCode.store(3, std::memory_order_release);
    LOGI("Recording rolled to next MediaStore part (previous finalized=%d)", finalized);
    // Swap succeeded and next writer is live. Finalization failure is exposed separately via
    // getRecordingErrorCode(); reporting roll failure here could make caller delete active part.
    return true;
}

int64_t UsbAudioEngine::checkpointRecording() {
    // mControlMutex is held for the whole call, which is what keeps mWriter alive: every path
    // that can destroy it (startRecording*, rollRecordingFd, stopRecording, closeEngine) takes
    // this same lock. That lets the expensive fsync() run with mWriterMutex *released*.
    std::lock_guard<std::mutex> controlLock(mControlMutex);
    if (!mRecording.load()) return -1;

    const auto checkpointStart = std::chrono::steady_clock::now();
    int64_t partBytes = -1;
    {
        std::lock_guard<std::mutex> writerLock(mWriterMutex);
        if (!mWriter || !mWriter->flushRecoverable()) {
            mRecordingErrorCode.store(2, std::memory_order_release);
            return -1;
        }
        partBytes = static_cast<int64_t>(mWriter->bytesWritten());
    }

    // Outside mWriterMutex: fsync() on a MediaStore descriptor goes through FUSE and can take
    // 50-300 ms, and holding the writer lock that long stalls the encoder until the ring overruns.
    const bool synced = mWriter->syncToDisk();
    // Recorded whether or not the sync succeeded: a checkpoint that takes hundreds of
    // milliseconds is the finding, and it is no longer supposed to block the encoder while it
    // does. Compare against encoder lock_wait_max_us in the same report.
    const auto checkpointMicros = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - checkpointStart).count());
    mCheckpointCount.fetch_add(1, std::memory_order_relaxed);
    mCheckpointLastMicros.store(checkpointMicros, std::memory_order_relaxed);
    storeMaxU64(mCheckpointMaxMicros, checkpointMicros);
    if (!synced) {
        mRecordingErrorCode.store(2, std::memory_order_release);
        return -1;
    }
    return partBytes;
}

int32_t UsbAudioEngine::getRecordingErrorCode() const {
    return mRecordingErrorCode.load(std::memory_order_acquire);
}

bool UsbAudioEngine::isStreamOpen() const {
    if (!mStreamOpen.load(std::memory_order_acquire)) return false;
    // The USB-iso source stops itself on unplug / repeated transfer errors; surface that as a
    // closed stream so the Kotlin health check reacts within one tick instead of waiting for
    // several "no packets" windows.
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mSourceMode == SourceMode::UsbIso && mUsbIsoSource && !mUsbIsoSource->isRunning()) {
        return false;
    }
    return true;
}

bool UsbAudioEngine::takeRouteFallbackRequest() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    return mUsbIsoSource && mUsbIsoSource->takeRouteFallbackRequest();
}

void UsbAudioEngine::pauseRecording() {
    mPaused.store(true, std::memory_order_release);
}

void UsbAudioEngine::resumeRecording() {
    mPaused.store(false, std::memory_order_release);
}

int64_t UsbAudioEngine::stopRecording() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mRecording.load()) return 0;

    // Stop producer first, then unpause encoder so it can drain finite buffered audio.
    // Leaving mRecording true lets live capture refill ring forever; leaving mPaused true
    // deadlocks stop when user presses Stop while paused.
    mRecording.store(false, std::memory_order_release);
    mPaused.store(false, std::memory_order_release);
    mStopRequested.store(true, std::memory_order_release);
    if (mEncoderThread.joinable()) mEncoderThread.join();

    const int64_t duration = mElapsedMillis.load(std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> writerLock(mWriterMutex);
        if (mWriter) {
            if (!mWriter->close()) {
                LOGE("Writer failed while finalizing output file");
                mRecordingErrorCode.store(3, std::memory_order_release);
            }
            mWriter.reset();
        }
    }
    return duration;
}

void UsbAudioEngine::closeEngine() {
    if (mRecording.load()) {
        stopRecording();
    }

    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    if (mUsbIsoSource) {
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
    }
    stopDemoLocked();
    mSinkReady.store(false, std::memory_order_release);
    mRingBuffer.reset();
    mWaveformAnalyzer.reset();
    mSourceMode = SourceMode::None;
    mStreamOpen.store(false, std::memory_order_release);
}

void UsbAudioEngine::encoderThreadLoop() {
    constexpr size_t kChunkFrames = 960; // ~20ms chunks @48kHz; small enough for low file-write latency
    std::vector<int32_t> chunk(kChunkFrames * mFormat.channelCount);
    uint64_t framesEncoded = 0;
    const size_t bytesPerFrame = sizeof(int32_t) * mFormat.channelCount;

    while (true) {
        if (mStopRequested.load(std::memory_order_acquire) && mRingBuffer->availableToRead() == 0) {
            break;
        }
        if (mPaused.load(std::memory_order_acquire)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        const size_t bytesAvailable = mRingBuffer->availableToRead();
        if (bytesAvailable < bytesPerFrame) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        const size_t framesToRead = std::min(kChunkFrames, bytesAvailable / bytesPerFrame);
        const size_t bytesToRead = framesToRead * bytesPerFrame;
        const size_t bytesRead =
            mRingBuffer->read(reinterpret_cast<uint8_t*>(chunk.data()), bytesToRead);
        const size_t framesRead = bytesRead / bytesPerFrame;

        if (framesRead > 0) {
            // Time spent waiting for the writer lock is the window in which nothing drains the
            // ring while capture keeps filling it; reported as encoder lock_wait_max_us.
            const auto waitStart = std::chrono::steady_clock::now();
            std::lock_guard<std::mutex> writerLock(mWriterMutex);
            const auto acquired = std::chrono::steady_clock::now();
            const auto waitMicros = static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(acquired - waitStart).count());
            storeMaxU64(mEncoderLockWaitMaxMicros, waitMicros);
            mEncoderLockWaitTotalMicros.fetch_add(waitMicros, std::memory_order_relaxed);

            if (!mWriter || !mWriter->writeFrames(chunk.data(), framesRead)) {
                LOGE("Encoder write failed after %llu frames",
                     static_cast<unsigned long long>(framesEncoded));
                mRecordingErrorCode.store(1, std::memory_order_release);
                break;
            }
            storeMaxU64(mWriteMaxMicros, static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - acquired).count()));
            framesEncoded += framesRead;
            mElapsedMillis.store(
                static_cast<int64_t>(framesEncoded * 1000 / mFormat.sampleRate),
                std::memory_order_relaxed);
        }
    }
}

void UsbAudioEngine::getLevels(float outLevels[4]) {
    // Drain: hand back the peak of everything since the previous poll, then re-arm at the floor.
    outLevels[0] = mLeftPeakDb.exchange(kMeterFloorDb, std::memory_order_relaxed);
    outLevels[1] = mLeftRmsDb.exchange(kMeterFloorDb, std::memory_order_relaxed);
    outLevels[2] = mRightPeakDb.exchange(kMeterFloorDb, std::memory_order_relaxed);
    outLevels[3] = mRightRmsDb.exchange(kMeterFloorDb, std::memory_order_relaxed);
}

bool UsbAudioEngine::isClipping() {
    return mClipping.exchange(false, std::memory_order_relaxed);
}

int64_t UsbAudioEngine::getElapsedMillis() const {
    return mElapsedMillis.load(std::memory_order_relaxed);
}

int32_t UsbAudioEngine::getXRunCount() const {
    return mXRunCount.load(std::memory_order_relaxed);
}

void UsbAudioEngine::getUsbIsoTransferStats(uint64_t outStats[7]) const {
    std::fill(outStats, outStats + 7, 0);
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mUsbIsoSource) {
        return;
    }
    const auto stats = mUsbIsoSource->getTransferStats();
    outStats[0] = stats.packetsCompleted;
    outStats[1] = stats.packetsMissed;
    outStats[2] = stats.packetsEmpty;
    outStats[3] = stats.packetsPartial;
    outStats[4] = stats.bytesReceived;
    outStats[5] = stats.nonZeroBytesReceived;
    outStats[6] = stats.resubmitFailures;
}

std::string UsbAudioEngine::getDiagnosticSummary() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    const char* sourceMode = "none";
    switch (mSourceMode) {
        case SourceMode::Oboe: sourceMode = "aaudio"; break;
        case SourceMode::UsbIso: sourceMode = "usb_iso"; break;
        case SourceMode::Demo: sourceMode = "demo"; break;
        case SourceMode::None: break;
    }

    std::ostringstream out;
    out << "source_mode=" << sourceMode << '\n'
        << "stream_open=" << (mStreamOpen.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "recording=" << (mRecording.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "paused=" << (mPaused.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "format=" << mFormat.sampleRate << "Hz/" << mFormat.channelCount
        << "ch/" << mFormat.bitsPerSample << "bit\n"
        << "xrun_count=" << mXRunCount.load(std::memory_order_relaxed) << '\n'
        << "recording_error_code=" << mRecordingErrorCode.load(std::memory_order_relaxed) << '\n'
        << "elapsed_ms=" << mElapsedMillis.load(std::memory_order_relaxed) << '\n'
        << "recording_start_frame=" << mRecordingStartFrame.load(std::memory_order_relaxed) << '\n'
        // Non-destructive on purpose: a support report must never swallow a peak that the
        // VU meter is about to display. Reports the in-flight accumulator since the last poll.
        << "levels_db=peak_l:" << mLeftPeakDb.load(std::memory_order_relaxed)
        << " rms_l:" << mLeftRmsDb.load(std::memory_order_relaxed)
        << " peak_r:" << mRightPeakDb.load(std::memory_order_relaxed)
        << " rms_r:" << mRightRmsDb.load(std::memory_order_relaxed)
        << " clipping:" << (mClipping.load(std::memory_order_relaxed) ? "true" : "false");
    out << "\ngain_db=" << mRecordingGainDb.load(std::memory_order_relaxed)
        << " (a positive gain is a hard clamp with no limiter: it flat-tops peaks)";
    // Ring headroom: xrun_count says frames were lost, this says how close every other callback
    // came to losing them. high_water near capacity with xrun_count 0 is a warning, not an all-clear.
    out << "\nring=capacity_bytes:" << (mRingBuffer ? mRingBuffer->capacity() : 0)
        << " high_water_bytes:" << mRingHighWaterBytes.load(std::memory_order_relaxed);
    // lock_wait_max_us is the window in which nothing drained the ring. It should now be
    // microseconds; hundreds of milliseconds would mean a writer operation is still blocking it.
    out << "\nencoder=lock_wait_max_us:" << mEncoderLockWaitMaxMicros.load(std::memory_order_relaxed)
        << " lock_wait_total_ms:" << (mEncoderLockWaitTotalMicros.load(std::memory_order_relaxed) / 1000)
        << " write_max_us:" << mWriteMaxMicros.load(std::memory_order_relaxed);
    out << "\ncheckpoint=count:" << mCheckpointCount.load(std::memory_order_relaxed)
        << " max_us:" << mCheckpointMaxMicros.load(std::memory_order_relaxed)
        << " last_us:" << mCheckpointLastMicros.load(std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> writerLock(mWriterMutex);
        out << "\nwriter_bytes=" << (mWriter ? mWriter->bytesWritten() : 0);
    }
    if (mUsbIsoSource) {
        out << "\n--- usb_iso_source ---\n" << mUsbIsoSource->diagnosticSummary();
    } else if (!mLastUsbSetupFailure.empty()) {
        out << "\n--- failed_usb_setup ---\n" << mLastUsbSetupFailure;
    }
    return out.str();
}

void UsbAudioEngine::getWaveformBins(float* outBins) const {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mWaveformAnalyzer) {
        uint32_t sequence = 0;
        mWaveformAnalyzer->getBins(outBins, &sequence);
        outBins[kWaveformBinCount * 4] = static_cast<float>(sequence % 1048576);
        outBins[kWaveformBinCount * 4 + 1] = mWaveformAnalyzer->binDurationMillis();
    } else {
        std::memset(outBins, 0, (kWaveformBinCount * 4 + 2) * sizeof(float));
    }
}

void UsbAudioEngine::setWaveformEnabled(bool enabled) {
    mWaveformEnabled.store(enabled, std::memory_order_release);
}

} // namespace djmrec

void djmrec::UsbAudioEngine::setRecordingGainDb(int gainDb) {
    mRecordingGainDb.store(std::clamp(gainDb, -12, 24), std::memory_order_relaxed);
    mRecordingGainLinear.store(std::pow(10.0f, std::clamp(gainDb, -12, 24) / 20.0f),
                               std::memory_order_relaxed);
}

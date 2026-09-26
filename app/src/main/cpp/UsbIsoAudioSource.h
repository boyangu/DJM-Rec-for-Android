#pragma once
#include "ChannelActivity.h"

#include <atomic>
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "PioneerMixerProfiles.h"
#include "ImplicitFeedbackPacer.h"
#include "ZeroPacketFilter.h"

struct libusb_context;
struct libusb_device_handle;
struct libusb_transfer;

namespace djmrec {

/**
 * Captures the raw isochronous USB audio stream directly via libusb, bypassing AAudio /
 * AudioRecord entirely.
 *
 * Why: Android's audio HAL only ever exposes channels 1/2 (or, at best, the full interleaved
 * N-channel block starting at channel 1) of a UAC2 interface -- there is no public API to
 * select an arbitrary channel *offset*. DJ mixers such as the Pioneer DJM-A9 expose one
 * combined multichannel interface, which AAudio cannot target pair-by-pair. This class
 * configures the selected pair when needed, then demuxes it from the isochronous IN endpoint.
 *
 * How: libusb_wrap_sys_device() wraps an already-open, already-permission-granted fd (from
 * Kotlin's `UsbDeviceConnection.getFileDescriptor()`) -- libusb never calls open() on the
 * device node itself, which is exactly why this works without root on stock Android.
 *
 * Threading: start()/stop() are expected to be called from a single control thread (the
 * caller is responsible for not calling them concurrently). Internally, a dedicated thread
 * pumps libusb_handle_events_timeout_completed(); the FrameCallback supplied to start() is
 * invoked synchronously from that thread and must not block or perform blocking I/O. No
 * synchronous libusb control transfer may ever be issued from that thread (libusb's sync API
 * re-enters the event loop); vendor route changes needed mid-stream are surfaced through
 * takeRouteFallbackRequest() for the Kotlin side to perform on its own connection.
 *
 * Lifetime contract: the Kotlin-side UsbDeviceConnection that produced Config::fd MUST remain
 * open (not .close()'d) for the entire time this object is running -- closing it invalidates
 * the fd out from under libusb mid-capture.
 */
class UsbIsoAudioSource {
public:
    struct Config {
        int fd = -1;                  // UsbDeviceConnection.getFileDescriptor()
        int interfaceNumber = -1;      // AudioStreaming interface number
        int alternateSetting = -1;     // alt setting that activates the isochronous endpoint
        int endpointAddress = -1;      // e.g. 0x81 (bit 7 set = IN)
        int maxPacketSize = 0;         // wMaxPacketSize from the endpoint descriptor
        int totalChannels = 2;         // channels in the *wire* format (e.g. 12 for the DJM-A9)
        int subframeSize = 4;          // bytes per sample container (1/2/3/4)
        int bitResolution = 24;        // significant bits per sample within the container
        int extractChannelOffset = 0;  // 0-indexed first channel of the stereo pair; -1 = auto-pick loudest pair
        int clockControlInterfaceNumber = -1;
        int clockSourceId = -1;
        bool clockSupportsFrequencySet = false;
        int requestedSampleRate = 48000;
        int vendorId = -1;
        int productId = -1;
        // Route REC OUT *with* the mic bus (kernel source 0x0a) rather than "without mic" (0x0e)
        // on models that offer both. Ignored where only one variant exists.
        bool includeMicInMix = true;
        // Field overrides (Recording setup > Mixer profile > Advanced). -1 = follow the profile,
        // 0 = force off, 1 = force on.
        int playbackOverride = -1;       // silent OUT keepalive traffic
        int endpointRateOverride = -1;   // UAC1 SET_CUR sampling frequency on the capture endpoint
        // True when the wire format was entered manually: a mismatch with the native profile
        // table is then logged instead of rejecting the session.
        bool allowFormatMismatch = false;
        std::vector<uint8_t> rawDescriptors;
    };

    struct TransferStatsSnapshot {
        uint64_t packetsCompleted = 0;
        uint64_t packetsMissed = 0;
        uint64_t packetsEmpty = 0;
        uint64_t packetsPartial = 0;
        uint64_t bytesReceived = 0;
        uint64_t nonZeroBytesReceived = 0;
        uint64_t resubmitFailures = 0;
    };

    /** Canonical (left-justified, sign-extended) int32 interleaved STEREO frames.
     *  Invoked on this object's internal libusb event-handling thread -- must not block. */
    using FrameCallback = std::function<void(const int32_t* interleavedStereo, size_t frameCount)>;

    UsbIsoAudioSource() = default;
    ~UsbIsoAudioSource();

    UsbIsoAudioSource(const UsbIsoAudioSource&) = delete;
    UsbIsoAudioSource& operator=(const UsbIsoAudioSource&) = delete;

    /** Returns an empty string on success, or a human-readable error otherwise. */
    std::string start(const Config& config, FrameCallback callback);

    /** Idempotent; safe to call even if start() failed partway through or was never called. */
    void stop();

    bool isRunning() const { return mRunning.load(std::memory_order_acquire); }
    /** True once the transport itself failed (device gone, repeated transfer errors, resubmit
     *  failure) -- distinct from an orderly stop(). */
    bool hasTransportFault() const { return mTransportFault.load(std::memory_order_acquire); }
    int openedSampleRate() const { return mOpenedSampleRate.load(std::memory_order_acquire); }

    /**
     * Waits briefly for the active endpoint's frame cadence, then returns the measured rate.
     * This is the authoritative fallback when UAC2 clock controls are read-only or unavailable.
     */
    int waitForMeasuredSampleRate(int timeoutMs);

    TransferStatsSnapshot getTransferStats() const;

    /** Release-safe, read-only snapshot used by exported support reports. */
    std::string diagnosticSummary() const;

    /**
     * Returns true exactly once per request raised by the capture thread when a full silent
     * window followed the initial MIX route (fallback stage 1 -> 2). The caller should then
     * route every configurable output to MIX over its own control path (Kotlin
     * `UsbDeviceConnection.controlTransfer`) -- never from this object's event thread.
     */
    bool takeRouteFallbackRequest() { return mRouteFallbackRequested.exchange(false, std::memory_order_acq_rel); }

    /**
     * Pins the AUTO channel-pair choice for the rest of the capture, so a recording cannot
     * change which USB channels it is reading from part-way through the file.
     *
     * In AUTO the pair is only decided after a full one-second window contains audible signal;
     * until then the stream is demuxed from channels 1-2. Capture goes live well before that,
     * so without this a recording started immediately after open would begin on 1-2 and hard-cut
     * to the locked pair a moment later -- not a dropped sample but a change of *content*, which
     * is the loudest kind of click. Returns false if the pair has not been decided yet; the
     * caller should leave AUTO free in that case, because a late correct switch beats a file
     * permanently stuck on the wrong pair.
     */
    bool freezeResolvedChannelOffset() {
        if (mResolvedChannelOffset.load(std::memory_order_relaxed) < 0) return false;
        mChannelOffsetFrozen.store(true, std::memory_order_release);
        return true;
    }

private:
    void eventThreadLoop();
    void handleCompletedTransfer(libusb_transfer* transfer);
    void handlePlaybackTransfer(libusb_transfer* transfer);
    void demuxAndEmit(const uint8_t* data, size_t length);
    void updateMeasuredSampleRate(size_t payloadBytes);
    bool submitTransfer(libusb_transfer* transfer);
    bool submitPlaybackTransfer(libusb_transfer* transfer);
    bool startPioneerPlaybackSilence(int sampleRate);
    void configurePioneerRecordingRoute();
    void routePioneerOutputToMix(int output);
    void restorePioneerRecordingRoute();
    void failTransport(const char* reason);

    static void onTransferComplete(libusb_transfer* transfer);
    static void onPlaybackTransferComplete(libusb_transfer* transfer);

    const char* profileName() const { return mMixerProfile ? mMixerProfile->name : "manual"; }

    // Depth of the URB queue the kernel services while we are busy. A transfer is re-armed only
    // after all of its packets have been demuxed, so the queue is the entire margin against the
    // event thread being descheduled: run out of queued URBs and the controller simply stops
    // collecting those microframes. That loss is invisible -- the packets never existed, so no
    // counter sees them -- and the timeline closes up over the hole, which is a click. At
    // bInterval=1 (8000 microframes/s) 24 x 16 packets is ~48 ms of tolerance for ~80 KB of
    // buffers. Latency is irrelevant here: this is a recorder, not a monitor path.
    static constexpr int kNumTransfers = 24;
    static constexpr int kPacketsPerTransfer = 16;
    // Whole-URB error statuses tolerated before the transport is declared dead.
    static constexpr int kMaxConsecutiveTransferErrors = 8;
    // Per-channel activity decode runs on every Nth packet only; pair peaks still accumulate
    // over each one-second window, which is all the auto-pick and diagnostics need.
    static constexpr int kActivityDecimation = 4;

    Config mConfig{};
    FrameCallback mCallback;

    libusb_context* mContext = nullptr;
    libusb_device_handle* mHandle = nullptr;
    int mClaimedClockControlInterface = -1;
    int mClaimedPlaybackInterface = -1;
    const PioneerMixerProfile* mMixerProfile = nullptr;
    std::array<int, 6> mPioneerOriginalSources{{-1, -1, -1, -1, -1, -1}};
    std::array<int, 6> mPioneerAppliedSources{{-1, -1, -1, -1, -1, -1}};
    std::array<bool, 6> mPioneerRoutesChanged{{false, false, false, false, false, false}};
    mutable std::mutex mDiagnosticMutex;
    std::vector<libusb_transfer*> mTransfers;
    std::vector<libusb_transfer*> mPlaybackTransfers;
    int mPlaybackPacketsPerSecond = 0;
    int mPlaybackFrameBytes = 0;
    int mPlaybackMaxPacketSize = 0;
    // Effective (profile + override) duplex/rate decisions, resolved once in start().
    bool mPlaybackEnabled = false;
    int mPlaybackInterface = -1;
    int mPlaybackAlternateSetting = -1;
    int mPlaybackOutChannels = 0;
    int mPlaybackOutSubframeBytes = 0;
    bool mUseEndpointSampleRate = false;
    // Guards mPlaybackPacer: the initial submit loop on the control thread can overlap with the
    // first completions arriving on the event thread.
    mutable std::mutex mPlaybackMutex;
    ImplicitFeedbackPacer mPlaybackPacer;
    std::atomic<int> mPioneerFallbackStage{0};
    std::atomic<bool> mRouteFallbackRequested{false};
    // -999 means no request was sent; libusb errors use -1 through -99.
    std::atomic<int> mEndpointRateSetResult{-999};
    // Write-only MIX route applied after SET_INTERFACE for models without a route GET
    // (DJM-450, DJM-V10, DJM-S11): the wValue sent and the libusb result.
    std::atomic<int> mSetupRouteSetResult{-999};
    std::atomic<int> mSetupRouteValue{-1};

    std::atomic<bool> mRunning{false};
    std::atomic<bool> mTransportFault{false};
    int mConsecutiveTransferErrors = 0;
    std::atomic<int> mOutstandingTransfers{0};
    std::atomic<uint64_t> mPacketsCompleted{0};
    std::atomic<uint64_t> mPacketsMissed{0};
    std::atomic<uint64_t> mPacketsEmpty{0};
    std::atomic<uint64_t> mPacketsPartial{0};
    std::atomic<uint64_t> mBytesReceived{0};
    std::atomic<uint64_t> mNonZeroBytesReceived{0};
    std::atomic<uint64_t> mResubmitFailures{0};
    std::thread mEventThread;

    std::vector<uint8_t> mCarryover; // partial-frame bytes carried over between packets
    std::vector<uint8_t> mWorking;   // scratch: carryover + newest packet, reused per call
    std::vector<int32_t> mScratch;   // reusable decode buffer, grown as needed
    ZeroPacketFilter mZeroPacketFilter;
    std::vector<uint8_t> mZeroPacket; // maxPacketSize zero bytes, re-emitted when a withheld packet was real silence
    std::vector<uint32_t> mPairPeaks;
    ChannelActivity mChannelActivity;
    int mActivityPacketCounter = 0;
    std::atomic<int> mResolvedChannelOffset{-1};
    std::atomic<bool> mChannelOffsetFrozen{false};

    // --- Capture timing, reported by diagnosticSummary() ----------------------------------
    // The decisive measurement for telling apart the two ways a recording can be wrong without
    // any counter noticing. Compare the audio we produced against the wall clock that produced
    // it: short means frames went missing (heard as clicks), long means frames arrived twice
    // (heard as an echo or doubled transient). Neither shows up in packets_missed, because in
    // both cases the packets the host never collected simply never existed.
    std::atomic<uint64_t> mFramesEmitted{0};
    std::atomic<int64_t> mCaptureStartNanos{0};
    std::atomic<int64_t> mLastReapNanos{0};
    /** Longest stall of the libusb event thread; beyond the URB queue depth, audio is lost. */
    std::atomic<uint64_t> mMaxReapGapMicros{0};
    /** Packets whose byte count was not a whole number of frames, i.e. carryover was in play. */
    std::atomic<uint64_t> mUnalignedPackets{0};
    size_t mFramesSincePeakLog = 0;
    bool mLoggedPayloadWindow = false;
    bool mLoggedPayloadSignal = false;
    uint64_t mBytesSincePeakLog = 0;
    uint64_t mNonZeroBytesSincePeakLog = 0;
    int mRawPacketDumpsLogged = 0; // caps one-time hex dumps of raw iso packet payload at capture start
    std::atomic<int> mOpenedSampleRate{0};

    std::mutex mRateProbeMutex;
    std::condition_variable mRateProbeReady;
    std::chrono::steady_clock::time_point mRateProbeStart{};
    uint64_t mRateProbeBytes = 0;
    bool mRateProbeStarted = false;
    std::atomic<bool> mRateProbeResolved{false};
    uint64_t mRateProbePackets = 0;
    int mCapturePacketsPerSecond = 0;
    int mConfirmedClockRate = 0;
};

} // namespace djmrec

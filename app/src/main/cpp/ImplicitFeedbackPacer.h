#pragma once
#include <array>
#include <cstddef>
#include <cstdint>

namespace djmrec {

// Sizes the silent playback (OUT) packets from the capture (IN) packets the device just sent.
//
// Every Pioneer DJ / AlphaTheta USB audio device is an implicit-feedback design: the capture
// endpoint is the clock, and the host is expected to send each OUT packet with the same number of
// frames it received on IN. Linux does exactly this for all of them (sound/usb/endpoint.c,
// snd_usb_handle_sync_urb: "The OUT packet we are about to send will have the same amount of
// payload bytes per stride as the IN packet we just received"), and the kernel history shows the
// devices misbehaving whenever the host paced OUT any other way. Pacing OUT at the nominal rate,
// as this app did until v0.47.1, feeds the device a clock that is not its own; the FLX10 answered
// by padding its IN stream with a whole packet of zeros every ~0.12 s.
//
// Frame counts go through a FIFO: one push per IN packet, one pop per OUT packet. While the FIFO
// is empty -- at start-up, before the first IN completion, because playback has to be running
// before these devices emit anything (QUIRK_FLAG_PLAYBACK_FIRST) -- the nominal rate is used.
// Not thread-safe: the caller holds its playback mutex around every call.
class ImplicitFeedbackPacer {
public:
    void reset(int sampleRate, int packetsPerSecond, int maxFramesPerPacket, bool mirror) {
        mSampleRate = sampleRate;
        mPacketsPerSecond = packetsPerSecond;
        mMaxFrames = maxFramesPerPacket;
        mMirror = mirror;
        mRemainder = 0;
        mHead = mTail = mCount = 0;
        mMirrored = mNominal = mOverflow = 0;
    }

    // Records how many frames one IN packet carried. Zero (empty or lost packet) makes the
    // matching OUT packet nominal-sized rather than empty: some devices stop streaming on a
    // 0-byte OUT packet, and the device dropping a packet says nothing about its clock.
    void noteCapturePacket(int frames) {
        if (!mMirror) return;
        if (mCount == kCapacity) {
            mHead = (mHead + 1) % kCapacity;
            --mCount;
            ++mOverflow;
        }
        mRing[mTail] = static_cast<uint16_t>(frames < 0 ? 0 : frames > 0xFFFF ? 0xFFFF : frames);
        mTail = (mTail + 1) % kCapacity;
        ++mCount;
    }

    // Frames for the next OUT packet: the oldest mirrored count, else the nominal cadence.
    int nextPlaybackFrames() {
        if (mCount > 0) {
            int frames = mRing[mHead];
            mHead = (mHead + 1) % kCapacity;
            --mCount;
            if (frames > 0) {
                ++mMirrored;
                return frames > mMaxFrames ? mMaxFrames : frames;
            }
        }
        ++mNominal;
        mRemainder += static_cast<uint64_t>(mSampleRate);
        const int frames = static_cast<int>(mRemainder / mPacketsPerSecond);
        mRemainder %= static_cast<uint64_t>(mPacketsPerSecond);
        return frames > mMaxFrames ? mMaxFrames : frames;
    }

    bool mirroring() const { return mMirror; }
    size_t queued() const { return mCount; }
    uint64_t mirroredPackets() const { return mMirrored; }
    uint64_t nominalPackets() const { return mNominal; }
    uint64_t overflowDrops() const { return mOverflow; }

private:
    // Deeper than the IN and OUT URB queues together (24 x 16 each), so a stall on the OUT side
    // is absorbed rather than dropped.
    static constexpr size_t kCapacity = 1024;
    std::array<uint16_t, kCapacity> mRing{};
    size_t mHead = 0, mTail = 0, mCount = 0;
    int mSampleRate = 48000;
    int mPacketsPerSecond = 8000;
    int mMaxFrames = 1;
    bool mMirror = false;
    uint64_t mRemainder = 0;
    uint64_t mMirrored = 0, mNominal = 0, mOverflow = 0;
};

} // namespace djmrec

#pragma once
#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace djmrec {

// Removes the all-zero padding packets some devices splice into a live capture stream.
//
// Measured on a DDJ-FLX10 recording against the source track (2026-09-25): every ~0.12 s the
// device emitted one extra isochronous packet -- exactly 6 frames, every byte zero on every
// channel -- *inserted* between two packets whose audio runs on continuously. Nothing was
// missing on either side of it: with those packets cut out the recording matched the source
// to -74 dBFS with no sample slips. Left in, each one is a 136 us hole to digital zero in the
// middle of the waveform: an audible click, ~8 per second on a loud track.
//
// Real silence cannot be told apart from padding by one packet alone, so this decides one packet
// late. A zero packet that follows signal is withheld; if the next packet carries signal again it
// was padding and is discarded, and if the next packet is zero too the stream has genuinely gone
// quiet and the withheld bytes are released in order. Genuine silence is therefore never shortened
// by more than a single packet, and only at its very edge.
class ZeroPacketFilter {
public:
    struct Step {
        size_t releaseZeroBytes = 0; // emit this many zero bytes *before* the current packet
        bool emitCurrent = true;     // false: the current packet is withheld until the next one
    };

    // `aligned` must be false when the packet does not hold whole frames (bytes carried over from
    // a previous packet, or a split frame at its end): dropping such a packet would shift every
    // later frame boundary, so it is always passed through.
    Step push(const uint8_t* data, size_t length, bool aligned) {
        const bool silent = std::all_of(data, data + length, [](uint8_t b) { return b == 0; });
        if (!silent) {
            if (mHeldBytes > 0) {
                mDropped.fetch_add(1, std::memory_order_relaxed);
                mHeldBytes = 0;
            }
            mPreviousHadSignal = true;
            return {};
        }
        if (mHeldBytes > 0) {
            const Step release{mHeldBytes, true};
            mHeldBytes = 0;
            mPreviousHadSignal = false;
            return release;
        }
        if (mPreviousHadSignal && aligned) {
            mHeldBytes = length;
            return {0, false};
        }
        mPreviousHadSignal = false;
        return {};
    }

    // A packet was lost. What follows the gap says nothing about a withheld packet, so it is
    // released as real audio. Returns the zero bytes to emit.
    size_t interrupt() {
        const size_t held = mHeldBytes;
        mHeldBytes = 0;
        mPreviousHadSignal = false;
        return held;
    }

    void reset() {
        mHeldBytes = 0;
        mPreviousHadSignal = false;
        mDropped.store(0, std::memory_order_relaxed);
    }

    uint64_t droppedPackets() const { return mDropped.load(std::memory_order_relaxed); }

private:
    size_t mHeldBytes = 0;
    bool mPreviousHadSignal = false;
    std::atomic<uint64_t> mDropped{0};
};

} // namespace djmrec

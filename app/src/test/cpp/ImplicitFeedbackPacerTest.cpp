#include "ImplicitFeedbackPacer.h"
#include <cassert>
#include <iostream>

int main() {
    djmrec::ImplicitFeedbackPacer pacer;

    // Nominal cadence (empty FIFO, or mirroring off) lands exactly on the rate: 44100 frames over
    // 8000 high-speed packets, as 5s and 6s.
    pacer.reset(44100, 8000, 7, true);
    long total = 0;
    for (int i = 0; i < 8000; ++i) {
        const int frames = pacer.nextPlaybackFrames();
        assert(frames == 5 || frames == 6);
        total += frames;
    }
    assert(total == 44100);
    assert(pacer.nominalPackets() == 8000 && pacer.mirroredPackets() == 0);

    // Mirrored sizes come back in order, then it falls back to nominal when the FIFO runs dry.
    pacer.reset(44100, 8000, 7, true);
    pacer.noteCapturePacket(6);
    pacer.noteCapturePacket(5);
    pacer.noteCapturePacket(6);
    assert(pacer.queued() == 3);
    assert(pacer.nextPlaybackFrames() == 6);
    assert(pacer.nextPlaybackFrames() == 5);
    assert(pacer.nextPlaybackFrames() == 6);
    assert(pacer.mirroredPackets() == 3);
    const int fallback = pacer.nextPlaybackFrames();
    assert(fallback == 5 || fallback == 6);
    assert(pacer.nominalPackets() == 1);

    // A lost IN packet (0 frames) becomes a nominal OUT packet, never an empty one.
    pacer.reset(44100, 8000, 7, true);
    pacer.noteCapturePacket(0);
    const int filled = pacer.nextPlaybackFrames();
    assert(filled == 5 || filled == 6);
    assert(pacer.nominalPackets() == 1 && pacer.mirroredPackets() == 0);

    // Clamped to what the OUT endpoint can carry.
    pacer.reset(44100, 8000, 7, true);
    pacer.noteCapturePacket(40);
    assert(pacer.nextPlaybackFrames() == 7);

    // Overflow drops the oldest entry and counts it.
    pacer.reset(44100, 8000, 7, true);
    pacer.noteCapturePacket(1);
    for (int i = 0; i < 1024; ++i) pacer.noteCapturePacket(6);
    assert(pacer.overflowDrops() == 1);
    assert(pacer.queued() == 1024);
    assert(pacer.nextPlaybackFrames() == 6);

    // Mirroring off: capture packets are ignored entirely.
    pacer.reset(44100, 8000, 7, false);
    pacer.noteCapturePacket(6);
    assert(pacer.queued() == 0);
    assert(!pacer.mirroring());

    std::cout << "Implicit feedback pacer checks passed: nominal cadence, mirroring, lost packets, clamp, overflow\n";
}

#include "WaveformAnalyzer.h"
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <algorithm>
#include <thread>
#include <vector>
#include <iostream>

int main() {
    djmrec::WaveformAnalyzer analyzer(48000);

    // The analyzer publishes a release envelope, not the raw per-bin peak, so mirror the same
    // recurrence here. This test is about the lock-free ring never tearing or going stale; the
    // envelope simply changes what the correct value is. env(n) = max(raw(n), env(n-1) * decay).
    constexpr int kBins = 4096;
    const float decay = std::exp(-analyzer.binDurationMillis() / 250.0f);
    std::vector<float> expectedBins(kBins + 1, 0.0f);
    {
        float envelope = 0.0f;
        for (int bin = 1; bin <= kBins; ++bin) {
            const float raw = (bin % 1000) * 1000000 / 2147483648.0f;
            envelope = std::max(raw, envelope * decay);
            expectedBins[bin] = envelope;
        }
    }

    std::atomic<bool> done{false};
    std::thread producer([&] {
        std::vector<int32_t> frames(294 * 2);
        for (int bin = 1; bin <= kBins; ++bin) {
            std::fill(frames.begin(), frames.end(), (bin % 1000) * 1000000);
            analyzer.pushFrames(frames.data(), 294);
        }
        done.store(true);
    });
    do {
        std::array<float, 2048> snapshot{};
        uint32_t end = 0;
        analyzer.getBins(snapshot.data(), &end);
        for (int i = 0; i < 512; ++i) {
            const int bin = static_cast<int>(end) - 511 + i;
            const float expected = bin <= 0 ? 0.0f : expectedBins[bin];
            // Tolerance is far tighter than the sawtooth's step, so a torn or stale read still
            // fails loudly; it only absorbs float rounding in the mirrored envelope.
            assert(std::fabs(snapshot[i * 4] - expected) < 0.0001f);
        }
    } while (!done.load());
    producer.join();
    assert(std::fabs(analyzer.binDurationMillis() - 6.125f) < 0.001f);
    std::cout << "Concurrent waveform history and cursor checks passed\n";
}

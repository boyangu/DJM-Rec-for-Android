#include "WaveformAnalyzer.h"
#include "AudioGain.h"
#include <array>
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <vector>

using Snapshot = std::array<float, djmrec::WaveformAnalyzer::kBinCount * 4>;

Snapshot tone(int rate, double hz, bool inverted, bool rightOnly = false) {
    djmrec::WaveformAnalyzer analyzer(rate);
    std::vector<int32_t> samples(rate * 2);
    for (int frame = 0; frame < rate; ++frame) {
        auto sample = static_cast<int32_t>(std::sin(6.283185307179586 * hz * frame / rate) * 1073741824.0);
        samples[frame * 2] = rightOnly ? 0 : sample;
        samples[frame * 2 + 1] = inverted ? -sample : sample;
    }
    analyzer.pushFrames(samples.data(), rate);
    Snapshot bins{};
    analyzer.getBins(bins.data());
    return bins;
}

int main() {
    for (int rate : {44100, 48000, 96000}) {
        for (double hz : {80.0, 800.0, 8000.0}) {
            const auto stereo = tone(rate, hz, false);
            const auto inverted = tone(rate, hz, true);
            const auto right = tone(rate, hz, false, true);
            for (size_t i = 0; i < stereo.size(); ++i) {
                assert(std::isfinite(stereo[i]));
                assert(std::fabs(stereo[i] - inverted[i]) < 0.00001f);
            }
            const size_t last = stereo.size() - 4;
            // At 8 kHz / 48 kHz the sampled sine peaks at sqrt(3)/2 of its
            // continuous amplitude; do not require an unsampled peak of 0.5.
            assert(stereo[last] > 0.4f);
            assert(std::fabs(right[last] - stereo[last]) < 0.00001f);
            const int dominant = hz < 250 ? 1 : hz < 2000 ? 2 : 3;
            for (int band = 1; band <= 3; ++band) {
                if (band != dominant) assert(stereo[last + dominant] > stereo[last + band]);
            }
        }
        djmrec::WaveformAnalyzer analyzer(rate);
        std::vector<int32_t> samples(rate * 8, 1073741824);
        analyzer.pushFrames(samples.data(), samples.size() / 2);
        Snapshot bins{};
        analyzer.getBins(bins.data());
        for (size_t i = 0; i < bins.size(); i += 4) assert(bins[i] == 0.5f);
        analyzer.reset();
        analyzer.getBins(bins.data());
        for (float value : bins) assert(value == 0.0f);
    }
    // Release envelope: a burst that stops dead must taper instead of dropping to a flat column
    // in a single 6 ms bin. Only the drawn amplitude is shaped; timing is untouched.
    {
        const int rate = 48000;
        djmrec::WaveformAnalyzer analyzer(rate);
        std::vector<int32_t> burst(static_cast<size_t>(rate) / 2, 1073741824); // 0.25 s at half scale
        analyzer.pushFrames(burst.data(), burst.size() / 2);
        std::vector<int32_t> silence(static_cast<size_t>(rate) * 3 / 2, 0);    // then 0.75 s of nothing
        analyzer.pushFrames(silence.data(), silence.size() / 2);

        Snapshot bins{};
        uint32_t end = 0;
        analyzer.getBins(bins.data(), &end);
        const float binMs = analyzer.binDurationMillis();
        const int silentBins = static_cast<int>((750.0f / binMs));

        // Walk backwards from the newest bin over the silent stretch: it must decrease
        // monotonically rather than being zero everywhere.
        float previous = -1.0f;
        int decreasing = 0;
        for (int i = djmrec::WaveformAnalyzer::kBinCount - silentBins;
             i < djmrec::WaveformAnalyzer::kBinCount; ++i) {
            const float value = bins[static_cast<size_t>(i) * 4];
            if (previous >= 0.0f && value < previous) ++decreasing;
            previous = value;
        }
        assert(decreasing > silentBins / 2);

        // 250 ms time constant: after three of them (750 ms) the tail is down to ~2.5%.
        const float newest = bins[(djmrec::WaveformAnalyzer::kBinCount - 1) * 4];
        assert(newest < 0.05f);
        // ...but one bin after the burst it is still clearly visible, i.e. no cliff.
        const int justAfter = djmrec::WaveformAnalyzer::kBinCount - silentBins + 1;
        assert(bins[static_cast<size_t>(justAfter) * 4] > 0.1f);
    }

    int32_t samples[] = {0, 100, -100, std::numeric_limits<int32_t>::max(), std::numeric_limits<int32_t>::min()};
    djmrec::applyRecordingGain(samples, 5, 2.0f);
    assert(samples[0] == 0 && samples[1] == 200 && samples[2] == -200);
    assert(samples[3] == std::numeric_limits<int32_t>::max());
    assert(samples[4] == std::numeric_limits<int32_t>::min());
    std::cout << "Audio signal checks passed: phase, stereo, bands, rates, history, reset, "
                 "envelope tail, gain saturation\n";
}

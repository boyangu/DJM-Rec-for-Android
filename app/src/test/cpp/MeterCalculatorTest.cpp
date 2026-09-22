#include "MeterCalculator.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <vector>

using namespace djmrec;

namespace {

/** Builds one interleaved stereo I32 buffer at the given normalized amplitudes. */
std::vector<int32_t> stereo(float left, float right, int frames) {
    std::vector<int32_t> out(static_cast<size_t>(frames) * 2);
    const auto scale = [](float amplitude) {
        return static_cast<int32_t>(amplitude * 2147483647.0f);
    };
    for (int i = 0; i < frames; ++i) {
        out[i * 2] = scale(left);
        out[i * 2 + 1] = scale(right);
    }
    return out;
}

bool near(float actual, float expected, float tolerance) {
    return std::fabs(actual - expected) <= tolerance;
}

} // namespace

int main() {
    // Floor: digital silence reports the meter floor, never -inf.
    {
        const auto buffer = stereo(0.0f, 0.0f, 64);
        const auto reading = MeterCalculator::analyze(buffer.data(), 64, oboe::AudioFormat::I32);
        assert(reading.leftPeakDb == kMeterFloorDb);
        assert(reading.rightPeakDb == kMeterFloorDb);
        assert(reading.leftRmsDb == kMeterFloorDb);
        assert(!reading.clipping);
    }

    // Ceiling: full scale reads 0 dBFS and is clamped there, which is why the UI meter scale
    // tops out at 0 rather than +3.
    {
        const auto buffer = stereo(1.0f, 1.0f, 64);
        const auto reading = MeterCalculator::analyze(buffer.data(), 64, oboe::AudioFormat::I32);
        assert(near(reading.leftPeakDb, 0.0f, 0.01f));
        assert(reading.leftPeakDb <= 0.0f);
        assert(reading.clipping);
    }

    // Half scale is -6 dBFS; a steady tone's RMS equals its peak.
    {
        const auto buffer = stereo(0.5f, 0.5f, 128);
        const auto reading = MeterCalculator::analyze(buffer.data(), 128, oboe::AudioFormat::I32);
        assert(near(reading.leftPeakDb, -6.02f, 0.1f));
        assert(near(reading.leftRmsDb, -6.02f, 0.1f));
        assert(!reading.clipping);
    }

    // Channels stay independent: a loud left must not lift the right.
    {
        const auto buffer = stereo(1.0f, 0.001f, 64);
        const auto reading = MeterCalculator::analyze(buffer.data(), 64, oboe::AudioFormat::I32);
        assert(near(reading.leftPeakDb, 0.0f, 0.01f));
        assert(reading.rightPeakDb < -50.0f);
    }

    // Clip threshold sits just under full scale (~-0.3 dBFS) so near-overs are caught.
    {
        const auto below = stereo(kClipThreshold - 0.01f, 0.0f, 32);
        assert(!MeterCalculator::analyze(below.data(), 32, oboe::AudioFormat::I32).clipping);
        const auto at = stereo(kClipThreshold, 0.0f, 32);
        assert(MeterCalculator::analyze(at.data(), 32, oboe::AudioFormat::I32).clipping);
    }

    // Anything at or below the floor amplitude saturates at the floor rather than going lower.
    assert(amplitudeToDb(0.0f) == kMeterFloorDb);
    assert(amplitudeToDb(-1.0f) == kMeterFloorDb);
    assert(amplitudeToDb(0.0000001f) == kMeterFloorDb);
    assert(near(amplitudeToDb(1.0f), 0.0f, 0.001f));

    std::cout << "MeterCalculator floor/ceiling/clip checks passed\n";
}

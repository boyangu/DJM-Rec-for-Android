#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace djmrec {

inline void applyRecordingGain(int32_t* samples, size_t sampleCount, float linearGain) {
    if (linearGain == 1.0f) return;
    constexpr double minSample = static_cast<double>(std::numeric_limits<int32_t>::min());
    constexpr double maxSample = static_cast<double>(std::numeric_limits<int32_t>::max());
    for (size_t i = 0; i < sampleCount; ++i) {
        const double amplified = static_cast<double>(samples[i]) * linearGain;
        samples[i] = static_cast<int32_t>(std::clamp(amplified, minSample, maxSample));
    }
}

} // namespace djmrec

#pragma once
/*
 * Host-only stub of the single Oboe declaration the pure DSP headers need.
 *
 * MeterCalculator.h is header-only maths that happens to take an oboe::AudioFormat to pick its
 * decode loop. Pulling the real Oboe in would drag the whole NDK into the host test build for
 * one enum, so the off-device tests compile against this instead. Values mirror Oboe's own
 * ordering; only their distinctness matters here.
 *
 * This file is never on the include path of the Android build -- see the target_include_directories
 * for MeterCalculatorTest in ../CMakeLists.txt.
 */
namespace oboe {

enum class AudioFormat {
    Invalid = -1,
    Unspecified = 0,
    I16 = 1,
    Float = 2,
    I24 = 3,
    I32 = 4,
};

} // namespace oboe

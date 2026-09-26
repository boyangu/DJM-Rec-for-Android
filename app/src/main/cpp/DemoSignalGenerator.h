#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace djmrec {

// Music-like stereo test signal for the debug-only demo mixer, so the app can be run in an
// emulator with no USB device and still exercise meters, the RGB waveform and recording.
//
// A 124 BPM loop: kick on every beat (low, red in the waveform), open hats on the off-beats
// (high, blue), a bass line on eighths and a sustained pad (mids, green). Every 32 bars the drums
// drop out for 4 bars so the waveform and meters show a breakdown. Peaks sit around -6 dBFS.
//
// Output is canonical: interleaved stereo int32, left-justified, as UsbIsoAudioSource produces.
class DemoSignalGenerator {
public:
    explicit DemoSignalGenerator(int sampleRate) : mRate(sampleRate > 0 ? sampleRate : 48000) {}

    void render(int32_t* interleavedStereo, size_t frames) {
        const double samplesPerBeat = mRate * 60.0 / kBpm;
        for (size_t i = 0; i < frames; ++i, ++mSample) {
            const double beatPos = mSample / samplesPerBeat;
            const long beat = static_cast<long>(beatPos);
            const double inBeat = (beatPos - beat) * samplesPerBeat / mRate; // seconds into the beat
            const bool breakdown = (beat / 4) % 36 >= 32;

            double left = 0.0, right = 0.0;

            if (!breakdown) {
                // Kick: pitch sweeps 110 Hz -> 45 Hz, ~250 ms decay.
                const double kickFreq = 45.0 + 65.0 * std::exp(-inBeat * 30.0);
                mKickPhase += kTwoPi * kickFreq / mRate;
                const double kick = std::sin(mKickPhase) * std::exp(-inBeat * 9.0) * 0.45;
                left += kick;
                right += kick;

                // Open hat on the off-beat: differenced noise, ~60 ms decay, slightly right.
                const double sinceOffbeat = inBeat - 0.5 * samplesPerBeat / mRate;
                if (sinceOffbeat >= 0) {
                    const double noise = nextNoise();
                    const double bright = noise - mLastNoise;
                    mLastNoise = noise;
                    const double hat = bright * std::exp(-sinceOffbeat * 45.0) * 0.10;
                    left += hat * 0.8;
                    right += hat;
                }
            }

            // Bass on eighths, root cycling every bar.
            static constexpr double kRoots[4] = {55.0, 55.0, 65.41, 49.0};
            const double root = kRoots[(beat / 4) % 4];
            const double eighth = std::fmod(beatPos * 2.0, 1.0);
            mBassPhase += kTwoPi * root / mRate;
            const double bass = (std::sin(mBassPhase) + 0.3 * std::sin(2.0 * mBassPhase)) *
                                std::exp(-eighth * 3.0) * 0.18;
            left += bass;
            right += bass;

            // Pad: A minor triad in the mids, slowly panned.
            const double pan = 0.5 + 0.4 * std::sin(kTwoPi * mSample / (mRate * 8.0));
            double pad = 0.0;
            for (int n = 0; n < 3; ++n) {
                mPadPhase[n] += kTwoPi * kPad[n] / mRate;
                pad += std::sin(mPadPhase[n]);
            }
            pad *= breakdown ? 0.09 : 0.05;
            left += pad * (1.0 - pan);
            right += pad * pan;

            interleavedStereo[i * 2] = toInt32(left);
            interleavedStereo[i * 2 + 1] = toInt32(right);
        }
        for (double& phase : mPadPhase) phase = std::fmod(phase, kTwoPi);
        mKickPhase = std::fmod(mKickPhase, kTwoPi);
        mBassPhase = std::fmod(mBassPhase, kTwoPi);
    }

private:
    static constexpr double kBpm = 124.0;
    static constexpr double kTwoPi = 6.283185307179586;
    static constexpr double kPad[3] = {220.0, 261.63, 329.63};

    static int32_t toInt32(double v) {
        v = std::clamp(v, -1.0, 1.0);
        return static_cast<int32_t>(std::lround(v * 2147483647.0));
    }

    double nextNoise() {
        mNoise ^= mNoise << 13;
        mNoise ^= mNoise >> 17;
        mNoise ^= mNoise << 5;
        return static_cast<int32_t>(mNoise) / 2147483648.0;
    }

    int mRate;
    uint64_t mSample = 0;
    double mKickPhase = 0.0;
    double mBassPhase = 0.0;
    double mPadPhase[3] = {0.0, 0.0, 0.0};
    uint32_t mNoise = 0x9E3779B9u;
    double mLastNoise = 0.0;
};

} // namespace djmrec

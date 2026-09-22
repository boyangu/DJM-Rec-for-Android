// A recording is checkpointed every few seconds while it is still being written: the RIFF sizes
// are patched in place so a file left behind by a crash is still playable. Patching means seeking
// backwards to offsets 4 and 40 in the middle of a stream of appends, so a checkpoint that failed
// to restore the write position would overwrite live audio -- damage that would show up as a
// click on exactly the checkpoint interval, which is the symptom this test exists to rule out.
//
// It also pins the split that keeps checkpoints from stalling the encoder: flushRecoverable()
// does the seeking half under the writer lock, syncToDisk() does the slow fsync() outside it.
// Both must be safe to call between any two writeFrames() calls, in either order, repeatedly.

#include "writers/WavWriter.h"

#include <cassert>
#include <cstdint>
#include <cstdio>
#include <iostream>
#include <string>
#include <vector>

namespace {

constexpr int kSampleRate = 44100;
constexpr int kChannels = 2;
constexpr int kBitsPerSample = 24;
constexpr size_t kHeaderBytes = 44;
constexpr size_t kBytesPerSample = 3;

// Distinct, non-repeating values so a duplicated or overwritten region cannot coincidentally
// match the expected payload.
int32_t sampleAt(size_t index) {
    // Shifted down one bit so the result always fits in int32_t: converting an out-of-range
    // unsigned to a signed type is implementation-defined before C++20 and this is a C++17 build.
    const uint32_t hashed = static_cast<uint32_t>(index) * 2654435761u;
    return static_cast<int32_t>(hashed >> 1) - 0x20000000;
}

std::vector<uint8_t> readAll(const std::string& path) {
    FILE* f = fopen(path.c_str(), "rb");
    assert(f && "recorded file is missing");
    fseek(f, 0, SEEK_END);
    const long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    if (size > 0) {
        const size_t got = fread(bytes.data(), 1, bytes.size(), f);
        assert(got == bytes.size() && "short read of the recorded file");
        (void)got;
    }
    fclose(f);
    return bytes;
}

uint32_t readU32(const std::vector<uint8_t>& bytes, size_t offset) {
    return static_cast<uint32_t>(bytes[offset]) |
           (static_cast<uint32_t>(bytes[offset + 1]) << 8) |
           (static_cast<uint32_t>(bytes[offset + 2]) << 16) |
           (static_cast<uint32_t>(bytes[offset + 3]) << 24);
}

} // namespace

int main() {
    const std::string path = "wav_checkpoint_test.wav";
    std::remove(path.c_str());

    djmrec::AudioFormatInfo format;
    format.sampleRate = kSampleRate;
    format.channelCount = kChannels;
    format.bitsPerSample = kBitsPerSample;

    // Five bursts of audio with a checkpoint between each, mirroring a long recording: the
    // interesting boundaries are the writes that immediately follow a header patch.
    const std::vector<size_t> burstFrames = {512, 1, 4096, 37, 960};
    size_t totalSamples = 0;
    for (size_t frames : burstFrames) totalSamples += frames * kChannels;

    std::vector<int32_t> allSamples(totalSamples);
    for (size_t i = 0; i < totalSamples; ++i) allSamples[i] = sampleAt(i);

    {
        djmrec::WavWriter writer;
        assert(writer.open(path, format) && "failed to open the test file");

        size_t sampleCursor = 0;
        for (size_t burst = 0; burst < burstFrames.size(); ++burst) {
            const size_t frames = burstFrames[burst];
            assert(writer.writeFrames(allSamples.data() + sampleCursor, frames));
            sampleCursor += frames * kChannels;

            assert(writer.flushRecoverable() && "checkpoint header patch failed");
            assert(writer.syncToDisk() && "checkpoint fsync failed");
            // bytesWritten() is what the service stores as the recoverable part size, so it has
            // to be the payload length and not include the header.
            assert(writer.bytesWritten() == sampleCursor * kBytesPerSample);

            // Two checkpoints back to back, and a sync with no intervening write, must both be
            // harmless -- the health tick can fire twice inside one encoder chunk.
            assert(writer.flushRecoverable());
            assert(writer.flushRecoverable());
            assert(writer.syncToDisk());
        }
        assert(writer.close());
    }

    const std::vector<uint8_t> bytes = readAll(path);
    const size_t expectedPayload = totalSamples * kBytesPerSample;
    assert(bytes.size() == kHeaderBytes + expectedPayload && "file length changed by checkpoints");

    assert(bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F');
    assert(readU32(bytes, 4) == 36 + expectedPayload && "RIFF size not patched to the real length");
    assert(readU32(bytes, 40) == expectedPayload && "data size not patched to the real length");

    // The payload must be every sample, in order, exactly once. A checkpoint that left the file
    // position at offset 8 rather than EOF would corrupt the bytes right after a patch, which is
    // precisely what this comparison catches.
    for (size_t i = 0; i < totalSamples; ++i) {
        const int32_t truncated = allSamples[i] >> 8;
        const uint8_t* actual = bytes.data() + kHeaderBytes + i * kBytesPerSample;
        assert(actual[0] == static_cast<uint8_t>(truncated & 0xFF));
        assert(actual[1] == static_cast<uint8_t>((truncated >> 8) & 0xFF));
        assert(actual[2] == static_cast<uint8_t>((truncated >> 16) & 0xFF));
    }

    std::remove(path.c_str());
    std::cout << "WAV checkpoint leaves the audio payload intact\n";
    return 0;
}

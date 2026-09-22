#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace djmrec {

/** Describes the canonical, always-int32-interleaved PCM stream every writer consumes. */
struct AudioFormatInfo {
    int sampleRate = 48000;
    int channelCount = 2;
    /** Effective bit depth of the *source* hardware (16/24/32) -- drives WAV/FLAC container depth. */
    int bitsPerSample = 24;
};

/**
 * Common interface for the three container/encoder back ends. The engine always hands
 * writers full-scale, left-justified `int32_t` interleaved samples (i.e. the native AAudio
 * capture format normalized to 32 bits) so writers never need per-format branching for the
 * *input* side -- only for how they re-scale on the way *out*.
 */
class AudioWriter {
public:
    virtual ~AudioWriter() = default;

    virtual bool open(const std::string& path, const AudioFormatInfo& format) = 0;

    /** Opens a duplicate of an Android MediaStore file descriptor. */
    virtual bool openFd(int fd, const AudioFormatInfo& format) = 0;

    /** Consumes `frameCount` frames (frameCount * channelCount int32 samples) from `interleaved`. */
    virtual bool writeFrames(const int32_t* interleaved, size_t frameCount) = 0;

    /** Finalizes the file (patches headers / flushes encoder state) and closes the handle. */
    virtual bool close() = 0;

    /**
     * Makes the file on disk self-describing (patches container sizes) and pushes userspace
     * buffers into the kernel. Cheap -- no device I/O -- but it touches writer state, so the
     * caller must hold the same lock it holds around writeFrames().
     */
    virtual bool flushRecoverable() = 0;

    /**
     * Asks the kernel to persist what flushRecoverable() handed it. This is the expensive half:
     * on a FUSE-backed MediaStore descriptor it routinely costs tens to hundreds of
     * milliseconds. It touches only the file descriptor, never writer state, so it MUST be
     * called with the writer lock released -- holding it here stalls the encoder thread and
     * overruns the capture ring, which is audible as a click on the checkpoint interval.
     */
    virtual bool syncToDisk() = 0;

    virtual uint64_t bytesWritten() const = 0;
};

} // namespace djmrec

"""Remove the padding-packet clicks from a recording made before the capture fix.

Usage:  python repair_zero_holes.py "in.wav" "out.wav"

Some devices (confirmed on the DDJ-FLX10) splice an extra USB packet of pure digital zero into
the capture stream every fraction of a second. The packet is *inserted*: the audio before and
after it runs on continuously, so cutting it out restores the original exactly rather than
papering over a gap. find_clicks.py reports these as "zero holes".

A run is removed only when it is short (at most MAX_HOLE_FRAMES frames), every channel is exactly
zero, and there is signal on both sides of it. Real silence -- a track ending, a pause -- is
longer than that and is left alone. The output is a few milliseconds per minute shorter.

Pure standard library. Streams the file, so a full-length set does not need to fit in memory.
The input is never modified.
"""
import sys
import wave

CHUNK_FRAMES = 1 << 16
MAX_HOLE_FRAMES = 64


def repair(src, dst):
    with wave.open(src, 'rb') as reader, wave.open(dst, 'wb') as writer:
        writer.setparams(reader.getparams())
        step = reader.getsampwidth() * reader.getnchannels()
        rate = reader.getframerate()
        silent_frame = bytes(step)
        pending = bytearray()      # zero frames seen since the last signal, not yet written
        signal_seen = False
        removed = removed_frames = 0
        out = bytearray()
        while True:
            raw = reader.readframes(CHUNK_FRAMES)
            if not raw:
                break
            for i in range(0, len(raw) - step + 1, step):
                frame = raw[i:i + step]
                if frame == silent_frame:
                    pending += frame
                    continue
                if pending:
                    run = len(pending) // step
                    if signal_seen and run <= MAX_HOLE_FRAMES:
                        removed += 1
                        removed_frames += run
                    else:
                        out += pending
                    pending = bytearray()
                signal_seen = True
                out += frame
            writer.writeframes(bytes(out))
            out = bytearray()
        writer.writeframes(bytes(pending))  # trailing silence is real
    print("removed %d holes (%d frames, %.1f ms)" % (removed, removed_frames,
                                                     1000.0 * removed_frames / rate))
    return removed


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 1
    if sys.argv[1] == sys.argv[2]:
        print("refusing to overwrite the input; give a different output path")
        return 1
    repair(sys.argv[1], sys.argv[2])
    return 0


if __name__ == '__main__':
    sys.exit(main())

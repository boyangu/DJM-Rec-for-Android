#!/usr/bin/env python3
"""Match the USB packet losses in a diagnostic report to positions in the recording.

    python match_misses.py "Set Recorder diagnostic log" rec.wav

Reads `recent_misses=` (the native capture's ring of the last 512 losses) and
`recording_start_frame=` from the report, converts each loss to a time in the file, and measures
whether the audio takes a step there that the surrounding music does not explain. A loss removes
five or six frames at 44.1 kHz; in a sustained tone that is an audible tick, in a transient or a
quiet passage it may not be. The same measurement at random positions gives the baseline.

Standard library only, like the other tools here. Reads the WAV once, only around each position.
"""
import random
import re
import struct
import sys
import wave

WINDOW_BEFORE = 3       # frames before the logged position to inspect
WINDOW_AFTER = 9        # frames after (the gap sits at +0..+8)
CONTEXT = 0.025         # seconds either side used as the "what the music does anyway" scale


def parse_report(path):
    text = open(path, encoding="utf-8", errors="replace").read()
    start = re.search(r"^recording_start_frame=(\d+)", text, re.M)
    block = re.search(r"^recent_misses=total:(\d+) shown:(\d+)((?:\n  .*)*)", text, re.M)
    if not block:
        sys.exit("no recent_misses line in the report (needs a build from 2026-09-26 or later)")
    total, shown = int(block.group(1)), int(block.group(2))
    misses = []
    for token in block.group(3).split():
        wall_ms, frame, packets, status = token.split(":")
        misses.append((int(wall_ms), int(frame), int(packets), int(status)))
    return (int(start.group(1)) if start else None), total, shown, misses


def read_mono(w, frame, count):
    """`count` mono frames starting at `frame`, as floats in -1..1."""
    ch, sw = w.getnchannels(), w.getsampwidth()
    w.setpos(max(0, frame))
    raw = w.readframes(count)
    out = []
    step = ch * sw
    for i in range(0, len(raw) - step + 1, step):
        acc = 0
        for c in range(ch):
            chunk = raw[i + c * sw:i + (c + 1) * sw]
            if sw == 3:
                v = int.from_bytes(chunk, "little", signed=True)
                acc += v / 8388608.0
            elif sw == 2:
                acc += struct.unpack("<h", chunk)[0] / 32768.0
            else:
                acc += int.from_bytes(chunk, "little", signed=True) / 2147483648.0
        out.append(acc / ch)
    return out


def step_score(w, rate, pos):
    """Largest second difference near `pos`, relative to the 90th percentile in the context."""
    ctx = int(rate * CONTEXT)
    lo = max(0, pos - ctx)
    seg = read_mono(w, lo, 2 * ctx)
    if len(seg) < 8:
        return 0.0, pos
    d2 = [abs(seg[i + 2] - 2 * seg[i + 1] + seg[i]) for i in range(len(seg) - 2)]
    ordered = sorted(d2)
    typical = ordered[int(len(ordered) * 0.9)] + 1e-9
    a = max(0, pos - WINDOW_BEFORE - lo)
    b = min(len(d2), pos + WINDOW_AFTER - lo)
    if b <= a:
        return 0.0, pos
    k = max(range(a, b), key=lambda i: d2[i])
    return d2[k] / typical, lo + k


def fmt(seconds):
    m, s = divmod(seconds, 60)
    return "%d:%06.3f" % (m, s)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    report, path = sys.argv[1], sys.argv[2]
    start_frame, total, shown, misses = parse_report(report)
    with wave.open(path, "rb") as w:
        rate, frames = w.getframerate(), w.getnframes()
        print("report      : %d losses since capture opened, %d kept" % (total, shown))
        if start_frame is None:
            sys.exit("no recording_start_frame in the report: the report was exported before a recording started")
        print("file        : %s, %d Hz, %s" % (path, rate, fmt(frames / rate)))
        inside = [(wall, f - start_frame, p, st) for wall, f, p, st in misses if 0 <= f - start_frame < frames]
        print("in this file: %d losses" % len(inside))
        if not inside:
            return 0
        print()
        print("  #   time in file   packets  status   step vs music")
        scores = []
        for i, (wall, pos, packets, status) in enumerate(inside, 1):
            score, at = step_score(w, rate, pos)
            scores.append(score)
            mark = "  <-- audible step" if score >= 10 else ""
            print("%3d   %12s   %7d  %6d   %6.1fx%s" % (i, fmt(at / rate), packets, status, score, mark))
        rng = random.Random(1)
        control = [step_score(w, rate, rng.randrange(rate, frames - rate))[0] for _ in range(300)]
        def frac(values, thr):
            return 100.0 * sum(1 for v in values if v > thr) / len(values)
        print()
        print("steps above 10x the surrounding music: %d%% of losses, %d%% of random positions"
              % (frac(scores, 10), frac(control, 10)))
        print("steps above  5x:                       %d%% of losses, %d%% of random positions"
              % (frac(scores, 5), frac(control, 5)))
        if len(inside) > 1:
            gaps = [(inside[i + 1][1] - inside[i][1]) / rate for i in range(len(inside) - 1)]
            bursts = sum(1 for g in gaps if g < 0.05)
            print("spacing: %d of %d losses came within 50 ms of the previous one (bursts)" % (bursts, len(gaps)))
        print("status 1 = bus error or missed service interval (the phone's USB controller), "
              "6 = the device sent more than it should")
    return 0


if __name__ == "__main__":
    sys.exit(main())

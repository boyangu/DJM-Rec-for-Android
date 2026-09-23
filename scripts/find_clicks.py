"""Locate clicks in a recorded WAV and report how they are spaced.

The spacing is the whole point. A click every ~5.0 s means the app's own periodic work was
damaging the file (the recording checkpoint runs on that interval); ~2.0 s points at the health
tick; irregular spacing points at lost USB packets or buffer overruns instead.

Usage:  python find_clicks.py "Set Recorder recording.wav"

Pure standard library. Handles 16/24/32-bit PCM WAV, and streams the file in chunks so a
full-length set does not have to fit in memory.

How it decides
--------------
It does NOT look at how big a sample-to-sample step is. That test does not work: dropping samples
out of a continuous waveform leaves a phase jump whose step is often no larger than the music's
own motion (measured on a file with known gaps: real breaks gave steps of 0.04-0.19 against a
99.9th-percentile normal step of 0.016 -- indistinguishable).

Audio is band limited, so what a dropout really breaks is smoothness: s[i] stops following on
from s[i-1] and s[i-2]. The second difference measures that directly. On test material with known
dropouts the separation is wide and clean -- ordinary programme material peaked at 1.3x the 99th
percentile while every real gap landed above 4.5x -- so the threshold sits at 3x, in the empty
band between them.
"""
import heapq
import sys
import wave

CHUNK_FRAMES = 1 << 16
# One in every SUBSAMPLE curvature values feeds the percentile estimate. Programme statistics are
# stable over millions of samples, so this costs nothing in accuracy and bounds memory.
SUBSAMPLE = 53
MAX_CANDIDATES = 20000
# Threshold as a multiple of the 99th percentile of curvature. The 99th is used rather than a
# higher percentile because on a badly damaged file the clicks themselves would contaminate the
# tail; 1% of a recording is never clicks.
THRESHOLD_RATIO = 3.0


def analyse(path):
    """Streams the file, returning (rate, frames, p99 curvature, candidate outliers)."""
    with wave.open(path, 'rb') as w:
        channels = w.getnchannels()
        width = w.getsampwidth()
        rate = w.getframerate()
        frames = w.getnframes()
        print("file       : %s" % path)
        print("format     : %d ch, %d-bit, %d Hz, %d frames (%s)"
              % (channels, width * 8, rate, frames, hms(frames / float(rate))))
        if frames > 20 * 60 * rate:
            print("note       : long file; this takes a few minutes. A 2-3 minute recording that")
            print("             still clicks is enough to find the interval.")

        step = width * channels
        full = float(1 << (width * 8 - 1))
        scale_samples = []
        candidates = []  # min-heap of (curvature, sample index)
        prev1 = prev2 = 0.0
        peak = 0.0
        index = 0
        counter = 0

        while True:
            raw = w.readframes(CHUNK_FRAMES)
            if not raw:
                break
            for i in range(0, len(raw) - step + 1, step):
                # Left channel only: a dropout hits both channels at the same instant.
                value = int.from_bytes(raw[i:i + width], 'little', signed=True) / full
                if index >= 2:
                    curvature = abs(value - 2.0 * prev1 + prev2)
                    counter += 1
                    if counter % SUBSAMPLE == 0:
                        scale_samples.append(curvature)
                    if len(candidates) < MAX_CANDIDATES:
                        heapq.heappush(candidates, (curvature, index))
                    elif curvature > candidates[0][0]:
                        heapq.heapreplace(candidates, (curvature, index))
                magnitude = abs(value)
                if magnitude > peak:
                    peak = magnitude
                prev2 = prev1
                prev1 = value
                index += 1

    if not scale_samples:
        return rate, frames, 0.0, 0.0, []
    scale_samples.sort()
    p99 = scale_samples[int(len(scale_samples) * 0.99)]
    return rate, frames, p99, peak, candidates


def hms(seconds):
    return "%d:%02d:%05.2f" % (int(seconds // 3600), int(seconds % 3600 // 60), seconds % 60)


def musical_grid(gaps):
    """Does the spacing fall on a musical grid rather than a machine's?

    Electronic music has near-vertical attacks, which score as high curvature just like a real
    splice does -- on a real DJ set this detector flagged 512 "clicks" whose spacings were
    0.484 / 0.242 / 0.121 s: the beat, eighth and sixteenth at 124 BPM. Those were the track's
    kicks and hats, not damage. A fault in the app is periodic on a clock or spread at random; it
    has no reason to land on sixteenth notes, so if the gaps fit a grid, the finding is the music.
    """
    if len(gaps) < 8:
        return None
    best = None
    for bpm10 in range(600, 2001):          # 60.0 to 200.0 BPM
        sixteenth = 60.0 / (bpm10 / 10.0) / 4.0
        fitted = 0
        for gap in gaps:
            steps = gap / sixteenth
            if 0.5 <= steps <= 64 and abs(steps - round(steps)) <= 0.06:
                fitted += 1
        share = fitted / float(len(gaps))
        if best is None or share > best[1]:
            best = (bpm10 / 10.0, share)
    return best if best and best[1] >= 0.7 else None


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    rate, frames, p99, peak, candidates = analyse(sys.argv[1])
    if frames < rate:
        print("too short to analyse")
        return 1
    if p99 <= 0.0:
        print("the file is digital silence end to end")
        return 0

    threshold = p99 * THRESHOLD_RATIO
    print("curvature  : 99th %.6f   peak level %.3f   click threshold %.6f (%.1fx)"
          % (p99, peak, threshold, THRESHOLD_RATIO))

    # Collapse each burst into one event: a single splice disturbs a few consecutive samples.
    ordered = sorted(candidates, key=lambda pair: pair[1])
    clicks = []
    guard = rate // 50  # 20 ms
    for curvature, position in ordered:
        if curvature < threshold:
            continue
        if clicks and position - clicks[-1][0] <= guard:
            if curvature > clicks[-1][1]:
                clicks[-1] = (clicks[-1][0], curvature)
            continue
        clicks.append((position, curvature))

    print("clicks found: %d" % len(clicks))
    if not clicks:
        worst = max(candidates)[0] if candidates else 0.0
        print("\nNothing in this file breaks waveform continuity (the worst point reaches %.1fx"
              % (worst / p99 if p99 else 0.0))
        print("the normal level, against the %.1fx threshold). If you still hear clicks they are"
              % THRESHOLD_RATIO)
        print("in the source material or added by the player, not cut into the recording.")
        return 0

    print("\n  #     time        gap from previous (s)   strength")
    previous = None
    gaps = []
    for n, (position, curvature) in enumerate(clicks[:60], 1):
        t = position / float(rate)
        if previous is None:
            print("%3d   %s   %21s   %5.1fx" % (n, hms(t), "-", curvature / p99))
        else:
            gap = t - previous
            gaps.append(gap)
            print("%3d   %s   %21.3f   %5.1fx" % (n, hms(t), gap, curvature / p99))
        previous = t
    if len(clicks) > 60:
        print("... %d more" % (len(clicks) - 60))
    if len(clicks) >= MAX_CANDIDATES:
        print("(hit the %d-event cap; the spacing above is still representative)" % MAX_CANDIDATES)

    if gaps:
        ordered_gaps = sorted(gaps)
        mid = ordered_gaps[len(ordered_gaps) // 2]
        # Spread of the middle 80% ignores one-off extra clicks that would mask a steady interval.
        lo = ordered_gaps[int(len(ordered_gaps) * 0.1)]
        hi = ordered_gaps[int(len(ordered_gaps) * 0.9)]
        print("\nmedian gap : %.3f s   (10th-90th percentile %.3f - %.3f)" % (mid, lo, hi))
        grid = musical_grid(gaps)
        if grid:
            bpm, share = grid
            print("VERDICT: %.0f%% of these land on a musical grid at about %.1f BPM"
                  % (share * 100, bpm))
            print("         (beat %.3f s, sixteenth %.3f s). They are the track's own kicks and"
                  % (60.0 / bpm, 60.0 / bpm / 4))
            print("         hats, not damage: no fault in the app has a reason to happen on")
            print("         sixteenth notes. Judge this file with find_echo.py and the app's")
            print("         own diagnostic report instead.")
        elif hi - lo < 0.5:
            print("VERDICT: strongly periodic at ~%.2f s." % mid)
            if abs(mid - 5.0) < 0.4:
                print("  ~5 s matches the recording checkpoint (header patch + fsync).")
            elif abs(mid - 2.0) < 0.3:
                print("  ~2 s matches the health tick.")
            else:
                print("  Does not match a known app interval; note the value.")
        else:
            print("VERDICT: irregular spacing, so this looks like lost USB packets or momentary")
            print("         buffer overruns rather than periodic app work. Check packets_missed")
            print("         and xrun_count in the app's diagnostic report.")
    return 0


if __name__ == '__main__':
    sys.exit(main())

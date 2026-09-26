"""Hunt for repeated or echoed audio in a recorded WAV.

For the symptom "a ghost of the kick, only in the highs, always in the same places". That is not
the same fault as a periodic click (see find_clicks.py) -- nothing is missing from the file, some
audio is arriving *twice*. Two mechanisms can do that, and they leave different fingerprints:

  A. A buffer written or read twice. The repeat is then bit-for-bit identical to the original,
     which real audio never is, so a single exact match is conclusive. The distance between the
     copies names the buffer that did it.
  B. A fixed short delay mixed in. Below ~25 ms this is heard as a metallic ring on transients
     rather than a distinct echo, and it hits the highs hardest because that is where a kick's
     attack lives. The delay is the fingerprint.

Both are reported with the delay expressed in the units that matter: USB packets, URBs, encoder
chunks. A delay that lands exactly on one of those is the bug.

Usage:  python find_echo.py "Set Recorder recording.wav"

Pure standard library. A 2-3 minute recording that clearly has the artefact is ideal; whole sets
work but take a few minutes.
"""
import sys
import wave
from collections import Counter

# Buffer sizes in the capture path, for naming a delay once we have measured it.
# frames @44.1 kHz -> what it would mean.
KNOWN_BUFFERS = [
    ("one USB packet (5 frames)", 5),
    ("one USB packet (6 frames)", 6),
    ("one URB, 16 packets", 88),
    ("one encoder chunk (960 frames)", 960),
]

BLOCK = 128          # samples compared for an exact-duplicate match
ENVELOPE_HOP_MS = 1.0
MIN_ECHO_MS = 3.0    # below this the two copies are not separable in the envelope
MAX_ECHO_MS = 150.0


def load(path):
    with wave.open(path, "rb") as w:
        channels, width, rate, frames = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(frames)
    print("file       : %s" % path)
    print("format     : %d ch, %d-bit, %d Hz, %d frames (%s)"
          % (channels, width * 8, rate, frames, hms(frames / float(rate))))
    step = width * channels
    full = float(1 << (width * 8 - 1))
    left, right = [], []
    for i in range(0, len(raw) - step + 1, step):
        left.append(int.from_bytes(raw[i:i + width], "little", signed=True) / full)
        if channels > 1:
            off = i + width
            right.append(int.from_bytes(raw[off:off + width], "little", signed=True) / full)
    return left, (right or left), rate


def hms(seconds):
    return "%d:%02d:%05.2f" % (int(seconds // 3600), int(seconds % 3600 // 60), seconds % 60)


def describe_delay(frames, rate, tolerance=0):
    """Name a delay if it lands on a buffer in the capture path.

    `tolerance` is the measurement's own resolution: the envelope only locates a ghost to the
    nearest hop, so an exact-frame comparison would miss a real match by a rounding error.
    """
    ms = frames * 1000.0 / rate
    for name, size in KNOWN_BUFFERS:
        if abs(frames - size) <= tolerance:
            return "%.2f ms -- %s%s" % (ms, "" if tolerance and frames != size else "exactly ", name)
        # Only small multiples: 22 x a 6-frame packet is arithmetic, not evidence.
        for multiple in (2, 3, 4):
            if size > 1 and abs(frames - size * multiple) <= tolerance:
                return "%.2f ms -- %d x %s" % (ms, multiple, name)
    return "%.2f ms" % ms


def exact_duplicates(samples, rate):
    """Bit-exact repeats of a block of audio. Digital silence is skipped: it repeats legitimately."""
    seen = {}
    hits = []
    step = BLOCK // 2
    for start in range(0, len(samples) - BLOCK, step):
        block = tuple(samples[start:start + BLOCK])
        if not any(block):
            continue
        # A block has to actually contain signal; a near-silent passage can repeat by chance at
        # 24-bit once dither is absent.
        if max(abs(v) for v in block) < 1e-4:
            continue
        previous = seen.get(block)
        if previous is None:
            seen[block] = start
        else:
            hits.append((previous, start, start - previous))
    return hits


def envelope(samples, rate):
    """High-frequency energy per hop: a steep high pass, then the peak in each block.

    Three cascaded one-pole sections, not one. A single 6 dB/octave section leaves a bassline only
    ~20 dB down, and the residual ripples at the bass half-period -- which this detector then
    faithfully reports as a "fixed echo" at around 9 ms on completely clean material. Three
    sections put the same bass ~60 dB down and the false positive disappears, while the kick's
    attack, which is what the ear is calling an echo, passes untouched.
    """
    alpha = 0.9   # ~780 Hz corner per section
    stages = [0.0, 0.0, 0.0]
    hop = max(1, int(rate * ENVELOPE_HOP_MS / 1000.0))
    out = []
    peak = 0.0
    for index, value in enumerate(samples):
        high = value
        for s in range(len(stages)):
            stages[s] = alpha * stages[s] + (1.0 - alpha) * high
            high = high - stages[s]
        high = abs(high)
        if high > peak:
            peak = high
        if (index + 1) % hop == 0:
            out.append(peak)
            peak = 0.0
    return out, hop


def echo_delays(env, hop, rate):
    """For each HF transient, the delay to a genuine second peak behind it.

    The trap here, found by testing against material with no echo at all: a transient's own decay
    is still a large fraction of its peak a few milliseconds later, so simply taking the loudest
    point after the onset reports an "echo" at the search window's lower edge on every file,
    clean or not. A ghost is only a ghost if the sound died away first -- so the envelope must
    fall to a fraction of the peak, and only then rise back into a local maximum.
    """
    ordered = sorted(env)
    if not ordered:
        return [], 0.0
    median = ordered[len(ordered) // 2] or 1e-9
    loud = ordered[int(len(ordered) * 0.995)]
    hi = int(MAX_ECHO_MS / ENVELOPE_HOP_MS)
    decayed = 0.15   # "the original has finished"
    audible = 0.12   # a ghost quieter than this would not be described as an echo

    delays = []
    last_onset = -10 ** 9
    for i in range(3, len(env) - hi):
        # An onset: loud in absolute terms and a genuine jump, so we sit on attacks not sustains.
        if env[i] < loud or env[i] < 4.0 * median:
            continue
        if env[i] < 2.0 * max(env[i - 3:i]):
            continue
        if i - last_onset < hi:       # do not let one transient's tail seed the next search
            continue
        last_onset = i
        peak = env[i]

        dip = None
        for k in range(i + 1, i + hi):
            if env[k] < decayed * peak:
                dip = k
                break
        if dip is None:
            continue

        best = None
        for k in range(dip + 1, i + hi - 1):
            if env[k] < audible * peak or env[k] < 3.0 * median:
                continue
            if env[k] < env[k - 1] or env[k] < env[k + 1]:   # must be a local maximum
                continue
            if best is None or env[k] > env[best]:
                best = k
        if best is None:
            continue
        delays.append(int(round((best - i) * hop)))
    return delays, median


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    left, right, rate = load(sys.argv[1])
    if len(left) < rate:
        print("too short to analyse")
        return 1

    print("\n--- A. bit-exact repeated audio " + "-" * 44)
    hits = exact_duplicates(left, rate)
    if not hits:
        print("none. No block of audio appears twice byte-for-byte, so nothing in the capture")
        print("path is writing or reading a buffer more than once.")
    else:
        spacing = Counter(delay for _, _, delay in hits)
        print("%d repeated block(s) found. Real audio never repeats exactly, so this is a bug." % len(hits))
        print("\n  delay (frames)   count   meaning")
        for delay, count in spacing.most_common(8):
            print("  %14d   %5d   %s" % (delay, count, describe_delay(delay, rate)))
        print("\n  first few occurrences:")
        for original, copy, delay in hits[:6]:
            print("    %s copied to %s (delay %d frames)"
                  % (hms(original / float(rate)), hms(copy / float(rate)), delay))

    print("\n--- B. fixed-delay echo on transients " + "-" * 38)
    env, hop = envelope(left, rate)
    delays, _ = echo_delays(env, hop, rate)
    # A genuine fixed echo marks essentially every transient in the file. A handful of scattered
    # hits is ordinary programme material, and calling a verdict on them produced a confident
    # "echo at 8 ms" on test audio that provably had none.
    if len(delays) < 12:
        print("no consistent ghost behind HF transients (%d candidate(s)). If you can hear one," % len(delays))
        print("it is longer than %g ms or quieter than the detector's floor." % MAX_ECHO_MS)
    else:
        counts = Counter(delays)
        common, hits_at_common = counts.most_common(1)[0]
        concentration = hits_at_common / float(len(delays))
        print("%d transients carry a secondary HF peak." % len(delays))
        print("\n  delay (frames)   transients   meaning")
        for delay, count in counts.most_common(6):
            print("  %14d   %10d   %s" % (delay, count, describe_delay(delay, rate, hop)))
        if concentration >= 0.5:
            print("\nVERDICT: %.0f%% land on the same delay -- a fixed echo, not musical content."
                  % (concentration * 100))
            print("  %s" % describe_delay(common, rate, hop))
        else:
            print("\nVERDICT: the delays are spread out, which is what musical echo/reverb in the")
            print("         source material looks like rather than a buffer fault.")

    print("\n--- C. channel independence " + "-" * 48)
    if left is right:
        print("mono file; nothing to compare.")
    else:
        identical = sum(1 for a, b in zip(left, right) if a == b)
        share = identical / float(len(left))
        print("L and R are sample-identical %.2f%% of the time." % (share * 100))
        if share > 0.98:
            print("Both channels carry the same samples: the capture is effectively mono, which")
            print("means the channel pair or the demux stride is wrong.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

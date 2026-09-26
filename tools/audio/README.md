# Audio forensics tools

Standalone Python 3 scripts for diagnosing a recording made by Set Recorder. Standard library
only; each streams the file, so a full-length set does not need to fit in memory. None of them
modifies its input.

| Script | What it answers |
|---|---|
| `find_clicks.py rec.wav` | Are there clicks, and what spaces them? Checks first for short runs of exact digital zero (a padded USB packet), then for breaks in waveform continuity, and says whether the spacing points at the app, lost USB packets, or the music itself. |
| `find_echo.py rec.wav` | Is any audio repeated or echoed? Reports the delay in USB packets, URBs and encoder chunks so a hit names the buffer responsible. |
| `repair_zero_holes.py in.wav out.wav` | Removes padded-packet holes from a recording made before the capture fix. Lossless: the holes were inserted, not cut in. |

When the source track is available, the strongest test is to align the recording against it and
subtract: anything left over came from the capture path. That analysis is not scripted yet; see
the 2026-09-25 CHANGELOG entry for how it was done.

Keep test recordings and source tracks out of git; `*.wav` and `*.flac` are ignored.

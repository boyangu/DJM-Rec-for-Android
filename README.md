# Set Recorder

Record your DJ sets from a compatible USB mixer directly to your Android phone.

A fork of [P2GR/DJM-Rec-for-Android](https://github.com/P2GR/DJM-Rec-for-Android).

**[Download the latest release APK](https://github.com/boyangu/DJM-Rec-for-Android/releases/latest)**

Requires Android 10 or newer, a 64-bit ARM device with USB host support, and a USB data cable.
Choose the **release APK** for everyday use. The debug APK is for testing.

## Supported devices

| Device | Recording support |
| --- | --- |
| **DJM-A9, DJM-750MK2** | Hardware confirmed |
| DJM-900NXS2, DJM-450 | Implemented and reported working; the app still labels them experimental until re-verified |
| DJM-V10 | Profile matches the Linux driver tables (12-ch 24-bit, USB 1/2 … 11/12 routable to REC OUT); hardware test pending |
| DJM-V5 | USB IDs (2b73:0058-005b), REC OUT with/without mic options and the six-step USB level confirmed from AlphaTheta's own Setting Utility; wire format still unverified. Connect it, then open **Diagnostics** from the menu drawer (top-left) and use **Copy USB descriptors** and share the output |
| DJM-S11 | Experimental mixer profile; hardware testing needed |
| DDJ-FLX10 | Profile from a real descriptor dump (2b73:0041, 10-ch 24-bit in, 44.1 kHz, class-compliant UAC2); which USB pair carries the recording mix is still being confirmed |
| XDJ-XZ | Experimental USB capture; automatic master selection on USB 5/6 |
| XDJ-AZ, OPUS-QUAD, OMNIS-DUO | Experimental USB capture; automatic master selection on USB 1/2 |
| XDJ-RX3 | Recognized, but its documented USB connection has no recording input |
| Other USB audio interfaces | May work when they expose a compatible USB audio input |

Experimental support is not a guarantee. All-in-one units must expose a compatible USB audio
input to Android; some vendor-specific modes still need further work. RX/RX2/RR have no dedicated
profiles. For RX3, use onboard USB recording or an external USB audio interface connected to its
analog output.

## Start recording

1. Connect your mixer's rear **PC/Mac USB audio port** to your phone with a data cable (phone as
   USB host/OTG). A USB storage port or Link Export connection does not provide recording audio.
   The top-panel **MULTI I/O / mobile-device port** (DJM-A9, DJM-V5) is a USB *host* port made for
   iPhone/iPad running DJM-REC; Android cannot act as a USB audio device, so it will never work there.
2. Open Set Recorder and allow USB access. Tap the source name to choose an input if several are connected.
3. Play audio and check both meters. Automatic arming starts monitoring, not recording. The
   status reads "no signal" only after the input has stayed silent for the **Silence hold** time
   (Settings > Capture, 5 s by default), so it does not flicker during quiet passages.
4. Open **Recording setup** to choose WAV/FLAC, sample rate, gain and a USB channel pair. Software
   gain defaults to **0 dB**; on the DJM-A9 and DJM-V10 prefer the **Mixer USB recording level**
   control, which sets the mixer's own USB send level. Choosing a pair routes MIX/REC OUT to it on
   the mixer, and **Include microphone** picks REC OUT with or without the mic bus on models that
   offer both (A9, V5). Make a short test recording first.
5. In **Settings > Background recording**, allow unrestricted battery use once so a long set is not
   throttled with the screen off. In **Settings > During a set**, allow **Do Not Disturb access**
   so calls and notifications are silenced while recording and restored afterwards -- an incoming
   call takes audio focus and covers the transport controls. The prompt above the transport offers
   both, plus keeping the screen awake, and disappears once they are done.
   If the mixer is not recognised (for example a DJM-V5 with a product ID the app does not know
   yet), open **Recording setup > Mixer profile** and force the closest profile, or choose
   **Class-compliant only** to send no vendor commands. **Advanced USB format** lets you pick the
   wire channel count, sample container, capture endpoint, and toggle the silent playback
   keepalive and endpoint sample-rate command. Settings are stored per mixer and applied
   immediately; **Reset to auto** returns to detection. Check the meters and make a short test
   recording after any change.
6. Press **Record**, then **Save set** when finished. Find your files in **Sets** and `Music/DJMRec`.
   WAV is the safer choice for long sets: its header is checkpointed every few seconds and can be
   repaired after a crash; FLAC recovery is still limited.

## Features

- Recorder controls and setup access without scrolling the recording page.
- RGB waveform: red bass, green mids, blue highs, with blended colors and up to 60 fps scrolling.
  Waveform processing sleeps when the display is hidden.
- Stereo meters and clipping indication.
- WAV/FLAC recording, pause/resume and track markers.
- Saved-set search, playback, sharing, export, rename and deletion.
- Settings for automatic arming, waveform animation, screen wake and stop confirmation.
- Do Not Disturb for the length of a recording, restored afterwards; alarms still sound and a mode
  you set yourself is left untouched.
- Background recording with a persistent notification. **Save & close** saves and ends capture.

Keep USB connected during a set. Force-stop, reboot, cable loss and some Android battery/call
restrictions can interrupt recording. Track markers identify moments in a stereo recording;
independent multitrack recording is not implemented.

## Diagnostics and privacy

Pro DJ Link and USB protocol research are available only on the
[`experimental` branch](https://github.com/P2GR/DJM-Rec-for-Android/tree/experimental),
which builds a separate app. They are not included in main releases.

The app sends no telemetry. Nothing about your device, mixer or recordings leaves the phone
unless you export it yourself: **Diagnostics > Create and share report** builds a text report
(app logs, USB descriptors, capture statistics and current settings, never audio) and opens the
system share sheet so you choose where it goes. Please attach it, and identify your mixer, Android
version, cable/port and what happened, when reporting a problem.

Models without a readable route register (DJM-450, DJM-V10, DJM-S11) get the *selected* MIX/REC
OUT pair written once after the USB interface and sample rate are initialized, with silent
playback traffic keeping the duplex stream active (8 channels on the 450, 12 on the V10). AUTO uses
the model's default pair (USB 1/2 on the 450 and V10; USB 5/6 on the S11); a manual pair configures
its own MIX route. The report's `capture_setup` line shows `rate_set_result` (3 means accepted),
`route_value` (for example 266/522/778 for USB 1/2, 3/4, 5/6) and `route_set_result` (0 means
accepted). Negative results are USB errors; -999 means not attempted. An accepted route write is
not readback verification. `capture_health` confirms whether audio actually arrives. The vendor
register encodings follow the Linux `snd-usb-audio` DJM quirks (`sound/usb/mixer_quirks.c`).

If the first second after arming is silent on a model with a readable route register (A9,
900NXS2, 750MK2, V5), the app routes MIX to every configurable pair as a fallback. That fallback
now runs from the app's own USB control path on the health tick, never from the isochronous
capture thread.

## License

MIT. Bundled libraries retain their own licenses.

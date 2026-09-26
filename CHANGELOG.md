# Changelog

## v0.48.0 (2026-09-26)

- Log every lost USB packet. Each one writes a line with how many packets went, the USB status,
  the exact source frame (so it maps to a position in the file, from the "Recording starts at
  source frame" line), the time since the previous loss and how long the capture thread had gone
  between checks. The exported diagnostic report carries them.
- Keep the last 512 USB packet losses inside the capture itself and print them in the diagnostic
  report (`recent_misses=` in the native snapshot, with the recording's start frame beside it), so
  a whole set's losses survive; the shared logcat buffer held under a minute of them. The new
  `tools/audio/match_misses.py` reads that block against the WAV and reports the step at each one.
- Redesign the app to the SET REC design system: a dark, monochrome look where colour is kept
  for the signal and for recording.
  - Settings is one scrolling list of Android-style rows. Recording holds what applies to every
    set (file format, software gain, no-signal delay, confirm before saving). Mixer, titled with
    the connected device, holds what depends on it (sample rate, mixer recording level, include
    mixer mic, input channels, mixer profile, advanced USB format); rows a mixer cannot use, or
    that need one connected, stay in place greyed out with the reason. During a set, Display and
    About follow.
  - Every option now lives in Settings. The recorder's setup sheet is gone and its sliders button
    opens Settings.
  - The recorder has an input card (name, format and profile, rescan), a signal panel with the time
    of day, a REC indicator, the elapsed time and a Battery saver button, then the waveform, the
    meters and the transport (Record set; Pause or Resume and Stop & save; markers).
  - The waveform is now rekordbox-style three-band layers (blue lows, orange mids, cream highs)
    instead of an RGB blend. The meters have 48 segments, a clip light and a -60/-40/-20/-9/0
    scale; their ballistics are unchanged.
  - The drawer and top bar are restyled, and "My Recordings" is now "Library".
  - The "Set up your phone for a long set" checklist is replaced by the During a set rows, which show
    the Do Not Disturb and battery states directly. Its shortcut to Android's system Battery Saver,
    the "Save everything & close" button and the track marker help text are gone from Settings.
- Fix lost USB packets (heard as ticks) caused by the phone's power management. Measured on a
  Sony XQ-EC72 with a DDJ-FLX10: 0.96 lost packets a second with the screen on and 6.1 a second on
  the battery saver screen, all bus-level errors with the capture thread never late, so the SoC's
  deep idle states were the cause. While raw USB capture runs the app now holds a silent
  low-latency stream on the built-in speaker, which makes the audio HAL veto those states, as it
  does for AAudio recorders. Confirmed on the same setup: 219 s of capture, 2:47 of it on the
  battery saver screen, zero lost packets; switching the guard off in an earlier run brought them
  back at 3.7 a second. The stream is pinned to the speaker and is never fed unless Android
  confirms that route, so it cannot touch the mixer. Always on; the diagnostic report shows its
  state under "USB idle guard".
- Remove automatic diagnostics. The app no longer sends anything to Firebase Analytics or
  Crashlytics, and the Settings > Privacy "Send diagnostics" toggle is gone. The Firebase SDK,
  Gradle plugins and release-workflow steps are removed. Diagnostic data leaves the phone only
  when you export it from Diagnostics > Create and share report. USB connection stages and
  recording state changes are logged to logcat instead, so the exported report still has them.
- Remove dead code found in the architecture review: two unused composables
  (`TransportControls`, `DeviceStatusCard`), the unused path-based native recording entry point,
  an unused enum and eight unused strings, unused constants, an intent extra nothing sent, and a
  one-time preferences migration. The manifest's `<queries>` block and the ProGuard keep rule for
  the `usb` package go too (neither had a reason left). Lint's unused-resource check is back on.
- Fix bugs found in an architecture review:
  - Native: a new USB session could push audio into the previous session's waveform analyzer
    while it was being freed (the source streams during its rate probe, before the new buffers
    existed). Frames are now dropped until the session's buffers are in place, and closing the
    engine frees the analyzer.
  - Native: an invalid recording format from Kotlin could crash the writer; it is now rejected.
  - Native no longer undoes a MIX route write when the route register reads back stale. On
    models where that readback is not live, it was reverting the route Kotlin had just set.
  - Unplugging the mixer while a set was being saved closed the whole app once the save
    finished. It now ends in the normal "mixer disconnected" state.
  - A service restart stacked a second set of state collectors in the UI; they are now replaced,
    and the service binding is tracked from bindService itself.
  - The diagnostic report always showed the stereo pair as "Auto"; it now reads the mixer's
    actual setting.
  - The silence-detection hold time and reset now run on the thread that owns the detector.
  - Coming back to the app while monitoring no longer rescans USB, which could report the mixer
    as unplugged.
- Move the audio forensics scripts (`find_clicks.py`, `find_echo.py`, `repair_zero_holes.py`) from
  `scripts/` to `tools/audio/`, with a README. `scripts/` now holds build and dev scripts only.
- Debug builds run in the Android emulator on a PC. On an emulator with no USB device, a demo
  mixer stands in for the hardware and plays a synthetic 124 BPM loop through the real native
  audio path, so meters, waveform, recording and the battery saver screen all work without a
  mixer. Release builds and real phones never see it. `scripts/run-dev.ps1` builds, starts the
  emulator, installs and launches in one command.
- Add a battery saver screen for long sets. While recording with Set Recorder in front, the
  screen no longer sleeps, and after 30 seconds without a touch, or at once from the recorder's
  Battery saver button, it drops to a black screen: the time of day and a REC dot above the elapsed
  time (`hh:mm:ss`), and a faint "Battery saver mode" label. The dot blinks twice a second; while
  paused it stays grey and PAUSED shows under the timer. While the screen shows, the app does
  everything Android lets an app do to save power: brightness down to 2% for its own window, the
  display's slowest refresh rate, system bars hidden, one redraw every half second and nothing in
  between, and the service stops meter and waveform work. The block moves slightly every minute to
  avoid burn-in. Any tap returns to the live screen. The automatic dimming is on by default; toggle
  it under Settings > During a set. The Battery saver button works either way.
- Fix the clicks and ticks in DDJ-FLX10 recordings. The FLX10 inserts one extra USB packet of pure
  digital zero into the capture stream every ~0.12 s, which punches a 6-frame hole to silence into
  the waveform, 8 or so per second through a loud track. Measured against the source track: the
  audio either side of each hole runs on continuously, so nothing was missing, only added. The
  capture now withholds an all-zero packet that follows signal and discards it if the next packet
  carries signal again. Two zero packets in a row are real silence and are kept in full. The new
  `zero_packets_dropped` field on the `capture_timing` line counts how often it fires. The filter
  runs on every mixer profile, since any of them could do the same.
- Pace the silent playback keepalive the way the device asks. Every Pioneer DJ / AlphaTheta USB
  audio device is an implicit-feedback design: the capture stream is the clock, and the host is
  meant to send each playback packet with as many frames as it just received on capture. Linux
  does this for all of them; this app sent playback at a fixed nominal rate instead, feeding the
  device a clock that is not its own. It now mirrors the capture packet sizes, falling back to the
  nominal rate only until the first capture packets arrive. Applies to every profile that needs
  playback traffic (DJM-A9, DJM-900NXS2, DJM-750MK2, DDJ-FLX10 and others). A new
  `playback_pacing=` diagnostic line shows how many packets were mirrored. This is the suspected
  root cause of the FLX10's padding packets; `zero_packets_dropped` on a new recording will show
  whether it was.
- `tools/audio/find_clicks.py` now checks for these zero holes before anything else. Its musical-grid
  test had called them music: a hole every ~0.12 s lands on a sixteenth note at ~124 BPM.
- Add `tools/audio/repair_zero_holes.py`, which removes the holes from recordings made before this
  fix. Because the holes were inserted rather than cut in, the repair is lossless.

## v0.47.1 (2026-09-23)

- Instrument the capture and recording path so a support report alone can tell apart the ways a
  recording goes wrong. The new lines in the native snapshot:
  - `capture_timing=... drift_ms:N` -- audio produced against the wall clock that produced it.
    Near zero means every frame the mixer sent arrived exactly once. Negative means frames went
    missing, which is heard as clicks. Positive means frames arrived twice, which is heard as an
    echo or a doubled transient. Neither shows up in the packet counters, because packets the host
    never collected never existed; this is the only measurement that sees them.
  - `max_reap_gap_us` -- the longest stall of the USB event thread. Longer than the URB queue
    (~48 ms) and the controller ran out of buffers and stopped collecting audio.
  - `unaligned_packets` -- packets that were not a whole number of frames, so a lost packet could
    splice half-old and half-new bytes into one frame. Expected to stay 0 on the DDJ-FLX10.
  - `ring=capacity_bytes:.. high_water_bytes:..` -- how close the capture ring came to overrunning
    on every callback, not just the ones that already lost frames.
  - `encoder=lock_wait_max_us:..` -- the window in which nothing drained the ring. This is the
    number the v0.46.0 checkpoint fix was aimed at, so it is now measurable rather than inferred.
  - `checkpoint=count:.. max_us:.. last_us:..` -- how long the periodic flush to storage actually
    takes on this device.
  - `gain_db` -- the recording gain, since anything above 0 dB is a hard clamp with no limiter.
- Add `scripts/find_echo.py`, which finds repeated or echoed audio in a recorded WAV and reports
  the delay in USB packets, URBs and encoder chunks so a hit names the buffer responsible.

## v0.47.0 (2026-09-22)

- Silence calls and notifications for the length of a recording. An incoming call is the thing
  that actually ruins a set: Android hands audio focus to telephony and drops a full-screen call
  UI over the transport controls. Set Recorder now puts the phone into Do Not Disturb when
  recording starts and restores the previous setting when it stops, through every way a recording
  can end -- normal save, error, USB unplug or the service being killed. Alarms still sound, and a
  Do Not Disturb mode you turned on yourself is never replaced or cleared. Needs Do Not Disturb
  access, which the app asks for rather than assuming; until it is granted the feature does
  nothing. Toggle under Settings > During a set.
- Add a pre-flight prompt on the recorder screen, shown only before recording, for the three
  things that cost people sets: Do Not Disturb access, unrestricted battery use, and keeping the
  screen awake. One tap each, dismissible, and it disappears once there is nothing left to do.
- Group the screen-awake setting with Do Not Disturb under a new "During a set" heading instead of
  leaving it under Display, and say plainly that capture continues with the screen off -- it keeps
  the meters visible, it is not what keeps the recording alive.

## v0.46.0 (2026-09-22)

- Fix periodic clicks cut into recorded files. Every 5 s the recording checkpoint patched the
  WAV header and then called `fsync()` while still holding the writer lock, so the encoder thread
  was blocked for the whole flush. On a MediaStore descriptor that goes through FUSE and
  routinely costs 50-300 ms, during which capture kept filling the ring at the wire rate; when it
  overran, the dropped frames left a step in the waveform on exactly the checkpoint interval. The
  header patch still runs under the lock, but the flush to storage now runs outside it, the same
  way rolling to a new file part has always worked.
- Deepen the USB capture queue from 8 to 24 transfers (~16 ms to ~48 ms). A transfer is only
  re-armed after all of its packets have been decoded, so the queue is the entire margin against
  the capture thread being descheduled. Running out of queued transfers loses microframes that no
  counter can see -- the packets never reach the host at all -- and the recording silently closes
  up over the hole. Costs about 80 KB of buffers; capture latency does not matter to a recorder.
- Stop the AUTO channel pair from changing part-way through a recording. AUTO only picks a pair
  once a full second of audible signal has arrived, but capture goes live before that and reads
  channels 1-2 in the meantime, so a recording started immediately after connecting could begin
  on one pair and hard-cut to another a moment later -- a change of content, which is the most
  audible kind of click. The pick is now pinned when recording starts. If nothing audible has
  been seen yet it is left free, because a late correction beats a whole file on the wrong pair.
- Discard the partial frame held over from a packet that was lost in transit. Splicing the bytes
  either side of a gap fabricates one frame of half-old, half-new data, which decodes to a
  full-scale sample -- far louder than the gap itself. (No effect on the DDJ-FLX10, whose 30-byte
  frames divide its packets evenly so nothing is ever held over; it matters on 4-byte-subslot
  models.)
- Add `scripts/find_clicks.py`, which locates discontinuities in a recorded WAV and reports how
  far apart they are, so a periodic fault can be told from random packet loss.

## v0.45.2 (2026-09-22)

- Lock the app to portrait. It no longer rotates to landscape when the phone is tilted, which
  was easy to trigger while reaching for a mixer mid-set. The wide side-by-side layout is kept
  because Android ignores the orientation lock in multi-window, where the window can still be
  wider than it is tall.

## v0.45.1 (2026-09-22)

- Fix the L/R meters never showing amber or red. Two faults compounded: segment colour was taken
  from each block's left edge, so the topmost block was evaluated at 59/60 and a fraction of
  exactly 1.0 never occurred, making red unreachable at any level; and the thresholds were peak
  values (-6 and 0 dBFS) applied to a bar whose length follows RMS, which for real programme
  material sits near -18 dBFS. Zones are now -20 dBFS for amber and -9 dBFS for red.
- Fix the whole meter jumping whenever the dB readout changed. The readout box left only 22dp of
  content width, so "-60" wrapped onto a second line and re-measured the row. It is now a fixed
  30dp, single line, no wrap, right aligned, with tabular figures so every value is the same
  width. It also shows the held peak instead of the instantaneous one, which crossed several
  integers a second and was unreadable.

## v0.45.0 (2026-09-22)

- Fix the input status flickering between "INPUT LIVE" and "ARMED / NO SIGNAL" several times a
  second. The meter atomics were overwritten by every USB packet (about 0.125 ms of audio) and
  polled 66 ms apart, so the UI judged the signal from roughly 0.1% of what the mixer sent and
  saw the floor whenever a poll landed in a gap between beats.
- Native levels are now a max-since-last-read accumulator: every callback folds in, each poll
  drains it. Nothing between polls is missed, so transients always register.
- Add a **Silence hold** setting (Settings > Capture, default 5 s, also 1/2/10/30 s). Signal is
  still detected instantly; only the return to "no signal" waits for the input to stay below
  -60 dBFS for the whole window. One `SignalDetector` now feeds the recorder label, the health
  evaluator and the notification, which previously used three different thresholds (-55, -60
  and -50 dBFS) and could disagree at the same instant.
- Give the VU meter real digital peak-meter ballistics: instant attack, 20 dB/s release, and a
  peak-hold marker that sits for 1.5 s before falling. The bars advance on the display frame
  clock rather than the 15 Hz poll, so they glide instead of stair-stepping. The meter scale now
  ends at 0 dBFS, since the native meter clamps there and the old +3 dB red zone was unreachable.
- Give the RGB waveform a 250 ms release envelope, so audio stopping dead leaves a tapering tail
  instead of a one-column cliff. Band colours and timing are unchanged.

## v0.44.0 (2026-09-22)

- Rename the app to **Set Recorder** and replace the launcher icon with a mirrored blue/amber/cream
  waveform on black, including an Android 13+ themed (monochrome) layer the project lacked.
  Recordings still save to `Music/DJMRec`, so every existing set stays listed in Sets.
- Remove the Go Live / livestreaming feature entirely: the streaming package, both live screens,
  the YouTube broadcast coordinator and four tests. This also drops the RootEncoder and
  play-services-auth dependencies, the JitPack repository, the CAMERA permission and the camera
  foreground-service type, and the Twitch/Google OAuth build config.
- Remove the live PCM tap from the native engine. That frees a second ring buffer (~768 KB
  resident at 48 kHz) and removes per-buffer work from the realtime audio callbacks.
- Remove the bottom navigation bar; the side drawer is now the only navigation. Remove the
  "Buy me a coffee" drawer entry.
- Point the in-app updater at this fork and rename the release APK to `Set-Recorder-v*.apk`.
  Previously it offered upstream's APK, which is signed with a different key and cannot install.
- Pin `androidx.fragment` to 1.8.3. Firebase Analytics pulls in fragment 1.0.0 transitively, on
  which `registerForActivityResult` is broken; play-services-auth used to win that version
  conflict, so removing it with Go Live exposed the problem.

## v0.43.0 (2026-09-21)

- Add a DDJ-FLX10 profile (2b73:0041) from an on-device descriptor dump: 10-ch 24-bit UAC2
  capture on if2/alt1, 44.1 kHz fixed, silent 4-ch playback keepalive on if1/alt1 and the endpoint
  rate command; no vendor routing. Fix the manual keepalive / rate-command / format overrides never
  reaching the capture engine, and prefer a device's own advertised sample rate over the generic
  AlphaTheta template.
- Make the Diagnostics screen reachable (menu drawer). It existed but had no navigation entry,
  so the support report and USB descriptor export could not be opened in any build.
- Route the *selected* USB pair to MIX/REC OUT on models without a readable route register
  (DJM-V10, DJM-S11, DJM-450 unchanged): picking USB 5/6 on a V10 now records MIX on USB 5/6.
- Correct Pioneer route source tables against the Linux `snd-usb-audio` DJM quirks: 0x0a is
  REC OUT (with mic), 0x0e REC OUT without mic, 0x09 mic-only (never a MIX route). DJM-750MK2 no
  longer writes 0x0f ("None") and defaults to its factory REC OUT pair USB 9/10.
- Add **Include microphone** (A9, V5), **Mixer USB recording level** (A9, V10; vendor register
  0x8003, +15 dB … 0 dB) and a **Sample rate** picker to Recording setup. Software gain now
  defaults to 0 dB instead of +12 dB.
- Generic AlphaTheta fallback: an unknown or vendor-class-only mixer (e.g. a DJM-V5 with a
  different product ID) is captured with the shared 12-ch/24-bit template and clearly marked
  unverified instead of being refused. Any AlphaTheta device now launches the app on attach.
- Add **Diagnostics > Copy USB descriptors** for finishing mixer profiles from real hardware.
- Add a per-mixer **Mixer profile** override in Recording setup: force any built-in profile
  (e.g. treat an unrecognised mixer as a DJM-V5 or DJM-A9), choose "class-compliant only" to
  send no vendor commands, or hand-enter the USB wire format (channel count, sample container,
  capture endpoint) and toggle the silent playback keepalive and endpoint sample-rate command.
  Stored per mixer, applied by re-reading the device, no rebuild needed.
- DJM-V5: product IDs 0x0058-0x005B, the REC OUT with/without mic options and the six-step USB
  recording level are now confirmed against AlphaTheta's DJM-V5 Setting Utility 1.0.0; the
  mixer USB level control is offered for the V5 as well.
- Native capture: never issue vendor control transfers from the libusb event thread (the
  "route all pairs" fallback is handed to the app's USB connection); inspect transfer status so an
  unplugged mixer or a dead endpoint ends the session within one health tick; raise the capture
  thread to audio priority; decimate per-channel activity decoding; fix a data race in the
  playback keepalive pacing and a use-after-free window when stats/live PCM were read during
  close; claim a shared capture/playback interface once.
- Service: health, meter and notification polling survive transient states; the wake lock's
  timeout is renewed every tick; a refused foreground promotion after the encoder started no
  longer leaves an orphaned MediaStore row; dropped start Intents release their USB connection;
  free-space failures are treated as unknown rather than unlimited.
- Add a one-tap battery-optimization exemption in Settings (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- Remove the dead "Android audio stack" and "DJM-REC port" experiments (the MULTI I/O port is a
  USB host port for iPhone/iPad and cannot work with Android).
- Build: pin libusb to v1.0.29, replace deprecated `FetchContent_Populate`, and build/run the
  native unit tests in CI via CMake/ctest.

## v0.42.3 (2026-09-16)

- Correct DJM-450 MIX/REC OUT routing to honor the selected USB pair after interface and
  sample-rate initialization, without requiring unsupported route readback.
- Enable silent eight-channel playback keepalive for DJM-450 USB capture. Physical mixer
  validation is still required; the profile remains experimental.
- Report DJM-450 setup-command results and retain native startup failures in Firebase
  diagnostics. Restarting capture on the same connection now produces fresh health snapshots.
- Add regression coverage for selected-pair routing, duplex configuration and setup telemetry.
- Fix CI and release SDK setup by skipping the unavailable legacy `tools` package.

## v0.42.2 (2026-09-14)

- Add privacy-controlled Firebase Analytics events for non-crash app, mixer connection,
  recording, streaming and recovery diagnostics.
- Report mixer identity, selected USB format and channel pair, transfer health, active USB
  channels and playback keepalive state to diagnose unsupported or silent mixer captures.
- Keep telemetry disabled until the existing Automatic diagnostics setting is enabled, and
  exclude audio, filenames, credentials, serial numbers and raw USB descriptors.

## v0.42.1 (2026-09-09)

- Remove Pro DJ Link discovery, automatic metadata markers and now-playing banners from
  the main app. These features and USB protocol research belong exclusively to the
  `experimental` branch and its separate application package.
- Retain the USB capture cleanup and removal of obsolete root/ALSA paths.
- Provision the missing repository Firebase configuration required by signed release builds.
  The v0.42.0 release build failed before producing release APKs.

## v0.42.0 (2026-09-09)

- Add opt-in Ethernet Pro DJ Link discovery and track metadata for CDJ-2000NXS2 players,
  with a shared application API for deck status and now-playing information.
- Align automatic track markers with recorded audio and add customizable top/bottom
  now-playing banners to camera and artwork livestreams.
- Add network selection and USB descriptor checks. DJM-A9 computer USB-B track metadata
  remains unverified; this integration uses a separate Ethernet connection.
- Remove obsolete root/ALSA capture paths and their settings, retaining the Android USB
  capture pipeline and diagnostics.
- USB protocol probes and raw packet research tools remain in the separate experimental
  branch and app build. Live mixer/player validation is still required.

## v0.41.3 (2026-09-08)

- Show the installed app version in Settings and add a manual update check with clear current,
  available, download, and error states.
- Download the latest signed release APK from GitHub, verify its published SHA-256 checksum and
  package identity, then open Android's installer for user confirmation.

## v0.41.2 (2026-09-08)

- Fix stretched, square-looking portrait livestreams by keeping RootEncoder's video preparation
  as the single owner of encoder dimensions and camera rotation. Portrait output is now 720x1280.
- Let YouTube automatically detect ingest resolution and frame rate so 9:16 streams are recognized
  as vertical instead of being constrained by a fixed 720p landscape declaration.

## v0.41.1 (2026-09-08)

- Improve livestream setup UI with visible broadcast title input, clearer selected options,
  simpler YouTube setup, and automatic USB mixer arming from the Go live flow.
- Lock portrait livestream output to the selected orientation instead of allowing sensor
  auto-rotation to produce landscape frames with side bars.
- Rescan USB audio on Activity resume and from the input picker so detection and monitoring
  no longer depend on opening the Mixer USB section first.

## v0.41.0 (2026-09-08)

- Cap waveform drawing at 60 fps, reduce snapshots to about 30 Hz, and stop native
  waveform analysis/polling while hidden. Background meters update once per second;
  recording, USB keepalive, streaming, and safety checks remain active.
- Resolve USB rates from packet cadence instead of short wall-clock estimates. Never
  use uncertain measurements such as 99,271 Hz as a recording/encoder format.
- Convert high-rate mixer PCM to 44.1/48 kHz for streaming with anti-alias filtering.
  Report separate audio and camera preparation errors and lower artwork video to 15 fps.
- Replace the streaming form with Connect, Picture, and Go live steps, a fixed action
  button, mixer meters, and retryable memory-only credentials. Preserve planned YouTube
  broadcasts after pre-live failures and start authorization lifecycle from current state.
- Report silent selected channels accurately even when USB packets contain low-level noise.
- Real broadcast playback and measured battery savings still require device validation.

## v0.40.2 (2026-09-08)

- Stop repeating healthy Bugfender capture reports. Log connection/health changes,
  one settled snapshot, and initial payload/signal detection; retain fault reporting.
- Replace waveform morphing with cursor-based scrolling on the display frame clock.
  Preserve historical peaks, protect concurrent snapshots, and keep live history when
  recording starts. Waveform updates no longer recompose the full recording page.
- Use additive RGB shading: red bass, green mids, blue highs; mixed bands produce
  yellow, cyan, magenta, and white. Remove the flickering white waveform outline.
- Open active camera streams in a full recording workspace with local stereo meters,
  gain, stream/record timers, camera switching, framing guides, and confirmed stop.
  These controls and overlays are not included in the video sent to viewers.
- Give queued livestream PCM frames their own buffers and sample-based timestamps.
  Detect stalled PCM and outgoing AAC/H.264, clean up unexpected disconnections,
  and preserve the stream timer across reconnects.
- Add regression coverage for quiet diagnostics, RGB colors, waveform timing and
  concurrent history, PCM timestamps, and stalled media delivery.
- Device rendering performance and end-to-end camera broadcasts still need physical
  validation; livestreaming remains experimental.

## v0.40.1 (2026-09-08)

- Add detailed Bugfender mixer connection reports: detected model, USB IDs, selected
  profile, transport, routing defaults, interfaces, and endpoints.
- Report advertised channel counts, PCM formats, sample rates in kHz, and clock
  capabilities; distinguish profile defaults from descriptor and runtime evidence.
- Measure raw input activity per channel before gain and stereo selection, with
  approximate one-second peak windows, dBFS levels, and snapshot age.
- Correlate connection, permission, capture, and failure logs using a connection ID
  and code locations, including unknown devices to help diagnose future support.
- Bound recurring health/error reports and retain the existing diagnostics opt-out.
- Add channel-activity and diagnostic-report regression tests and contributor guidance.
- Generate GitHub release notes directly from this changelog.

## v0.40.0 (2026-09-08)

- Redesign the recording workspace with accessible input/setup controls, stereo
  meters, smoother three-band waveforms, and a layout for wider screens.
- Improve automatic arming, USB format selection, per-mixer channel preferences,
  recording saves/recovery, and the notification's Save & close action.
- Improve recorded-set search, playback, seeking, sharing, export, rename/delete,
  and track markers within stereo recordings.
- Add customization settings and improve experimental livestreaming and YouTube
  authorization/broadcast handling.
- Expand mixer profiles and all-in-one recognition with descriptor-based capture
  where supported. DJM-A9 and DJM-750MK2 remain the hardware-confirmed devices;
  other profiles require physical testing. Recognition alone does not confirm capture.
- Add automatic Bugfender diagnostics for debug and release builds, crash reporting,
  release mapping uploads, and an in-app opt-out. Audio payloads are not uploaded.
- Simplify the README with supported-device status and recording guidance.

## v0.36.6 (2026-08-22)

- Correct the input-meter scale so every dB label aligns with the measured peak position
- Add the installed DJM-S11 Windows driver-derived VID/PID profile (`2B73:0037`)
- Add the S11 vendor-class 14-channel playback / 10-channel capture contract and playback
  keepalive required by its clocking
- Route S11 MIX/REC OUT to USB 5/6 with the validated Pioneer vendor request

## v0.36.5 (2026-08-14)

- Update the live waveform independently at 50 fps for smoother visual response
- Keep meter, health, and notification polling on their existing schedules
- Stop waveform polling automatically when monitoring/recording ends

## v0.36.4 (2026-08-14)

- Add driver-derived DJM-V10 and DJM-450 capture profiles and Windows driver archives
- Keep DJM-A9 recording hardware-validated; mark other mixer profiles for physical testing
- Name normal recordings `mix_YYYYMMDD_HHmmss` without a misleading part suffix
- Retain part suffixes only for genuine WAV rollover files

## v0.36.2 (2026-07-24)

- Add RTMP/RTMPS livestreaming with direct DJM USB audio
- Add optional rear/front camera and custom artwork video modes
- Correct camera and preview rotation at startup and while device orientation changes
- Add persisted custom artwork selection with sampled preview; remove built-in artwork
- Add YouTube, Mixcloud, Twitch, TikTok, and custom RTMP destination setup
- Add Google authorization with automatic YouTube broadcast/RTMPS provisioning
- Map public Google OAuth client IDs to local and release build variants
- Show installed package and signing SHA-1 when Google OAuth registration is missing
- Start and complete YouTube broadcasts after confirming active RTMP ingest
- Add shareable YouTube watch links and broadcast lifecycle status
- Feed AAC with stable stereo PCM16 blocks and expose mixer-audio telemetry
- Fix black camera preview caused by stream startup clearing its pending SurfaceView
- Upgrade RootEncoder to 2.7.2 for monotonic A/V timestamps and GL lifecycle fixes
- Upgrade Android build tools for Kotlin 2.3-compatible release shrinking
- Upgrade Compose runtime and lint rules for Kotlin 2.3 metadata support
- Require sent AAC and H.264 packets before reporting a stream as live
- Report mixer PCM, AAC, camera, or H.264 startup failures directly in stream status
- Add Twitch device authorization, stream-key retrieval, and official ingest discovery
- Keep WAV/FLAC recording available while streaming
- Correct DJM-750MK2 capture framing to 12-channel packed 24-bit PCM

## v0.35.0 (2026-07-18)

- Add driver-derived DJM-V5, DJM-900NXS2, and DJM-750MK2 capture profiles
- Add release-safe USB descriptor, UAC topology, route protocol, and native session diagnostics
- Add read-only route probes when capture is idle and live transfer health verdicts
- Verify and restore mixer routes without changing unknown devices
- Keep recording formats focused on WAV and FLAC

## v0.34.1 (2026-07-17)

- Restore Gradle wrapper execution on Linux CI runners
- Configure stable release signing for installable GitHub APKs

## v0.34.0 (2026-07-17)

- USB isochronous capture via libusb (root-free FD handoff)
- DJM-A9 vendor control protocol for MIX routing
- Duplex playback activation (silent OUT stream to keep mixer clock alive)
- Multi-strategy fallback ladder for non-zero audio capture
- WAV/FLAC encoding
- Optional battery-saving live waveform setting
- Root USB assist for rooted devices (Type-C role forcing)
- In-app diagnostic log export
- GitHub Releases update checker

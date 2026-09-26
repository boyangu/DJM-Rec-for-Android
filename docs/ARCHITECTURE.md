# Architecture

Set Recorder records a DJ set from a Pioneer DJ / AlphaTheta mixer or all-in-one to an Android
phone over USB. It talks to the mixer directly through libusb (isochronous capture), because
Android's audio stack cannot select the channel pair these multichannel interfaces carry the
recording mix on.

This document describes the target the codebase is being refactored towards, and where each phase
stands. Update it in the same PR as any structural change.

## Target: Kotlin, four layers

Dependencies point down only. Nothing in a lower layer imports from a higher one.

```
ui/            Compose. Each screen takes a *ScreenState data class + an *Actions interface.
               Never sees a ViewModel, the Application or an object singleton.
   ↓
app/           Per-screen ViewModels. Map domain flows → ScreenState; forward actions.
               No SharedPreferences, no Intents.
   ↓
domain/        Pure Kotlin, no android.* imports, fully host-testable:
               RecordingSession (state machine)   CaptureSessionParams (one description of a session)
               CaptureFormatResolver              HealthSupervisor
               MixerProfiles (the only table)     RecordingFailure (typed errors)
   ↓
platform/      Android adapters, one job each:
               RecordingService (hosts a RecordingSession, makes the Android calls it asks for)
               RecordingWriter (MediaStore, crash journal, checkpoint, part roll)
               RecordingNotifications, WakeLockHolder, DoNotDisturbController
               UsbDeviceWatcher, UsbDescriptorReader, PioneerVendorControl, IsoConnectionHolder
               SettingsStore (sole owner of SharedPreferences)
               AudioEngine (JNI), diagnostics sinks
               AppGraph (constructs and wires everything; lives on the Application)
```

Dependency injection is a hand-rolled `AppGraph`: constructor injection everywhere, no framework.

## Target: native, one sink and pluggable sources

```
AudioSource (interface)      UsbIsoSource | DemoSource
        │  canonical stereo int32 frames
        ▼
FrameSink                    gain → meter → waveform → ring buffer
        ▼
EncoderThread → AudioWriter  (WAV | FLAC)

UsbAudioEngine               owns one AudioSource, the FrameSink and the encoder; JNI façade
```

Native does no policy. Kotlin reads descriptors, picks the profile, writes vendor routes and
chooses the rate, then hands native a fully resolved stream config. Native claims the interface,
streams, measures the real cadence and reports it back.

Only Pioneer DJ / AlphaTheta hardware is supported. The Android audio-stack (Oboe/AAudio) capture
path for generic stereo interfaces is being removed.

## Capture path today

```
UsbAudioManager (Kotlin)     attach/permission → descriptors → profile/format → vendor routes
        │  fd + ~20 fields as Intent extras
        ▼
RecordingService             foreground service, state, polling, health, files, notification
        │  AudioEngine.openUsbIso(22 args)
        ▼
UsbIsoAudioSource (C++)      claim, keepalive OUT stream (implicit feedback), URBs, demux,
                             zero-packet filter, AUTO pair pick
        ▼
UsbAudioEngine (C++)         meter, waveform, ring buffer, encoder thread, WAV/FLAC writer
```

## Refactor phases

| Phase | Scope | Status |
|---|---|---|
| 0 | Commit in-tree work, repo hygiene, this document, `scripts/host-tests.sh` | done |
| 1 | Bug fixes found in review (native use-after-free, `closeAfterSave`, rebind collectors, …) | done (2 deferred to phases 3–4) |
| 2 | Delete dead and deprecated code | done |
| 3 | `CaptureSessionParams`: one description of a session, one JNI call | |
| 4 | Split `RecordingService` (notifications, wake lock, writer, health supervisor) | |
| 5 | `RecordingSession` state machine with a real Saving state and typed failures | |
| 6 | `SettingsStore`, `AppGraph`, break the dependency cycles | |
| 7 | Native: vendor control to Kotlin, `AudioSource`/`FrameSink`, RAII, lock split, host tests | |
| 8 | Split `UsbAudioManager`; per-screen state, theme tokens, settings as data | |
| 9 | SDK 36, Variant API, dependency and toolchain bumps | |
| 10 | UI revamp (fonts, colours, settings design); planned separately | |

## Testing

- Kotlin unit tests: `./gradlew testDebugUnitTest`
- Native host tests: `scripts/host-tests.sh` (cmake + ctest when available, else g++ directly;
  on Windows run it through WSL)
- Emulator: `scripts/run-dev.ps1` (demo mixer stands in for USB hardware in debug builds)
- Recording forensics: `tools/audio/`

# Contributing to Set Recorder

## Setup

```bash
git clone https://github.com/P2GR/DJM-Rec-for-Android.git
cd DJM-Rec-for-Android
```

JDK 17, Android SDK, NDK 26.1, CMake 3.22.1 required. Open in Android Studio or build via CLI:

```bash
./gradlew assembleDebug
```

## Before submitting

- Lint must pass: `./gradlew lintDebug`
- Tests must pass: `./gradlew testDebugUnitTest`
- Keep ProGuard rules up to date if adding JNI or reflection-based code

## Code style

- Kotlin: official style (configured in `gradle.properties`)
- C++: C++17, `.clang-format` is project-standard
- Commit messages: [Conventional Commits](https://www.conventionalcommits.org/)

## Mixer diagnostics

In Firebase Crashlytics, use custom keys `mixer.name`, `mixer.profile`, `mixer.connection`,
`mixer.usb_id` and `mixer.connected`. `mixer.name` is a known profile name such as `DJM-A9`,
`Unknown` for an unprofiled USB audio device, or `None` without a mixer. Bounded custom logs use
`MixerConnection`, `MixerCapabilities`, `Mixer`, `UsbDescriptors` and `CaptureHealth` prefixes.
Connection ID links detection, permission, profile selection and failures.

Example format (illustrative values, not a hardware certification):

```text
connection=8f21ab90; stage=onDeviceAttached; source=UsbAudioManager.onDeviceAttached
Detected: DJM-A9; USB=2B73:003C; profile=DJM-A9
Selected total channels=12; PCM=24bit/3bytes; available rates=48.0 kHz, 96.0 kHz
sample_rate=requested:48000 opened:48000
channel_offset=requested:8 resolved:8
USB1=-120.0dBFS(below threshold) ... USB9=-6.0dBFS(active) USB10=-8.2dBFS(active)
```

Raw USB channel activity uses approximate one-second peak windows, before gain and stereo
extraction. `CaptureHealth` sends a snapshot on connection/health changes and one settled snapshot
after five seconds. Healthy sessions then stay quiet; payload summaries log only the first
window and first signal. Measurements continue without repeated uploads. Check
window age for stale data after a stall. Active means at least -60 dBFS; quieter audio may
still exist, and activity alone does not prove correct master routing. Android-managed input
provides its opened stream's stereo meters, not otherwise inaccessible mixer channels.

Advertised rates, profile contract rates, queried/Android-derived choices and actual opened
rates are labeled separately. Unknown values remain unknown. Unrecognized devices receive
interface/endpoint inventory logs; configuration dumps require USB permission. Logging does
not request extra permissions for arbitrary non-audio devices or probe new vendor controls.
Recorded audio and raw audio packet dumps remain excluded; the existing Settings opt-out applies.

For new profiles, collect the connection ID, descriptor chunks, selected format/rate/pair,
channel peaks and failure events. Test stereo separation and saved timing on physical hardware.

Native unit tests (header-only helpers: PCM decode, rate resolution, Pioneer route tables,
channel activity, waveform/gain math) build with any host C++17 compiler and run in CI:

```sh
cmake -S app/src/test/cpp -B build/native-tests
cmake --build build/native-tests
ctest --test-dir build/native-tests --output-on-failure
```

When touching a mixer profile, cross-check the wire format and route option codes against the
Linux kernel's `sound/usb/quirks-table.h` and `sound/usb/mixer_quirks.c` (snd_djm_* tables) and
cite the entry in the profile comment.

Production builds require an untracked `app/google-services.json`. For GitHub releases, store its
base64-encoded contents in the `GOOGLE_SERVICES_JSON_BASE64` repository secret. The release workflow
reconstructs the file only on the runner and uploads native symbols to Firebase Crashlytics.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.

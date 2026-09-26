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

## Running it without a mixer

Debug builds run in the Android emulator with a demo mixer standing in for the USB hardware.
`scripts/run-dev.ps1` builds, starts the emulator, installs and launches (see the script header
for the one-time SDK setup). The structure of the code and the refactor in progress are in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Before submitting

- Lint must pass: `./gradlew lintDebug`
- Tests must pass: `./gradlew testDebugUnitTest`
- Keep ProGuard rules up to date if adding JNI or reflection-based code

## Code style

- Kotlin: official style (configured in `gradle.properties`)
- C++: C++17, `.clang-format` is project-standard
- Commit messages: [Conventional Commits](https://www.conventionalcommits.org/)

## Mixer diagnostics

The app has no telemetry. Mixer evidence comes from the report users export themselves
(**Diagnostics > Create and share report**, built by `LogExporter`): USB enumeration, raw
descriptors and parsed topology, capture settings, transfer stats, the native pipeline snapshot and
this process's logcat. `UsbAudioManager` logs each connection stage (attach, permission,
inspection, failures) to logcat under the `UsbAudioManager` tag, so they appear in the report.

Native snapshot lines look like this (illustrative values, not a hardware certification):

```text
sample_rate=requested:48000 opened:48000
channel_offset=requested:8 resolved:8
USB1=-120.0dBFS(below threshold) ... USB9=-6.0dBFS(active) USB10=-8.2dBFS(active)
```

Raw USB channel activity uses approximate one-second peak windows, before gain and stereo
extraction. Check window age for stale data after a stall. Active means at least -60 dBFS; quieter audio may
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

or `scripts/host-tests.sh`, which does the same and falls back to plain g++ when cmake is missing
(on Windows: `wsl -- bash scripts/host-tests.sh`).

When touching a mixer profile, cross-check the wire format and route option codes against the
Linux kernel's `sound/usb/quirks-table.h` and `sound/usb/mixer_quirks.c` (snd_djm_* tables) and
cite the entry in the profile comment.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.

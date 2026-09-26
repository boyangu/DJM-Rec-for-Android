#!/usr/bin/env bash
# Build and run the native host tests (app/src/test/cpp).
#
# With cmake available this is exactly what CI runs. Without it (e.g. a bare WSL install with only
# g++), each test is compiled directly with the same include paths and extra sources.
#
#   scripts/host-tests.sh              from Linux/macOS/WSL
#   wsl -- bash scripts/host-tests.sh  from Windows
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/app/src/main/cpp"
tests="$root/app/src/test/cpp"

if command -v cmake >/dev/null 2>&1; then
    build="$root/build/native-tests"
    cmake -S "$tests" -B "$build" -DCMAKE_BUILD_TYPE=Debug >/dev/null
    cmake --build "$build" --parallel
    ctest --test-dir "$build" --output-on-failure
    exit 0
fi

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
flags=(-std=c++17 -Wall -Wextra -UNDEBUG -I"$src" -I"$tests/host_stubs" -pthread)
failed=0
# Test names come from CMakeLists.txt so this list never drifts from CI.
for name in $(grep -oE 'djmrec_add_test\(([A-Za-z]+)\)' "$tests/CMakeLists.txt" | sed -E 's/.*\((.*)\)/\1/'); do
    extra=()
    case "$name" in
        AudioSignalTest|WaveformSnapshotTest) extra=("$src/WaveformAnalyzer.cpp") ;;
        WavCheckpointTest) extra=("$src/writers/WavWriter.cpp") ;;
    esac
    if g++ "${flags[@]}" "$tests/$name.cpp" "${extra[@]}" -o "$out/$name" && "$out/$name"; then
        :
    else
        echo "FAILED: $name"
        failed=1
    fi
done
exit $failed

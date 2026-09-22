cmake_minimum_required(VERSION 3.16)
# Host-side unit tests for the header-only native helpers (PCM decode, sample-rate resolution,
# Pioneer route tables, channel activity, waveform/gain math). None of them needs libusb, Oboe
# or the NDK, so they build with any C++17 host compiler and run in CI via ctest:
#
#   cmake -S app/src/test/cpp -B build/native-tests
#   cmake --build build/native-tests
#   ctest --test-dir build/native-tests --output-on-failure
project(djmrec_native_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()

set(DJMREC_NATIVE_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp")

function(djmrec_add_test name)
    add_executable(${name} ${name}.cpp)
    target_include_directories(${name} PRIVATE "${DJMREC_NATIVE_SRC}")
    # assert()-based tests must not be compiled with NDEBUG.
    target_compile_options(${name} PRIVATE $<IF:$<CXX_COMPILER_ID:MSVC>,/UNDEBUG,-UNDEBUG>)
    add_test(NAME ${name} COMMAND ${name})
endfunction()

djmrec_add_test(PioneerMixerProfilesTest)
djmrec_add_test(UsbPcmFormatTest)
djmrec_add_test(UsbSampleRateTest)
djmrec_add_test(ChannelActivityTest)
djmrec_add_test(AudioSignalTest)
djmrec_add_test(WaveformSnapshotTest)
djmrec_add_test(MeterCalculatorTest)
# MeterCalculator.h takes an oboe::AudioFormat to select its decode loop. host_stubs supplies
# just that enum so the metering maths is testable off-device without the NDK.
target_include_directories(MeterCalculatorTest PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/host_stubs")
# WaveformAnalyzer.cpp is the only non-header dependency.
target_sources(AudioSignalTest PRIVATE "${DJMREC_NATIVE_SRC}/WaveformAnalyzer.cpp")
target_sources(WaveformSnapshotTest PRIVATE "${DJMREC_NATIVE_SRC}/WaveformAnalyzer.cpp")
find_package(Threads REQUIRED)
target_link_libraries(WaveformSnapshotTest PRIVATE Threads::Threads)

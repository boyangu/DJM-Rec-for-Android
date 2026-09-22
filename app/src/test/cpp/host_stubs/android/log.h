#pragma once
// Host-only stand-in for the NDK's <android/log.h>. The writers log through __android_log_print,
// which is the single thing keeping otherwise-portable container code from building off-device.
// Swallowing the calls lets the WAV header/checkpoint logic be unit-tested without the NDK.

enum android_LogPriority {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT,
};

inline int __android_log_print(int /*prio*/, const char* /*tag*/, const char* /*fmt*/, ...) {
    return 0;
}

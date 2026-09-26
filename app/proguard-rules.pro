# Keep Oboe/JNI native bridge entry points.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the JNI-facing class and its members.
-keep class com.audiopro.djmrec.audio.** { *; }

# Crashlytics 20.1 uses these API 37 classes only behind a runtime SDK check.
# Keep R8 builds compatible with our lower compile SDK until API 37 is installed.
-dontwarn android.os.ProfilingTrigger
-dontwarn android.os.ProfilingTrigger$Builder

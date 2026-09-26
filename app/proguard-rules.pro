# Keep Oboe/JNI native bridge entry points.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the JNI-facing class and its members.
-keep class com.audiopro.djmrec.audio.** { *; }

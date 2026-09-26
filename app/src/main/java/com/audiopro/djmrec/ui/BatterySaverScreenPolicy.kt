package com.audiopro.djmrec.ui

import java.util.Locale

/** How long the recorder screen must go untouched during a recording before it dims. */
const val SAVER_IDLE_MS = 30_000L

/**
 * Whether the battery saver screen should be showing.
 *
 * Only during a recording (paused counts: the set is still on), only while the app is the one in
 * front, and only once nobody has touched the screen for [SAVER_IDLE_MS]. Pure so each condition
 * is testable without an emulator.
 */
fun shouldShowSaver(
    enabled: Boolean,
    recordingActive: Boolean,
    appResumed: Boolean,
    idleMillis: Long,
): Boolean = enabled && recordingActive && appResumed && idleMillis >= SAVER_IDLE_MS

/** `mm:ss` below an hour, `h:mm:ss` from an hour up. */
fun saverElapsedText(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }
}

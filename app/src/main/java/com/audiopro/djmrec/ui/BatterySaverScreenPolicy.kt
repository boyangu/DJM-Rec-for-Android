package com.audiopro.djmrec.ui

/** How long the recorder screen must go untouched during a recording before it dims. */
const val SAVER_IDLE_MS = 30_000L

/**
 * Whether the battery saver screen should be showing.
 *
 * Only during a recording (paused counts: the set is still on) and only while the app is the one
 * in front. It then shows either because the user asked for it ([requested], the recorder's
 * Battery saver button, honoured even with the automatic dimming turned off) or because nobody has
 * touched the screen for [SAVER_IDLE_MS] with [enabled] on. Pure so each condition is testable
 * without an emulator.
 */
fun shouldShowSaver(
    enabled: Boolean,
    recordingActive: Boolean,
    appResumed: Boolean,
    idleMillis: Long,
    requested: Boolean = false,
): Boolean = recordingActive && appResumed && (requested || (enabled && idleMillis >= SAVER_IDLE_MS))

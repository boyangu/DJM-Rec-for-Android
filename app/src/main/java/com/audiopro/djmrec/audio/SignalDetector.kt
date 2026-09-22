package com.audiopro.djmrec.audio

/**
 * Decides whether the mixer is currently feeding us audible signal, with a configurable hold so
 * the answer does not flicker.
 *
 * Why this exists: the raw meter peak is a max over one polling interval, and real music is full
 * of moments below any sensible threshold -- zero crossings, gaps between kicks, breakdowns, the
 * quiet intro of a track. Reacting to those directly made the recorder flip between "INPUT LIVE"
 * and "ARMED / NO SIGNAL" several times a second. Three separate call sites each did their own
 * bare comparison against three different thresholds (-55, -60 and -50 dBFS), so they could even
 * disagree with each other at the same instant.
 *
 * The rule here is deliberately asymmetric:
 *  - **Onset is immediate.** The first sample above the threshold reports signal, so arming a
 *    mixer and hearing the meter move stays instant.
 *  - **Release waits [holdMs].** Signal is only considered lost once the input has stayed quiet
 *    for the whole hold window, which is what makes the readout stable through musical gaps.
 *
 * Callers drive this from their own clock ([update] takes the timestamp), so the hold is measured
 * in wall-clock milliseconds and is unaffected by how often the caller happens to poll. That
 * matters because the service slows its meter loop from ~15 Hz to 1 Hz when the UI is hidden.
 *
 * Not thread-safe: the service owns one instance and only touches it from its monitor thread.
 */
class SignalDetector(
    /** Peak dBFS above which the input counts as audible. */
    private val thresholdDb: Float = DEFAULT_THRESHOLD_DB,
    /** How long the input must stay below [thresholdDb] before signal is declared lost. */
    var holdMs: Long = DEFAULT_HOLD_MS
) {
    /** Timestamp of the most recent above-threshold reading, or 0 if there has never been one. */
    var lastSignalAtMs: Long = 0L
        private set

    /** Current verdict, so callers can read it without supplying a fresh sample. */
    var signalPresent: Boolean = false
        private set

    /**
     * Folds one meter reading in and returns the current verdict.
     *
     * @param nowMs a monotonic clock, e.g. `SystemClock.elapsedRealtime()`.
     * @param peakDb the loudest peak seen since the previous call.
     */
    fun update(nowMs: Long, peakDb: Float): Boolean {
        if (peakDb > thresholdDb) {
            lastSignalAtMs = nowMs
            signalPresent = true
            return true
        }
        // signalPresent starts false and is only ever set true by the branch above, so it
        // already encodes "have we ever heard anything". No separate sentinel is needed, and a
        // timestamp of 0 is perfectly legitimate so it must never be used as one.
        signalPresent = signalPresent && nowMs - lastSignalAtMs < holdMs
        return signalPresent
    }

    /** Forgets all history. Used when capture stops so a new session starts from "no signal". */
    fun reset() {
        lastSignalAtMs = 0L
        signalPresent = false
    }

    companion object {
        /** Matches the VU meter floor: anything at or below this is indistinguishable from silence. */
        const val DEFAULT_THRESHOLD_DB = -60f
        const val DEFAULT_HOLD_MS = 5_000L

        /** Values offered in Settings, in milliseconds. */
        val HOLD_CHOICES_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

        fun sanitizeHoldMs(value: Long): Long =
            if (value in HOLD_CHOICES_MS) value else DEFAULT_HOLD_MS
    }
}

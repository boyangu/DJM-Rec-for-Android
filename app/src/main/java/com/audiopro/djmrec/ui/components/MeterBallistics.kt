package com.audiopro.djmrec.ui.components

import kotlin.math.max

/**
 * Digital peak-meter ballistics: instant attack, exponential release, and a peak-hold marker.
 *
 * The native engine reports the true peak since the previous poll, but the UI only polls about
 * 15 times a second. Drawing those samples directly made the bar stair-step and snap to the floor
 * between hits. This advances a smoothed value on the display frame clock instead, so the meter
 * falls continuously between polls the way hardware meters do.
 *
 * All decay is computed from the elapsed nanoseconds handed to [update], never from a tick count,
 * so the behaviour is identical on a 60 Hz and a 144 Hz panel and is unaffected by dropped frames.
 *
 * Not thread-safe; each meter channel owns its own instance and only touches it from the UI frame
 * loop.
 */
internal class MeterBallistics(
    /** How fast the peak bar falls once the signal drops, in dB per second. */
    private val peakReleaseDbPerSec: Float = PEAK_RELEASE_DB_PER_SEC,
    /** How long the peak-hold marker sits at a new maximum before it starts falling. */
    private val holdMs: Long = HOLD_MS,
    /** Time constant for the RMS fill, gentler than the peak so the bar reads as a body. */
    private val rmsReleaseMs: Long = RMS_RELEASE_MS,
    private val floorDb: Float = FLOOR_DB
) {
    var peakDb: Float = floorDb
        private set

    /** The held marker: sits at the last maximum for [holdMs], then falls at the release rate. */
    var peakHoldDb: Float = floorDb
        private set

    var rmsDb: Float = floorDb
        private set

    // Explicit flag rather than `lastFrameNanos == 0L`: a frame time of 0 is legitimate,
    // and treating it as "unset" made every later frame re-seed instead of decaying.
    private var started = false
    private var lastFrameNanos = 0L
    private var holdUntilNanos = 0L

    /**
     * Advances the meter to [frameNanos].
     *
     * @param targetPeakDb loudest peak since the previous poll; attack is instant.
     * @param targetRmsDb RMS since the previous poll.
     */
    fun update(frameNanos: Long, targetPeakDb: Float, targetRmsDb: Float) {
        // First frame (or after a reset) establishes the time base without decaying anything.
        if (!started) {
            started = true
            lastFrameNanos = frameNanos
            peakDb = targetPeakDb
            rmsDb = targetRmsDb
            peakHoldDb = targetPeakDb
            holdUntilNanos = frameNanos + holdMs * NANOS_PER_MS
            return
        }
        // Clamp the step so returning from the background does not dump one huge decay in a
        // single frame; the meter simply resumes from where it was.
        val elapsedSec = ((frameNanos - lastFrameNanos).coerceIn(0L, MAX_STEP_NANOS)) / 1e9f
        lastFrameNanos = frameNanos

        peakDb = if (targetPeakDb >= peakDb) targetPeakDb
        else max(floorDb, peakDb - peakReleaseDbPerSec * elapsedSec)

        // Exponential approach for the RMS body: covers ~63% of the remaining distance per time
        // constant, which reads as a smooth settle rather than a linear ramp.
        rmsDb = if (targetRmsDb >= rmsDb) targetRmsDb
        else {
            val alpha = (elapsedSec / (rmsReleaseMs / 1000f)).coerceIn(0f, 1f)
            max(floorDb, rmsDb + (targetRmsDb - rmsDb) * alpha)
        }

        if (targetPeakDb >= peakHoldDb) {
            peakHoldDb = targetPeakDb
            holdUntilNanos = frameNanos + holdMs * NANOS_PER_MS
        } else if (frameNanos >= holdUntilNanos) {
            peakHoldDb = max(floorDb, peakHoldDb - peakReleaseDbPerSec * elapsedSec)
        }
    }

    /** Drops everything to the floor and forgets the time base, e.g. when capture stops. */
    fun reset() {
        peakDb = floorDb
        peakHoldDb = floorDb
        rmsDb = floorDb
        started = false
        lastFrameNanos = 0L
        holdUntilNanos = 0L
    }

    companion object {
        const val FLOOR_DB = -60f
        const val PEAK_RELEASE_DB_PER_SEC = 20f
        const val HOLD_MS = 1_500L
        const val RMS_RELEASE_MS = 300L
        private const val NANOS_PER_MS = 1_000_000L
        /** 250 ms: longer gaps (backgrounding, a stalled UI) resume instead of snapping down. */
        private const val MAX_STEP_NANOS = 250L * NANOS_PER_MS
    }
}

package com.audiopro.djmrec.domain

import com.audiopro.djmrec.audio.RecordingHealth
import com.audiopro.djmrec.audio.RecordingHealthEvaluator
import com.audiopro.djmrec.audio.RecordingHealthInput
import com.audiopro.djmrec.audio.RecordingHealthLevel

/** One health tick's raw readings, taken by the service from the engine and storage. */
class HealthSample(
    val recording: Boolean,
    val usbIso: Boolean,
    val streamOpen: Boolean,
    val freeBytes: Long,
    val remainingSeconds: Long,
    /** `AudioEngine.getUsbIsoTransferStats()`: cumulative counters since the stream opened. */
    val usbStats: LongArray,
    /** Cumulative ring-buffer under/overrun count. */
    val xRunCount: Int,
    val writerErrorCode: Int,
    val signalPresent: Boolean,
)

/** What to show, and the reason to stop recording now if there is one. */
data class HealthVerdict(val health: RecordingHealth, val safetyStopReason: String?)

/**
 * Turns the engine's cumulative counters into per-tick deltas, grades them with
 * [RecordingHealthEvaluator], and decides when a recording must be stopped to keep the file safe.
 * Not thread-safe: the service calls it from the monitor thread only.
 */
class HealthSupervisor {
    private var lastUsbStats = LongArray(STAT_COUNT)
    private var initialized = false
    private var stalledChecks = 0
    private var lastXRunCount = 0

    fun reset() {
        lastUsbStats = LongArray(STAT_COUNT)
        initialized = false
        stalledChecks = 0
        lastXRunCount = 0
    }

    fun evaluate(sample: HealthSample): HealthVerdict {
        val stats = sample.usbStats
        // The first tick has nothing to compare against: assume traffic rather than report a stall.
        fun delta(index: Int, first: Long) =
            if (initialized) stats.getOrElse(index) { 0 } - lastUsbStats.getOrElse(index) { 0 } else first
        val packetDelta = delta(STAT_COMPLETED, 1)
        val byteDelta = delta(STAT_BYTES, 1)
        val nonZeroDelta = delta(STAT_NON_ZERO_BYTES, 1)
        val missedDelta = delta(STAT_MISSED, 0)
        val resubmitDelta = delta(STAT_RESUBMIT_FAILURES, 0)
        val xRunDelta = (sample.xRunCount - lastXRunCount).coerceAtLeast(0)
        lastUsbStats = stats
        lastXRunCount = sample.xRunCount
        initialized = true

        val health = RecordingHealthEvaluator.evaluate(
            RecordingHealthInput(
                recording = sample.recording,
                usbIso = sample.usbIso,
                streamOpen = sample.streamOpen,
                freeBytes = sample.freeBytes,
                remainingSeconds = sample.remainingSeconds,
                packetDelta = packetDelta,
                byteDelta = byteDelta,
                nonZeroByteDelta = nonZeroDelta,
                missedPacketDelta = missedDelta,
                resubmitFailures = resubmitDelta,
                xRuns = xRunDelta,
                writerErrorCode = sample.writerErrorCode,
                signalPresent = sample.signalPresent
            )
        )

        stalledChecks = if (sample.usbIso && packetDelta <= 0) stalledChecks + 1 else 0
        val stopReason = if (!sample.recording) null else when {
            health.level == RecordingHealthLevel.ERROR -> health.message
            health.level == RecordingHealthLevel.LOW_STORAGE -> health.message
            stalledChecks >= MAX_STALLED_CHECKS -> "USB audio stopped. Recording finalized safely."
            else -> null
        }
        return HealthVerdict(health, stopReason)
    }

    companion object {
        /** Consecutive ticks with no USB packets before a recording is stopped. */
        const val MAX_STALLED_CHECKS = 3

        // Layout of AudioEngine.getUsbIsoTransferStats().
        private const val STAT_COUNT = 7
        private const val STAT_COMPLETED = 0
        private const val STAT_MISSED = 1
        private const val STAT_BYTES = 4
        private const val STAT_NON_ZERO_BYTES = 5
        private const val STAT_RESUBMIT_FAILURES = 6
    }
}

package com.audiopro.djmrec.domain

import com.audiopro.djmrec.audio.RecordingHealthLevel
import kotlin.test.*

class HealthSupervisorTest {
    private val gigabyte = 1L shl 30

    /** Cumulative stats: [completed, missed, empty, partial, bytes, nonZeroBytes, resubmitFailures]. */
    private fun stats(packets: Long, missed: Long = 0, bytes: Long = packets * 100, resubmits: Long = 0) =
        longArrayOf(packets, missed, 0, 0, bytes, bytes, resubmits)

    private fun sample(
        stats: LongArray,
        recording: Boolean = true,
        usbIso: Boolean = true,
        streamOpen: Boolean = true,
        freeBytes: Long = gigabyte,
        remainingSeconds: Long = 3_600,
        xRuns: Int = 0,
        writerError: Int = 0,
    ) = HealthSample(recording, usbIso, streamOpen, freeBytes, remainingSeconds, stats, xRuns, writerError, signalPresent = true)

    @Test fun firstTickAfterResetIsNotAStall() {
        val supervisor = HealthSupervisor()
        val verdict = supervisor.evaluate(sample(stats(0)))
        assertEquals(RecordingHealthLevel.GOOD, verdict.health.level)
        assertNull(verdict.safetyStopReason)
    }

    @Test fun stopsARecordingAfterThreeTicksWithoutUsbPackets() {
        val supervisor = HealthSupervisor()
        supervisor.evaluate(sample(stats(1_000)))
        repeat(HealthSupervisor.MAX_STALLED_CHECKS - 1) {
            val verdict = supervisor.evaluate(sample(stats(1_000)))
            assertEquals(RecordingHealthLevel.USB_UNSTABLE, verdict.health.level)
            assertNull(verdict.safetyStopReason)
        }
        assertNotNull(supervisor.evaluate(sample(stats(1_000))).safetyStopReason)
    }

    @Test fun neverStopsWhileOnlyMonitoring() {
        val supervisor = HealthSupervisor()
        repeat(5) { assertNull(supervisor.evaluate(sample(stats(1_000), recording = false)).safetyStopReason) }
        assertNull(supervisor.evaluate(sample(stats(1_000), recording = false, streamOpen = false)).safetyStopReason)
    }

    @Test fun packetsResumingClearsTheStallCount() {
        val supervisor = HealthSupervisor()
        supervisor.evaluate(sample(stats(1_000)))
        supervisor.evaluate(sample(stats(1_000)))
        supervisor.evaluate(sample(stats(1_000)))
        assertEquals(RecordingHealthLevel.GOOD, supervisor.evaluate(sample(stats(2_000))).health.level)
        assertNull(supervisor.evaluate(sample(stats(2_000))).safetyStopReason)
    }

    @Test fun reportsDeltasNotTotals() {
        val supervisor = HealthSupervisor()
        supervisor.evaluate(sample(stats(1_000, missed = 5), xRuns = 2))
        assertEquals(RecordingHealthLevel.GOOD, supervisor.evaluate(sample(stats(2_000, missed = 5), xRuns = 2)).health.level)
        assertEquals(RecordingHealthLevel.USB_UNSTABLE, supervisor.evaluate(sample(stats(3_000, missed = 6), xRuns = 2)).health.level)
        assertEquals(RecordingHealthLevel.USB_UNSTABLE, supervisor.evaluate(sample(stats(4_000, missed = 6), xRuns = 3)).health.level)
    }

    @Test fun writerErrorsAndLowStorageStopARecording() {
        val supervisor = HealthSupervisor()
        val writerFailed = supervisor.evaluate(sample(stats(1_000), writerError = 5))
        assertEquals(RecordingHealthLevel.ERROR, writerFailed.health.level)
        assertEquals(writerFailed.health.message, writerFailed.safetyStopReason)
        val lowStorage = supervisor.evaluate(sample(stats(2_000), freeBytes = 1_000))
        assertEquals(RecordingHealthLevel.LOW_STORAGE, lowStorage.health.level)
        assertNotNull(lowStorage.safetyStopReason)
    }

    @Test fun resetForgetsThePreviousSession() {
        val supervisor = HealthSupervisor()
        supervisor.evaluate(sample(stats(1_000)))
        supervisor.evaluate(sample(stats(1_000)))
        supervisor.evaluate(sample(stats(1_000)))
        supervisor.reset()
        // A new stream restarts its counters at zero; that is not a stall or a negative delta.
        val verdict = supervisor.evaluate(sample(stats(10)))
        assertEquals(RecordingHealthLevel.GOOD, verdict.health.level)
        assertNull(verdict.safetyStopReason)
    }
}

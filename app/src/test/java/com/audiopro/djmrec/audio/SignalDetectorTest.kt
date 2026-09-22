package com.audiopro.djmrec.audio

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalDetectorTest {

    @Test
    fun `onset is immediate`() {
        val detector = SignalDetector(holdMs = 5_000L)
        assertFalse(detector.update(0L, -70f), "silence before any signal")
        assertTrue(detector.update(10L, -20f), "the first audible sample reports signal at once")
    }

    @Test
    fun `signal is held across a gap shorter than the hold window`() {
        val detector = SignalDetector(holdMs = 5_000L)
        detector.update(0L, -10f)
        // A musical gap: everything below the floor for just under five seconds.
        assertTrue(detector.update(2_000L, -80f))
        assertTrue(detector.update(4_900L, -80f), "4.9 s of quiet is still within the hold")
    }

    @Test
    fun `signal drops once the hold window elapses`() {
        val detector = SignalDetector(holdMs = 5_000L)
        detector.update(0L, -10f)
        assertFalse(detector.update(5_000L, -80f), "exactly at the window the signal is gone")
        assertFalse(detector.update(9_000L, -80f))
    }

    @Test
    fun `a single audible sample re-arms the hold`() {
        val detector = SignalDetector(holdMs = 5_000L)
        detector.update(0L, -10f)
        detector.update(4_000L, -80f)
        detector.update(4_500L, -5f)              // one hit restarts the clock
        assertTrue(detector.update(9_000L, -80f), "4.5 s after the new hit, still holding")
        assertFalse(detector.update(9_600L, -80f), "5.1 s after the new hit, released")
    }

    @Test
    fun `threshold boundary is exclusive`() {
        val detector = SignalDetector(thresholdDb = -60f, holdMs = 1_000L)
        assertFalse(detector.update(0L, -60f), "exactly at the floor counts as silence")
        assertTrue(detector.update(10L, -59.9f))
    }

    @Test
    fun `shortening the hold applies to the gap already in progress`() {
        val detector = SignalDetector(holdMs = 30_000L)
        detector.update(0L, -10f)
        assertTrue(detector.update(3_000L, -80f))
        detector.holdMs = 1_000L                  // user picks a shorter hold mid-gap
        assertFalse(detector.update(3_100L, -80f), "the new, shorter window is already exceeded")
    }

    @Test
    fun `reset returns to no signal`() {
        val detector = SignalDetector(holdMs = 5_000L)
        detector.update(0L, -10f)
        assertTrue(detector.signalPresent)
        detector.reset()
        assertFalse(detector.signalPresent)
        // After a reset the hold must not resurrect the old timestamp.
        assertFalse(detector.update(100L, -80f))
    }

    @Test
    fun `hold choices round-trip and garbage falls back to the default`() {
        SignalDetector.HOLD_CHOICES_MS.forEach {
            kotlin.test.assertEquals(it, SignalDetector.sanitizeHoldMs(it))
        }
        kotlin.test.assertEquals(SignalDetector.DEFAULT_HOLD_MS, SignalDetector.sanitizeHoldMs(1234L))
        kotlin.test.assertEquals(SignalDetector.DEFAULT_HOLD_MS, SignalDetector.sanitizeHoldMs(-1L))
    }
}

package com.audiopro.djmrec.ui.components

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeterBallisticsTest {
    private val floor = MeterBallistics.FLOOR_DB
    private fun ms(value: Long) = value * 1_000_000L

    /** Feeds [durationMs] of a constant target at [hz], starting from [startNanos]. */
    private fun run(
        ballistics: MeterBallistics,
        startNanos: Long,
        durationMs: Long,
        hz: Int,
        peak: Float,
        rms: Float = floor
    ): Long {
        val step = 1_000_000_000L / hz
        var t = startNanos
        val end = startNanos + ms(durationMs)
        while (t <= end) {
            ballistics.update(t, peak, rms)
            t += step
        }
        return t - step
    }

    @Test
    fun `attack is instant`() {
        val b = MeterBallistics()
        b.update(0L, floor, floor)
        b.update(ms(16), -3f, -6f)
        assertEquals(-3f, b.peakDb, 0.01f, "a loud sample is shown on the very next frame")
        assertEquals(-3f, b.peakHoldDb, 0.01f)
    }

    @Test
    fun `peak releases at the configured rate`() {
        val b = MeterBallistics()
        b.update(0L, 0f, floor)                       // establish the time base at 0 dBFS
        val t = run(b, ms(16), 1_000, 60, floor)      // then one second of silence
        // 20 dB/s release: one second later the bar should read about -20 dBFS.
        assertTrue(abs(b.peakDb - (-20f)) < 1.5f, "expected about -20 dB, got ${b.peakDb}")
        assertTrue(t > 0)
    }

    @Test
    fun `peak hold marker waits then falls`() {
        val b = MeterBallistics()
        b.update(0L, 0f, floor)
        run(b, ms(16), 1_400, 60, floor)
        assertEquals(0f, b.peakHoldDb, 0.01f, "still held 1.4 s after the hit")
        run(b, ms(1_420), 300, 60, floor)
        assertTrue(b.peakHoldDb < -1f, "past the 1.5 s hold it has started falling: ${b.peakHoldDb}")
    }

    @Test
    fun `rms settles within its time constant`() {
        val b = MeterBallistics()
        b.update(0L, floor, 0f)                       // start with RMS at full scale
        run(b, ms(16), 300, 60, floor, floor)
        // One 300 ms time constant covers ~63% of the 60 dB drop, i.e. well past -30 dB.
        assertTrue(b.rmsDb < -30f, "expected RMS well below -30 dB, got ${b.rmsDb}")
        assertTrue(b.rmsDb >= floor)
    }

    @Test
    fun `decay is frame-rate independent`() {
        val slow = MeterBallistics()
        val fast = MeterBallistics()
        slow.update(0L, 0f, 0f)
        fast.update(0L, 0f, 0f)
        run(slow, ms(16), 800, 60, floor, floor)
        run(fast, ms(16), 800, 144, floor, floor)
        // Same wall-clock elapsed, so both must land in the same place regardless of refresh rate.
        assertTrue(abs(slow.peakDb - fast.peakDb) < 1.0f,
            "60 Hz gave ${slow.peakDb}, 144 Hz gave ${fast.peakDb}")
    }

    @Test
    fun `never falls below the floor`() {
        val b = MeterBallistics()
        b.update(0L, 0f, 0f)
        run(b, ms(16), 10_000, 60, floor, floor)
        assertEquals(floor, b.peakDb, 0.01f)
        assertEquals(floor, b.peakHoldDb, 0.01f)
        assertEquals(floor, b.rmsDb, 0.01f)
    }

    @Test
    fun `a long stall resumes instead of collapsing in one frame`() {
        val b = MeterBallistics()
        b.update(0L, 0f, 0f)
        // Backgrounded for ten seconds, then one frame arrives: the step is clamped to 250 ms,
        // so the meter picks up where it left off rather than snapping to the floor.
        b.update(ms(10_000), floor, floor)
        assertTrue(b.peakDb > -10f, "expected a clamped step, got ${b.peakDb}")
    }

    @Test
    fun `reset drops everything to the floor`() {
        val b = MeterBallistics()
        b.update(0L, 0f, 0f)
        b.reset()
        assertEquals(floor, b.peakDb, 0.01f)
        assertEquals(floor, b.peakHoldDb, 0.01f)
        assertEquals(floor, b.rmsDb, 0.01f)
        // The time base is forgotten too, so the next frame re-establishes it without decaying.
        b.update(ms(99_999), -12f, -18f)
        assertEquals(-12f, b.peakDb, 0.01f)
    }
}

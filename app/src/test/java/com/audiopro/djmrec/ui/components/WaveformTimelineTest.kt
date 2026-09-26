package com.audiopro.djmrec.ui.components

import org.junit.Assert.*
import org.junit.Test

class WaveformTimelineTest {
    private fun snapshot(cursor: Int) = FloatArray(2050).apply { this[2048] = cursor.toFloat(); this[2049] = 6f }

    @Test fun loudestBandFillsTheEnvelopeAndOthersScale() {
        val out = FloatArray(3)
        bandHeights(0.25f, 0.4f, 0.1f, 0f, out)
        assertEquals(0.5f, out[0], 0.0001f) // sqrt(0.25), the loudest band
        assertTrue(out[1] > 0f && out[1] < out[0])
        assertEquals(0f, out[2], 0f)
    }

    @Test fun silenceAndBadInputDrawNothing() {
        val out = floatArrayOf(1f, 1f, 1f)
        bandHeights(0f, 0.5f, 0.5f, 0.5f, out)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), out, 0f)
        out.fill(1f)
        bandHeights(Float.NaN, Float.NaN, -1f, 0f, out)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), out, 0f)
    }

    @Test fun snapshotsTranslateWithoutMorphingHistory() {
        val timeline = WaveformTimeline()
        val first = snapshot(100)
        first[400] = 0.8f
        timeline.accept(first)
        assertEquals(8f, timeline.lag(1_000_000, true), 0.001f)
        assertEquals(5f, timeline.lag(19_000_000, true), 0.001f)
        timeline.accept(snapshot(103))
        assertEquals(8f, timeline.lag(19_000_000, true), 0.001f)
        assertEquals(0.8f, first[400], 0f)
        assertEquals(0f, timeline.lag(1_000_000_000, true), 0f)
        assertEquals(0f, timeline.lag(2_000_000_000, true), 0f)
    }

    @Test fun cursorWrapResetAndDisabledSmoothingStayBounded() {
        val timeline = WaveformTimeline()
        timeline.accept(snapshot(1048574))
        timeline.lag(1_000_000, true)
        timeline.accept(snapshot(1))
        assertEquals(8f, timeline.lag(19_000_000, true), 0.001f)
        timeline.accept(snapshot(0))
        assertEquals(8f, timeline.lag(20_000_000, true), 0.001f)
        assertEquals(0f, timeline.lag(30_000_000, false), 0f)
        timeline.accept(FloatArray(0))
        assertTrue(timeline.bins.isEmpty())
    }
}

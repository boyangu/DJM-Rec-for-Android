package com.audiopro.djmrec.ui.components

import com.audiopro.djmrec.ui.theme.MeterAmber
import com.audiopro.djmrec.ui.theme.MeterGreen
import com.audiopro.djmrec.ui.theme.MeterRed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the meter's dB scale and colour zones.
 *
 * These shipped broken and no test noticed: the colour was chosen from each segment's *left*
 * edge, so the topmost segment was evaluated at 59/60 and a fraction of exactly 1.0 never
 * occurred. Red was therefore unreachable at any input level. The thresholds were also peak
 * values applied to an RMS-driven bar, so amber needed a level real music never reaches.
 */
class VuMeterScaleTest {

    private fun zonesAcrossBar() = (0 until METER_SEGMENT_COUNT)
        .map { colorForFraction(meterSegmentFraction(it)) }

    @Test
    fun `every colour zone is reachable somewhere on the bar`() {
        val zones = zonesAcrossBar().toSet()
        assertTrue(MeterGreen in zones, "no green segment")
        assertTrue(MeterAmber in zones, "no amber segment")
        assertTrue(MeterRed in zones, "no red segment on any segment of the bar")
    }

    @Test
    fun `the topmost segment is red`() {
        // Full scale must light the red zone; this is the exact case the left-edge bug missed.
        assertEquals(MeterRed, colorForFraction(meterSegmentFraction(METER_SEGMENT_COUNT - 1)))
        assertEquals(MeterRed, colorForFraction(1f))
    }

    @Test
    fun `zones run green then amber then red, never back`() {
        val order = listOf(MeterGreen, MeterAmber, MeterRed)
        var highest = 0
        zonesAcrossBar().forEach { color ->
            val rank = order.indexOf(color)
            assertTrue(rank >= 0, "unexpected colour $color")
            assertTrue(rank >= highest, "zones went backwards at rank $rank")
            highest = rank
        }
        assertEquals(order.size - 1, highest, "the bar never reaches the top zone")
    }

    @Test
    fun `realistic programme levels light amber rather than staying green`() {
        // Music sits around -18 to -12 dBFS RMS; that has to be visibly amber, which is the
        // user-visible half of the bug: at the old -6 dB threshold the bar was always green.
        assertEquals(MeterAmber, colorForFraction(dbToFraction(-18f)))
        assertEquals(MeterAmber, colorForFraction(dbToFraction(-12f)))
        assertEquals(MeterGreen, colorForFraction(dbToFraction(-30f)))
        assertEquals(MeterRed, colorForFraction(dbToFraction(-6f)))
    }

    @Test
    fun `the dB scale maps the full range onto the bar`() {
        assertEquals(0f, dbToFraction(METER_FLOOR_DB), 0.0001f)
        assertEquals(1f, dbToFraction(METER_CEILING_DB), 0.0001f)
        assertEquals(0.5f, dbToFraction(-30f), 0.0001f)
        // Out-of-range input is clamped, never drawn off the end of the track.
        assertEquals(0f, dbToFraction(-120f), 0.0001f)
        assertEquals(1f, dbToFraction(12f), 0.0001f)
    }

    @Test
    fun `zone thresholds sit inside the displayable range`() {
        // A threshold above the ceiling would be unreachable by construction.
        assertTrue(METER_RED_DB < METER_CEILING_DB, "red threshold is at or above the ceiling")
        assertTrue(METER_AMBER_DB < METER_RED_DB)
        assertTrue(METER_AMBER_DB > METER_FLOOR_DB)
    }
}

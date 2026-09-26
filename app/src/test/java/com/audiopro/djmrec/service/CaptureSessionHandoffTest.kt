package com.audiopro.djmrec.service

import com.audiopro.djmrec.domain.CaptureSessionParams
import com.audiopro.djmrec.domain.CaptureSource
import kotlin.test.*

class CaptureSessionHandoffTest {
    private val params = CaptureSessionParams("Demo Mixer", 48_000, 24, CaptureSource.Demo)

    @Test fun paramsCanBeTakenExactlyOnce() {
        val handoff = CaptureSessionHandoff()
        val id = handoff.offer(params)
        assertEquals(params, handoff.take(id))
        assertNull(handoff.take(id))
    }

    @Test fun unknownAndDroppedIdsYieldNothing() {
        val handoff = CaptureSessionHandoff()
        assertNull(handoff.take(CaptureSessionHandoff.NO_ID))
        assertNull(handoff.take(99))
        val id = handoff.offer(params)
        handoff.drop(id)
        assertNull(handoff.take(id))
    }

    @Test fun eachOfferGetsItsOwnIdNeverNoId() {
        val handoff = CaptureSessionHandoff()
        val other = params.copy(deviceLabel = "DJM-A9")
        val first = handoff.offer(params)
        val second = handoff.offer(other)
        assertNotEquals(first, second)
        assertNotEquals(CaptureSessionHandoff.NO_ID, first)
        assertEquals(other, handoff.take(second))
        assertEquals(params, handoff.take(first))
    }
}

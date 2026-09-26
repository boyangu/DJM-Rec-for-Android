package com.audiopro.djmrec.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatterySaverScreenPolicyTest {

    @Test
    fun `elapsed time is always hh mm ss`() {
        assertEquals("00:00:00", elapsedText(0))
        assertEquals("00:12:48", elapsedText((12 * 60 + 48) * 1000L + 999))
        assertEquals("01:12:48", elapsedText((3600 + 12 * 60 + 48) * 1000L))
        assertEquals("10:02:03", elapsedText((10 * 3600 + 2 * 60 + 3) * 1000L))
    }

    @Test
    fun `negative elapsed time shows zero`() {
        assertEquals("00:00:00", elapsedText(-5_000))
    }

    @Test
    fun `saver shows after the idle time only when every condition holds`() {
        assertTrue(shouldShowSaver(true, true, true, SAVER_IDLE_MS))
        assertFalse(shouldShowSaver(false, true, true, SAVER_IDLE_MS), "setting off")
        assertFalse(shouldShowSaver(true, false, true, SAVER_IDLE_MS), "not recording")
        assertFalse(shouldShowSaver(true, true, false, SAVER_IDLE_MS), "app not in front")
        assertFalse(shouldShowSaver(true, true, true, SAVER_IDLE_MS - 1), "touched recently")
    }

    @Test
    fun `a request shows the saver at once, even with the setting off`() {
        assertTrue(shouldShowSaver(false, true, true, 0, requested = true))
        assertFalse(shouldShowSaver(true, false, true, 0, requested = true), "not recording")
        assertFalse(shouldShowSaver(true, true, false, 0, requested = true), "app not in front")
    }
}

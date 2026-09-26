package com.audiopro.djmrec.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatterySaverScreenPolicyTest {

    @Test
    fun `elapsed time is mm ss below an hour`() {
        assertEquals("00:00", saverElapsedText(0))
        assertEquals("00:59", saverElapsedText(59_999))
        assertEquals("01:00", saverElapsedText(60_000))
        assertEquals("59:59", saverElapsedText(3_599_999))
    }

    @Test
    fun `elapsed time is h mm ss from an hour up`() {
        assertEquals("1:00:00", saverElapsedText(3_600_000))
        assertEquals("10:02:03", saverElapsedText((10 * 3600 + 2 * 60 + 3) * 1000L))
    }

    @Test
    fun `negative elapsed time shows zero`() {
        assertEquals("00:00", saverElapsedText(-5_000))
    }

    @Test
    fun `saver shows only when every condition holds`() {
        assertTrue(shouldShowSaver(true, true, true, SAVER_IDLE_MS))
        assertFalse(shouldShowSaver(false, true, true, SAVER_IDLE_MS), "setting off")
        assertFalse(shouldShowSaver(true, false, true, SAVER_IDLE_MS), "not recording")
        assertFalse(shouldShowSaver(true, true, false, SAVER_IDLE_MS), "app not in front")
        assertFalse(shouldShowSaver(true, true, true, SAVER_IDLE_MS - 1), "touched recently")
    }
}

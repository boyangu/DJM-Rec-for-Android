package com.audiopro.djmrec.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PioneerMixerProfileTest {
    @Test
    fun `S11 uses separate interfaces and routes only the record pair`() {
        val profile = PioneerMixerProfile.find(0x2B73, 0x0037)!!
        assertEquals(2, profile.vendorCaptureInterface)
        assertEquals(1, profile.playbackInterface)
        assertEquals(10, profile.vendorCaptureChannelCount)
        assertEquals(listOf(48_000), profile.vendorCaptureSampleRates)
        assertEquals(4, profile.defaultCaptureChannelOffset)
        assertEquals(listOf(-1, -1, 0x0A), profile.mixWithMicSources)
        assertEquals(-1, profile.mixRouteValue(0, includeMic = true))
        assertEquals(0x030A, profile.mixRouteValue(2, includeMic = true))
        assertEquals(false, profile.isHardwareConfirmed)
    }

    @Test
    fun `recognizes all supported mixer product ids`() {
        val vendor = PioneerMixerProfile.ALPHATHETA_VENDOR_ID

        assertEquals(PioneerMixerProfile.DJM_A9, PioneerMixerProfile.find(vendor, 0x003C))
        assertEquals(PioneerMixerProfile.DJM_V10, PioneerMixerProfile.find(vendor, 0x0034))
        assertEquals(PioneerMixerProfile.DJM_900NXS2, PioneerMixerProfile.find(vendor, 0x000A))
        assertEquals(PioneerMixerProfile.DJM_750MK2, PioneerMixerProfile.find(vendor, 0x001B))
        assertEquals(PioneerMixerProfile.DJM_450, PioneerMixerProfile.find(vendor, 0x0013))
        listOf(0x0058, 0x0059, 0x005A, 0x005B).forEach { productId ->
            assertEquals(PioneerMixerProfile.DJM_V5, PioneerMixerProfile.find(vendor, productId))
        }
    }

    @Test
    fun `rejects unknown products and vendors`() {
        assertNull(PioneerMixerProfile.find(PioneerMixerProfile.ALPHATHETA_VENDOR_ID, 0xFFFF))
        assertNull(PioneerMixerProfile.find(0x08E4, 0x003C))
    }

    @Test
    fun `uses driver-derived default capture pairs`() {
        assertEquals(8, PioneerMixerProfile.DJM_A9.defaultCaptureChannelOffset)
        assertEquals(0, PioneerMixerProfile.DJM_V10.defaultCaptureChannelOffset)
        assertEquals(0, PioneerMixerProfile.DJM_V5.defaultCaptureChannelOffset)
        assertEquals(0, PioneerMixerProfile.DJM_900NXS2.defaultCaptureChannelOffset)
        // Factory REC OUT pair on the 750MK2 is USB 9/10 (kernel snd_djm_ctls_750mk2 default).
        assertEquals(8, PioneerMixerProfile.DJM_750MK2.defaultCaptureChannelOffset)
        assertEquals(0, PioneerMixerProfile.DJM_450.defaultCaptureChannelOffset)
    }

    @Test
    fun `uses mixer multichannel wire formats`() {
        assertEquals(12, PioneerMixerProfile.DJM_V10.vendorCaptureChannelCount)
        assertEquals(3, PioneerMixerProfile.DJM_V10.vendorCaptureSubframeSize)
        assertEquals(24, PioneerMixerProfile.DJM_V10.vendorCaptureBitResolution)
        assertEquals(listOf(44_100, 48_000, 96_000), PioneerMixerProfile.DJM_V10.vendorCaptureSampleRates)
        assertEquals(12, PioneerMixerProfile.DJM_900NXS2.vendorCaptureChannelCount)
        assertEquals(3, PioneerMixerProfile.DJM_900NXS2.vendorCaptureSubframeSize)
        assertEquals(24, PioneerMixerProfile.DJM_900NXS2.vendorCaptureBitResolution)
        assertEquals(listOf(96_000), PioneerMixerProfile.DJM_900NXS2.vendorCaptureSampleRates)
        assertEquals(12, PioneerMixerProfile.DJM_750MK2.vendorCaptureChannelCount)
        assertEquals(3, PioneerMixerProfile.DJM_750MK2.vendorCaptureSubframeSize)
        assertEquals(24, PioneerMixerProfile.DJM_750MK2.vendorCaptureBitResolution)
        assertEquals(listOf(96_000), PioneerMixerProfile.DJM_750MK2.vendorCaptureSampleRates)
        assertEquals(8, PioneerMixerProfile.DJM_450.vendorCaptureChannelCount)
        assertEquals(3, PioneerMixerProfile.DJM_450.vendorCaptureSubframeSize)
        assertEquals(24, PioneerMixerProfile.DJM_450.vendorCaptureBitResolution)
        assertEquals(listOf(48_000), PioneerMixerProfile.DJM_450.vendorCaptureSampleRates)
        assertEquals(0, PioneerMixerProfile.DJM_450.vendorCaptureInterface)
        assertEquals(1, PioneerMixerProfile.DJM_450.vendorCaptureAlternateSetting)
    }

    @Test
    fun `uses driver-derived routes without assuming readback semantics`() {
        assertEquals(6, PioneerMixerProfile.DJM_V10.outputCount)
        assertEquals(List(6) { 0x0A }, PioneerMixerProfile.DJM_V10.mixWithMicSources)
        assertEquals(PioneerMixerProfile.RouteReadMode.NONE, PioneerMixerProfile.DJM_V10.routeReadMode)
        assertEquals(3, PioneerMixerProfile.DJM_450.outputCount)
        assertEquals(listOf(0x0A, 0x0A, 0x0A), PioneerMixerProfile.DJM_450.mixWithMicSources)
        assertEquals(PioneerMixerProfile.RouteReadMode.NONE, PioneerMixerProfile.DJM_450.routeReadMode)
        assertEquals(true, PioneerMixerProfile.DJM_450.requiresPlaybackTraffic)
        assertEquals(0, PioneerMixerProfile.DJM_450.playbackInterface)
        assertEquals(1, PioneerMixerProfile.DJM_450.playbackAlternateSetting)
    }

    @Test
    fun `encodes REC OUT with and without mic per kernel label table`() {
        // A9: 0x0a with mic, 0x0e without (both present in snd_djm_opts_a9_cap1..5); 0x09 (mic
        // only) is never a MIX route.
        assertEquals(0x050A, PioneerMixerProfile.DJM_A9.mixRouteValue(4, includeMic = true))
        assertEquals(0x050E, PioneerMixerProfile.DJM_A9.mixRouteValue(4, includeMic = false))
        assertFalse(PioneerMixerProfile.DJM_A9.mixWithMicSources.contains(0x09))
        assertTrue(PioneerMixerProfile.DJM_A9.supportsMicToggle)
        // V10: no without-mic variant -> mic preference ignored, every pair 0x0a.
        for (output in 0 until 6) {
            val expected = ((output + 1) shl 8) or 0x0A
            assertEquals(expected, PioneerMixerProfile.DJM_V10.mixRouteValue(output, includeMic = true))
            assertEquals(expected, PioneerMixerProfile.DJM_V10.mixRouteValue(output, includeMic = false))
        }
        assertFalse(PioneerMixerProfile.DJM_V10.supportsMicToggle)
        assertEquals(-1, PioneerMixerProfile.DJM_V10.mixRouteValue(6, includeMic = true))
        // 750MK2: 0x0a on all five pairs; 0x0f ("None") is gone.
        assertEquals(0x010A, PioneerMixerProfile.DJM_750MK2.mixRouteValue(0, includeMic = true))
        assertEquals(0x050A, PioneerMixerProfile.DJM_750MK2.mixRouteValue(4, includeMic = true))
        assertFalse(PioneerMixerProfile.DJM_750MK2.mixWithMicSources.contains(0x0F))
        // V5 mirrors the A9 codes on four pairs.
        assertEquals(0x040E, PioneerMixerProfile.DJM_V5.mixRouteValue(3, includeMic = false))
        assertEquals(-1, PioneerMixerProfile.DJM_V5.mixRouteValue(4, includeMic = true))
    }

    @Test
    fun `capture level register is only offered on the six-step models`() {
        assertTrue(PioneerMixerProfile.DJM_A9.supportsCaptureLevel)
        assertTrue(PioneerMixerProfile.DJM_V10.supportsCaptureLevel)
        assertFalse(PioneerMixerProfile.DJM_900NXS2.supportsCaptureLevel)
        assertTrue(PioneerMixerProfile.DJM_V5.supportsCaptureLevel)
        assertFalse(PioneerMixerProfile.DJM_750MK2.supportsCaptureLevel)
        assertEquals(listOf(15, 12, 9, 6, 3, 0), PioneerMixerProfile.CAPTURE_LEVEL_STEPS_DB)
        assertEquals(0x0000, PioneerMixerProfile.captureLevelValue(0))
        assertEquals(0x0500, PioneerMixerProfile.captureLevelValue(5))
        assertEquals(0x0500, PioneerMixerProfile.captureLevelValue(9))
        assertEquals(0x8003, PioneerMixerProfile.CAPTURE_LEVEL_INDEX)
    }

    @Test
    fun `encodes each driver route GET convention`() {
        assertEquals(0, PioneerMixerProfile.DJM_A9.routeReadValue(0))
        assertEquals(4, PioneerMixerProfile.DJM_A9.routeReadValue(4))
        assertEquals(1, PioneerMixerProfile.DJM_V5.routeReadValue(0))
        assertEquals(4, PioneerMixerProfile.DJM_V5.routeReadValue(3))
        assertEquals(0, PioneerMixerProfile.DJM_900NXS2.routeReadValue(4))
        assertEquals(0, PioneerMixerProfile.DJM_750MK2.routeReadValue(4))
        assertEquals(2, PioneerMixerProfile.DJM_A9.routeReadLength)
        assertEquals(5, PioneerMixerProfile.DJM_900NXS2.routeReadLength)
        assertEquals(0, PioneerMixerProfile.DJM_V10.routeReadLength)
    }

    @Test
    fun `decodes per-output and all-output route replies`() {
        assertEquals(0x0A, PioneerMixerProfile.DJM_A9.decodeRouteSource(byteArrayOf(0, 0x0A), 4))
        assertEquals(0x0E, PioneerMixerProfile.DJM_V5.decodeRouteSource(byteArrayOf(0, 0x0E), 2))
        assertEquals(
            0x0A,
            PioneerMixerProfile.DJM_900NXS2.decodeRouteSource(
                byteArrayOf(1, 2, 3, 4, 0x0A),
                4
            )
        )
        assertEquals(
            0x0F,
            PioneerMixerProfile.DJM_750MK2.decodeRouteSource(
                byteArrayOf(0x0F, 2, 3, 4, 5),
                0
            )
        )
    }

    @Test
    fun `validates output selector byte for per-output replies`() {
        assertEquals(true, PioneerMixerProfile.DJM_A9.isRouteResponseValid(byteArrayOf(4, 0x0A), 4))
        assertEquals(false, PioneerMixerProfile.DJM_A9.isRouteResponseValid(byteArrayOf(3, 0x0A), 4))
        assertEquals(true, PioneerMixerProfile.DJM_V5.isRouteResponseValid(byteArrayOf(4, 0x0E), 3))
        assertEquals(false, PioneerMixerProfile.DJM_V5.isRouteResponseValid(byteArrayOf(3, 0x0E), 3))
        assertEquals(true, PioneerMixerProfile.DJM_900NXS2.isRouteResponseValid(byteArrayOf(1, 2, 3, 4, 5), 4))
        assertEquals(false, PioneerMixerProfile.DJM_900NXS2.isRouteResponseValid(byteArrayOf(1, 2, 3, 4), 3))
    }
}

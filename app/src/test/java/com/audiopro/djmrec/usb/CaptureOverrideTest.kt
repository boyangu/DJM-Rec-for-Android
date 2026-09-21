package com.audiopro.djmrec.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOverrideTest {
    private val vendorEp = AudioStreamingInterfaceInfo(
        interfaceNumber = 0, alternateSetting = 1, terminalLink = -1,
        channelCount = 0, bitResolution = 0, subframeSize = 0,
        isochronousInEndpointAddress = 0x82, isochronousInMaxPacketSize = 1024, interfaceClass = 255
    )
    private val uacEp = AudioStreamingInterfaceInfo(
        interfaceNumber = 2, alternateSetting = 1, terminalLink = 3,
        channelCount = 2, bitResolution = 24, subframeSize = 4,
        isochronousInEndpointAddress = 0x83, isochronousInMaxPacketSize = 200, interfaceClass = 1
    )

    @Test
    fun `defaults are inactive and resolve the detected profile`() {
        val override = CaptureOverride()
        assertFalse(override.isActive)
        assertEquals(PioneerMixerProfile.DJM_V5, override.resolveProfile(0x2B73, 0x0058))
        assertNull(override.resolveProfile(0x2B73, 0x7777))
        assertEquals(uacEp, override.applyTo(uacEp, listOf(uacEp, vendorEp)))
        assertNull(override.applyTo(null, listOf(vendorEp)))
    }

    @Test
    fun `forced profile wins over the product id and none disables it`() {
        val forced = CaptureOverride(profile = PioneerMixerProfile.DJM_V5.name)
        assertEquals(PioneerMixerProfile.DJM_V5, forced.resolveProfile(0x2B73, 0x1234))
        assertEquals(PioneerMixerProfile.DJM_V5, forced.forcedProfile)
        val none = CaptureOverride(profile = CaptureOverride.PROFILE_NONE)
        assertNull(none.resolveProfile(0x2B73, 0x003C))
        assertNull(none.forcedProfile)
        assertTrue(none.isActive)
    }

    @Test
    fun `manual format fills in a vendor endpoint that detection could not describe`() {
        val override = CaptureOverride(channelCount = 8, subframeSize = 3, bitResolution = 24)
        val applied = override.applyTo(null, listOf(vendorEp))!!
        assertEquals(0, applied.interfaceNumber)
        assertEquals(0x82, applied.isochronousInEndpointAddress)
        assertEquals(8, applied.channelCount)
        assertEquals(3, applied.subframeSize)
        assertEquals(24, applied.bitResolution)
    }

    @Test
    fun `manual endpoint selects the requested interface and keeps detected format fields`() {
        val override = CaptureOverride(interfaceNumber = 0, alternateSetting = 1, endpointAddress = 0x82)
        val applied = override.applyTo(uacEp, listOf(uacEp, vendorEp))!!
        assertEquals(0x82, applied.isochronousInEndpointAddress)
        // Vendor endpoint carries no format; the shared DJM template fills the gap.
        assertEquals(CaptureOverride.DEFAULT_CHANNELS, applied.channelCount)
        assertEquals(CaptureOverride.DEFAULT_SUBFRAME, applied.subframeSize)
        assertEquals(24, applied.bitResolution)
    }

    @Test
    fun `round trips through the preference map and rejects garbage`() {
        val original = CaptureOverride(
            profile = PioneerMixerProfile.DJM_V10.name, channelCount = 12, subframeSize = 3,
            bitResolution = 24, interfaceNumber = 0, alternateSetting = 1, endpointAddress = 0x82,
            playbackKeepalive = CaptureOverride.TRISTATE_ON, endpointRateCommand = CaptureOverride.TRISTATE_OFF
        )
        assertEquals(original, CaptureOverride.fromMap(original.toMap()))
        val garbage = CaptureOverride.fromMap(
            mapOf("profile" to "DJM_UNKNOWN", "channels" to 99, "subframe" to 7, "bits" to 20, "playback" to 5)
        )
        assertEquals(CaptureOverride.PROFILE_AUTO, garbage.profile)
        assertEquals(0, garbage.channelCount)
        assertEquals(0, garbage.subframeSize)
        assertEquals(0, garbage.bitResolution)
        assertEquals(CaptureOverride.TRISTATE_ON, garbage.playbackKeepalive)
    }
}

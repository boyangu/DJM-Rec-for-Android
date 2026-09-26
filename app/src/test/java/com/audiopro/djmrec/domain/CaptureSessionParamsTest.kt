package com.audiopro.djmrec.domain

import kotlin.test.*

class CaptureSessionParamsTest {
    private val usb = CaptureSource.UsbIso(
        fd = 42, interfaceNumber = 1, alternateSetting = 1, endpointAddress = 0x81, maxPacketSize = 432,
        totalChannels = 12, subframeSize = 3, bitResolution = 24,
        channelOffset = CaptureSource.UsbIso.AUTO_CHANNEL_OFFSET, includeMic = true, clock = null,
        vendorId = 0x2B73, productId = 0x0034, rawDescriptors = byteArrayOf(9, 2, 1),
        playbackOverride = Tristate.FOLLOW_PROFILE, endpointRateOverride = Tristate.FOLLOW_PROFILE,
        allowFormatMismatch = false
    )

    private fun session(source: CaptureSource) = CaptureSessionParams("DJM-A9", 48_000, 24, source)

    @Test fun acceptsAUsableUsbSession() {
        assertNull(session(usb).invalidReason())
        assertTrue(session(usb).isUsbIso)
    }

    @Test fun rejectsUsbSessionsMissingWhatNativeNeedsToClaimTheEndpoint() {
        assertNotNull(session(usb.copy(fd = -1)).invalidReason())
        assertNotNull(session(usb.copy(interfaceNumber = -1)).invalidReason())
        assertNotNull(session(usb.copy(endpointAddress = -1)).invalidReason())
        assertNotNull(session(usb.copy(maxPacketSize = 0)).invalidReason())
    }

    @Test fun nonUsbSources() {
        assertNull(session(CaptureSource.Demo).invalidReason())
        assertFalse(session(CaptureSource.Demo).isUsbIso)
        assertNull(session(CaptureSource.AudioStack(deviceId = 7, channelCount = 2)).invalidReason())
        assertNotNull(session(CaptureSource.AudioStack(deviceId = -1, channelCount = 2)).invalidReason())
    }

    @Test fun usbSourcesCompareDescriptorContentNotArrayIdentity() {
        val same = usb.copy(rawDescriptors = byteArrayOf(9, 2, 1))
        assertEquals(usb, same)
        assertEquals(usb.hashCode(), same.hashCode())
        assertNotEquals(usb, usb.copy(rawDescriptors = byteArrayOf(9, 2, 2)))
    }

    @Test fun tristateMatchesTheNativeEncoding() {
        assertEquals(-1, Tristate.FOLLOW_PROFILE.nativeValue)
        assertEquals(0, Tristate.OFF.nativeValue)
        assertEquals(1, Tristate.ON.nativeValue)
        Tristate.entries.forEach { assertEquals(it, Tristate.fromNative(it.nativeValue)) }
        assertEquals(Tristate.FOLLOW_PROFILE, Tristate.fromNative(5))
    }
}

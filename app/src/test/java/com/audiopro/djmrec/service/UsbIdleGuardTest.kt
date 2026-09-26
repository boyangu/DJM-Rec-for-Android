package com.audiopro.djmrec.service

import android.media.AudioDeviceInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbIdleGuardTest {
    @Test
    fun `the guard never plays to a USB device`() {
        assertFalse(UsbIdleGuard.routeAcceptable(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertFalse(UsbIdleGuard.routeAcceptable(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertFalse(UsbIdleGuard.routeAcceptable(AudioDeviceInfo.TYPE_USB_ACCESSORY))
    }

    @Test
    fun `the phone's own outputs are fine`() {
        assertTrue(UsbIdleGuard.routeAcceptable(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertTrue(UsbIdleGuard.routeAcceptable(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertTrue(UsbIdleGuard.routeAcceptable(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
    }
}

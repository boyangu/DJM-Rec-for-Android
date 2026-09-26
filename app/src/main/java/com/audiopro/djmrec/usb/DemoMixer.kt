package com.audiopro.djmrec.usb

import android.os.Build
import com.audiopro.djmrec.BuildConfig

/**
 * Debug builds on an emulator only: a stand-in mixer so the whole app can be run and looked at
 * without USB hardware. When no real device is connected it is published as the current device;
 * the service then opens [com.audiopro.djmrec.audio.AudioEngine.openDemo], which feeds a
 * synthetic music-like signal through the same native path as USB audio (meters, waveform,
 * recording to a real file). Release builds and real phones never see it.
 */
object DemoMixer {
    const val DEVICE_NAME = "demo:mixer"

    val enabled: Boolean by lazy { BuildConfig.DEBUG && runningOnEmulator() }

    val device: UsbAudioDeviceInfo by lazy {
        UsbAudioDeviceInfo(
            deviceName = DEVICE_NAME,
            productName = "Demo Mixer (emulator)",
            vendorId = 0xDE30,
            productId = 0x0001,
            streamingInterfaceNumber = -1,
            activeAlternateSetting = 0,
            isochronousInEndpointAddress = -1,
            isochronousInMaxPacketSize = -1,
            channelCount = 2,
            bitResolution = 24,
            subframeSize = 3,
            supportedSampleRates = listOf(48_000, 44_100),
            hasPermission = true,
        )
    }

    fun isDemo(device: UsbAudioDeviceInfo?): Boolean = device?.deviceName == DEVICE_NAME

    private fun runningOnEmulator(): Boolean =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
            Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.PRODUCT.startsWith("sdk")
}

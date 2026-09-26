package com.audiopro.djmrec.usb

/**
 * USB identities with known Pioneer / AlphaTheta mixer USB contracts and optional proprietary
 * routing.
 *
 * Reference for the vendor register encodings: Linux `sound/usb/mixer_quirks.c` (snd_djm_*
 * tables) and `sound/usb/quirks-table.h` (wire formats), torvalds/linux master 2026-09-21.
 * Route SET: `bmRequestType 0x40, bRequest 0x03, wIndex 0x8002, wValue = ((output+1) shl 8) or
 * source`. Source codes: 0x00 LINE, 0x01 CD/LINE, 0x02 DIGITAL, 0x03 PHONO, 0x06 post fader,
 * 0x07/0x08 crossfader A/B, 0x09 MIC ONLY, 0x0a REC OUT (mix incl. mic), 0x0d AUX,
 * 0x0e REC OUT WITHOUT MIC, 0x0f NONE, 0x10 FX SEND.
 */
enum class PioneerMixerProfile(
    val displayName: String,
    val productIds: Set<Int>,
    val defaultCaptureChannelOffset: Int,
    val outputCount: Int,
    val routeReadMode: RouteReadMode,
    /** Vendor source per configurable output that carries REC OUT *including* the mic (0x0a). */
    val mixWithMicSources: List<Int>,
    /** REC OUT *without* mic (0x0e) per output, or -1 when the model's option list lacks it. */
    val mixWithoutMicSources: List<Int>,
    val requiresPlaybackTraffic: Boolean = false,
    val playbackInterface: Int = -1,
    val playbackAlternateSetting: Int = -1,
    /**
     * Some models (confirmed: DJM-900NXS2) don't declare their audio-carrying interface under
     * the standard USB Audio Class (class 1 / subclass 2) at all -- the isochronous IN endpoint
     * lives on a USB_CLASS_VENDOR_SPEC (255) interface instead, which
     * [com.audiopro.djmrec.usb.UsbAudioDescriptorParser.findAudioStreamingInterfaces] never
     * looks at. When set (>= 0), [UsbAudioManager] falls back to scanning this exact
     * (interface, alt setting) for its isochronous IN endpoint regardless of declared class, and
     * uses [vendorCaptureChannelCount]/[vendorCaptureSubframeSize]/[vendorCaptureBitResolution]
     * as the wire format, since a vendor-class interface has no CS_INTERFACE AS_GENERAL/FORMAT_TYPE
     * descriptors to read those from.
     *
     * DJM-900NXS2 values: endpoint (0x82 IN / 0x01 OUT, both isochronous, 1024B, interval 1)
     * confirmed via on-device descriptor dump (2026-07-20). The wire format was established
     * empirically -- see the two dated notes below -- and later matched the Linux
     * `quirks-table.h` entry for 2b73:000a (12-channel S24_3LE capture).
     *
     * UPDATE 2026-07-20 (a): a raw hex dump of the untouched capture-endpoint wire bytes (see
     * [com.audiopro.djmrec.diagnostics] logcat output, `raw iso packet #N dump`) confirmed
     * genuine all-zero payload on every MIX-routed pair while music was confirmed audibly playing
     * on the mixer -- this rules out a channel/bit-depth guess as the cause of silence (a
     * wrong format would misplace real nonzero bytes into the wrong slots, not zero them). Fixed
     * by sending the UAC1 SET_CUR sampling-frequency control transfer unconditionally (see
     * `setPioneerCaptureSampleRate` call site in native `UsbIsoAudioSource.cpp`), matching
     * Pioneer's own driver sequence captured via USBPcap -- real audio started flowing.
     *
     * UPDATE 2026-07-20 (b): once real audio was flowing, recordings came out quiet and
     * "washing machine"-distorted. Testing the real captured wire bytes from Pioneer's own driver
     * (whit_sound_on.pcapng, device 2b73:000a, endpoint 0x82) against every plausible
     * channel-count/subframe combination -- scoring each by how smooth/autocorrelated the decoded
     * samples come out, since real audio is continuous and a wrong stride produces near-noise --
     * showed 12 channels at 3-byte (24-bit) subframes fits roughly 10x better than every other
     * combination, including an earlier 10-channel guess. Corroborated independently: this app's
     * own observed capture packets are consistently 216 bytes, which divides evenly into 6 frames
     * of 12ch x 3B (36B/frame) but never evenly into 10ch x 3B (30B/frame, 216/30=7.2).
     */
    val vendorCaptureInterface: Int = -1,
    val vendorCaptureAlternateSetting: Int = -1,
    val vendorCaptureChannelCount: Int = -1,
    val vendorCaptureSubframeSize: Int = -1,
    val vendorCaptureBitResolution: Int = -1,
    val vendorCaptureSampleRates: List<Int> = emptyList(),
    /**
     * Extra 0-based output indices (beyond [defaultCaptureChannelOffset]'s output) that
     * [com.audiopro.djmrec.usb.UsbAudioManager.establishPioneerRoute] should also set to MIX.
     * DJM-900NXS2: a USBPcap capture of Pioneer's Setting Utility confirmed both output 1
     * (USB1/2) and output 5 (USB9/10, index 4) accept `source=0x0A` for MIX -- routing both
     * up front means the app's own USB-channel-pair picker (and the auto-pick-loudest-pair
     * fallback) can land on either without a second round of vendor requests.
     */
    val additionalMixOutputs: List<Int> = emptyList(),
    /**
     * True when the mixer exposes the six-step USB capture level register (kernel
     * `SND_DJM_WINDEX_CAPLVL`, wIndex 0x8003) with the A9/V10 scale: wValue 0x0000 = +15 dB,
     * 0x0100 = +12, 0x0200 = +9, 0x0300 = +6, 0x0400 = +3, 0x0500 = 0 dB.
     */
    val supportsCaptureLevel: Boolean = false
) {
    // Kernel snd_djm_opts_a9_cap1..5: every pair lists 0x0a (REC OUT) and 0x0e (REC OUT without
    // mic). 0x09 is the kernel's mic-only source and is deliberately NOT a MIX route here.
    DJM_A9(
        "DJM-A9", setOf(0x003C), 8, 5, RouteReadMode.SINGLE_OUTPUT_ZERO_BASED,
        List(5) { 0x0A }, List(5) { 0x0E }, true, 1, 1,
        supportsCaptureLevel = true
    ),
    // Kernel quirks-table.h 2b73:0034: 12 ch out / 12 ch in, S24_3LE, 44.1/48/96 kHz, if0/alt1,
    // EP 0x82 in / 0x01 out; snd_djm_opts_v10_cap1..6 list 0x0a on all six pairs, no 0x0e.
    DJM_V10(
        "DJM-V10", setOf(0x0034), 0, 6, RouteReadMode.NONE,
        List(6) { 0x0A }, List(6) { -1 },
        requiresPlaybackTraffic = true, playbackInterface = 0, playbackAlternateSetting = 1,
        vendorCaptureInterface = 0, vendorCaptureAlternateSetting = 1,
        vendorCaptureChannelCount = 12, vendorCaptureSubframeSize = 3,
        vendorCaptureBitResolution = 24,
        vendorCaptureSampleRates = listOf(44_100, 48_000, 96_000),
        supportsCaptureLevel = true
    ),
    // DJM-V5 (2026-01). Product IDs 0x0058-0x005B CONFIRMED from AlphaTheta's own Mac Setting
    // Utility 1.0.0 (DJM-V5Setup.framework checks vendor 0x2B73, product 0x58..0x5B). The same
    // framework's strings list the per-pair USB input options -- Control Tone PHONO / LINE,
    // Pre/Post CH fader, MIC, "MIX(REC OUT with MIC)", "MIX(REC OUT without MIC)" -- and a
    // six-step boost level (+15..0 dB) identical to the A9/V10, so the A9 source codes (0x0a /
    // 0x0e) and the capture-level register are assumed. No kernel quirk or descriptor dump yet:
    // the wire format comes from the device's UAC descriptors when present, otherwise from
    // UsbAudioManager's generic AlphaTheta vendor-class fallback or a manual override.
    DJM_V5(
        "DJM-V5", setOf(0x0058, 0x0059, 0x005A, 0x005B), 0, 4,
        RouteReadMode.SINGLE_OUTPUT_ONE_BASED, List(4) { 0x0A }, List(4) { 0x0E },
        supportsCaptureLevel = true
    ),
    DJM_900NXS2(
        "DJM-900NXS2", setOf(0x000A), 0, 5, RouteReadMode.ALL_OUTPUTS,
        List(5) { 0x0A }, List(5) { -1 },
        requiresPlaybackTraffic = true, playbackInterface = 0, playbackAlternateSetting = 1,
        vendorCaptureInterface = 0, vendorCaptureAlternateSetting = 1,
        vendorCaptureChannelCount = 12, vendorCaptureSubframeSize = 3, vendorCaptureBitResolution = 24,
        vendorCaptureSampleRates = listOf(96_000),
        additionalMixOutputs = listOf(4)
    ),
    // Kernel snd_djm_opts_750mk2_cap1..5 list 0x0a on all five pairs; factory REC OUT is USB 9/10
    // (kernel default index 3 of cap5 = 0x050a). The previous 0x0f ("None") entries are not in
    // this model's option list at all.
    DJM_750MK2(
        "DJM-750MK2", setOf(0x001B), 8, 5, RouteReadMode.ALL_OUTPUTS,
        List(5) { 0x0A }, List(5) { -1 },
        requiresPlaybackTraffic = true, playbackInterface = 0, playbackAlternateSetting = 1,
        vendorCaptureInterface = 0, vendorCaptureAlternateSetting = 1,
        vendorCaptureChannelCount = 12, vendorCaptureSubframeSize = 3,
        vendorCaptureBitResolution = 24, vendorCaptureSampleRates = listOf(96_000)
    ),
    // ALSA DJM-S11 contract: capture if2, playback if1, MIX REC OUT on USB5/6 only.
    DJM_S11(
        "DJM-S11", setOf(0x0037), 4, 3, RouteReadMode.NONE,
        listOf(-1, -1, 0x0A), listOf(-1, -1, -1),
        requiresPlaybackTraffic = true, playbackInterface = 1, playbackAlternateSetting = 1,
        vendorCaptureInterface = 2, vendorCaptureAlternateSetting = 1,
        vendorCaptureChannelCount = 10, vendorCaptureSubframeSize = 3,
        vendorCaptureBitResolution = 24, vendorCaptureSampleRates = listOf(48_000)
    ),
    // Kernel snd_djm_opts_450_cap1..3 list 0x0a on all three configurable pairs (USB 7/8 fixed).
    DJM_450(
        "DJM-450", setOf(0x0013), 0, 3, RouteReadMode.NONE,
        listOf(0x0A, 0x0A, 0x0A), listOf(-1, -1, -1),
        requiresPlaybackTraffic = true, playbackInterface = 0, playbackAlternateSetting = 1,
        vendorCaptureInterface = 0, vendorCaptureAlternateSetting = 1,
        vendorCaptureChannelCount = 8, vendorCaptureSubframeSize = 3,
        vendorCaptureBitResolution = 24, vendorCaptureSampleRates = listOf(48_000)
    ),
    // DDJ-FLX10 (2b73:0041), from an on-device descriptor dump (firmware 1.14, 2026-09-22):
    // class-compliant UAC2. AC if0 with an internal programmable clock (id 1); AS if1/alt1 = 4 ch
    // OUT (EP 0x01, 84 B) and AS if2/alt1 = 10 ch IN (EP 0x81, 210 B, 24-bit in 3-byte slots,
    // flagged implicit-feedback for the OUT stream); 44.1 kHz only. Standard descriptors carry the
    // format, so no vendor-capture override. Like the DDJ-1000 (kernel quirk) it needs the UAC1-style
    // endpoint SET_CUR rate command and produces audio only while the host streams playback, hence
    // the silent keepalive. Vendor routing codes are unknown, so no MIX route is written; AUTO locks
    // the loudest pair. Which pair carries the recording mix is still to be confirmed.
    DDJ_FLX10(
        "DDJ-FLX10", setOf(0x0041), 0, 5, RouteReadMode.NONE,
        List(5) { -1 }, List(5) { -1 },
        requiresPlaybackTraffic = true, playbackInterface = 1, playbackAlternateSetting = 1,
        vendorCaptureSampleRates = listOf(44_100)
    );

    val hasVendorCaptureOverride: Boolean get() = vendorCaptureInterface >= 0

    /** Recording confirmed by the project owner; other profiles still need physical testing. */
    val isHardwareConfirmed: Boolean get() = this == DJM_A9 || this == DJM_750MK2

    /** True when at least one output offers a separate "REC OUT without mic" source. */
    val supportsMicToggle: Boolean get() = mixWithoutMicSources.any { it >= 0 }

    /**
     * Vendor source code to write for [output] honoring the mic preference. Falls back to the
     * with-mic source when the model has no separate without-mic route; -1 when the output is
     * fixed (not configurable).
     */
    fun mixSource(output: Int, includeMic: Boolean): Int {
        val withMic = mixWithMicSources.getOrNull(output) ?: return -1
        val withoutMic = mixWithoutMicSources.getOrNull(output) ?: -1
        return if (includeMic || withoutMic < 0) withMic else withoutMic
    }

    /** Full `wValue` for the route SET request, or -1 when [output] is not configurable. */
    fun mixRouteValue(output: Int, includeMic: Boolean): Int {
        if (output < 0 || output >= outputCount) return -1
        val source = mixSource(output, includeMic)
        return if (source < 0) -1 else ((output + 1) shl 8) or source
    }

    enum class RouteReadMode(private val fixedResponseLength: Int) {
        NONE(0),
        SINGLE_OUTPUT_ZERO_BASED(2),
        SINGLE_OUTPUT_ONE_BASED(2),
        /** Response carries one byte per configurable output; length = [outputCount]. */
        ALL_OUTPUTS(-1);

        internal fun responseLengthFor(outputCount: Int): Int =
            if (this == ALL_OUTPUTS) outputCount else fixedResponseLength
    }

    /** Expected byte length of a route GET reply for this model. */
    val routeReadLength: Int get() = routeReadMode.responseLengthFor(outputCount)

    fun routeReadValue(outputIndex: Int): Int = when (routeReadMode) {
        RouteReadMode.NONE -> 0
        RouteReadMode.SINGLE_OUTPUT_ZERO_BASED -> outputIndex
        RouteReadMode.SINGLE_OUTPUT_ONE_BASED -> outputIndex + 1
        RouteReadMode.ALL_OUTPUTS -> 0
    }

    fun decodeRouteSource(response: ByteArray, outputIndex: Int): Int? = when (routeReadMode) {
        RouteReadMode.NONE -> null
        RouteReadMode.SINGLE_OUTPUT_ZERO_BASED,
        RouteReadMode.SINGLE_OUTPUT_ONE_BASED -> response.getOrNull(1)?.toInt()?.and(0xFF)
        RouteReadMode.ALL_OUTPUTS -> response.getOrNull(outputIndex)?.toInt()?.and(0xFF)
    }

    fun isRouteResponseValid(response: ByteArray, outputIndex: Int): Boolean =
        response.size == routeReadLength && when (routeReadMode) {
            RouteReadMode.NONE -> false
            RouteReadMode.SINGLE_OUTPUT_ZERO_BASED ->
                response.firstOrNull()?.toInt()?.and(0xFF) == outputIndex
            RouteReadMode.SINGLE_OUTPUT_ONE_BASED ->
                response.firstOrNull()?.toInt()?.and(0xFF) == outputIndex + 1
            RouteReadMode.ALL_OUTPUTS -> outputIndex in response.indices
        }

    companion object {
        const val ALPHATHETA_VENDOR_ID = 0x2B73
        const val ROUTE_GET_REQUEST = 0x00
        const val ROUTE_SET_REQUEST = 0x03
        const val ROUTE_INDEX = 0x8002
        /** Capture level register (kernel SND_DJM_WINDEX_CAPLVL); same request type/number as routes. */
        const val CAPTURE_LEVEL_INDEX = 0x8003

        /** Capture level steps for [supportsCaptureLevel] models: index -> gain in dB. */
        val CAPTURE_LEVEL_STEPS_DB: List<Int> = listOf(15, 12, 9, 6, 3, 0)

        /** `wValue` for the capture level register at [stepIndex] (0 = +15 dB ... 5 = 0 dB). */
        fun captureLevelValue(stepIndex: Int): Int = (stepIndex.coerceIn(0, CAPTURE_LEVEL_STEPS_DB.size - 1)) shl 8

        fun find(vendorId: Int, productId: Int): PioneerMixerProfile? {
            if (vendorId != ALPHATHETA_VENDOR_ID) return null
            return entries.firstOrNull { productId in it.productIds }
        }
    }
}

package com.audiopro.djmrec.usb

import android.content.Context

/**
 * Per-mixer runtime overrides so an unknown or mis-detected mixer (a DJM-V5 with an unexpected
 * product ID, new firmware, a future model) can be made to work in the field without a rebuild.
 * Everything here is optional; the defaults mean "use what the app detected".
 *
 * Stored per USB vendor:product in the shared "settings" preferences (see [CaptureOverrideStore]).
 */
data class CaptureOverride(
    /** [PROFILE_AUTO], [PROFILE_NONE] (class-compliant only, no vendor routing) or a [PioneerMixerProfile] name. */
    val profile: String = PROFILE_AUTO,
    /** Wire channel count (2..32) or 0 = detected/profile value. */
    val channelCount: Int = 0,
    /** Bytes per sample container (2/3/4) or 0 = detected. */
    val subframeSize: Int = 0,
    /** Significant bits per sample (16/24/32) or 0 = detected. */
    val bitResolution: Int = 0,
    /** Isochronous IN endpoint to capture from, or -1 = detected. Both interface and alt setting must be set together. */
    val interfaceNumber: Int = -1,
    val alternateSetting: Int = -1,
    val endpointAddress: Int = -1,
    /** Silent OUT keepalive traffic: [TRISTATE_AUTO], [TRISTATE_OFF] or [TRISTATE_ON]. */
    val playbackKeepalive: Int = TRISTATE_AUTO,
    /** UAC1 SET_CUR sampling-frequency request on the capture endpoint: auto/off/on. */
    val endpointRateCommand: Int = TRISTATE_AUTO
) {
    val hasProfileChoice: Boolean get() = profile != PROFILE_AUTO
    val hasFormat: Boolean get() = channelCount > 0 || subframeSize > 0 || bitResolution > 0
    val hasEndpoint: Boolean get() = interfaceNumber >= 0 && alternateSetting >= 0
    val isActive: Boolean
        get() = hasProfileChoice || hasFormat || hasEndpoint ||
            playbackKeepalive != TRISTATE_AUTO || endpointRateCommand != TRISTATE_AUTO

    /** The profile to apply for a device with these USB IDs, or null for none. */
    fun resolveProfile(vendorId: Int, productId: Int): PioneerMixerProfile? = when (profile) {
        PROFILE_NONE -> null
        PROFILE_AUTO -> PioneerMixerProfile.find(vendorId, productId)
        else -> PioneerMixerProfile.entries.firstOrNull { it.name == profile }
            ?: PioneerMixerProfile.find(vendorId, productId)
    }

    /** Manual profile chosen by the user (not auto-detected), if any. */
    val forcedProfile: PioneerMixerProfile?
        get() = PioneerMixerProfile.entries.firstOrNull { it.name == profile }

    /**
     * Applies the manual wire format on top of what detection produced. [candidates] are every
     * isochronous IN endpoint in the configuration (any interface class), used when the user
     * picked a specific endpoint or when detection found nothing usable at all.
     */
    fun applyTo(
        detected: AudioStreamingInterfaceInfo?,
        candidates: List<AudioStreamingInterfaceInfo>
    ): AudioStreamingInterfaceInfo? {
        val base = when {
            hasEndpoint -> candidates.firstOrNull {
                it.interfaceNumber == interfaceNumber && it.alternateSetting == alternateSetting &&
                    (endpointAddress < 0 || it.isochronousInEndpointAddress == endpointAddress)
            } ?: detected
            detected != null -> detected
            hasFormat -> candidates.filter { it.alternateSetting > 0 }.maxByOrNull { it.isochronousInMaxPacketSize ?: 0 }
            else -> null
        } ?: return null
        if (!hasFormat && !hasEndpoint) return base
        val channels = channelCount.takeIf { it > 0 } ?: base.channelCount.takeIf { it > 0 } ?: DEFAULT_CHANNELS
        val subframe = subframeSize.takeIf { it > 0 } ?: base.subframeSize.takeIf { it > 0 } ?: DEFAULT_SUBFRAME
        val bits = bitResolution.takeIf { it > 0 } ?: base.bitResolution.takeIf { it > 0 } ?: minOf(24, subframe * 8)
        return base.copy(channelCount = channels, subframeSize = subframe, bitResolution = bits)
    }

    fun toMap(): Map<String, Any> = mapOf(
        KEY_PROFILE to profile,
        KEY_CHANNELS to channelCount,
        KEY_SUBFRAME to subframeSize,
        KEY_BITS to bitResolution,
        KEY_INTERFACE to interfaceNumber,
        KEY_ALT to alternateSetting,
        KEY_ENDPOINT to endpointAddress,
        KEY_PLAYBACK to playbackKeepalive,
        KEY_RATE_COMMAND to endpointRateCommand
    )

    companion object {
        const val PROFILE_AUTO = "auto"
        const val PROFILE_NONE = "none"
        const val TRISTATE_AUTO = -1
        const val TRISTATE_OFF = 0
        const val TRISTATE_ON = 1
        /** The template shared by every multichannel DJM in the Linux quirks table. */
        const val DEFAULT_CHANNELS = 12
        const val DEFAULT_SUBFRAME = 3

        const val KEY_PROFILE = "profile"
        const val KEY_CHANNELS = "channels"
        const val KEY_SUBFRAME = "subframe"
        const val KEY_BITS = "bits"
        const val KEY_INTERFACE = "interface"
        const val KEY_ALT = "alt"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_PLAYBACK = "playback"
        const val KEY_RATE_COMMAND = "rate_command"

        val CHANNEL_CHOICES = listOf(2, 4, 6, 8, 10, 12, 14, 16)
        /** (subframe bytes, bit resolution) pairs the decoder understands. */
        val CONTAINER_CHOICES = listOf(3 to 24, 4 to 24, 4 to 32, 2 to 16)

        fun fromMap(values: Map<String, Any?>): CaptureOverride {
            fun int(key: String, default: Int) = (values[key] as? Number)?.toInt()
                ?: (values[key] as? String)?.toIntOrNull() ?: default
            val profile = (values[KEY_PROFILE] as? String)?.takeIf { it.isNotBlank() } ?: PROFILE_AUTO
            return CaptureOverride(
                profile = if (profile == PROFILE_AUTO || profile == PROFILE_NONE ||
                    PioneerMixerProfile.entries.any { it.name == profile }) profile else PROFILE_AUTO,
                channelCount = int(KEY_CHANNELS, 0).takeIf { it in 1..32 } ?: 0,
                subframeSize = int(KEY_SUBFRAME, 0).takeIf { it in 2..4 } ?: 0,
                bitResolution = int(KEY_BITS, 0).takeIf { it in setOf(16, 24, 32) } ?: 0,
                interfaceNumber = int(KEY_INTERFACE, -1),
                alternateSetting = int(KEY_ALT, -1),
                endpointAddress = int(KEY_ENDPOINT, -1),
                playbackKeepalive = int(KEY_PLAYBACK, TRISTATE_AUTO).coerceIn(TRISTATE_AUTO, TRISTATE_ON),
                endpointRateCommand = int(KEY_RATE_COMMAND, TRISTATE_AUTO).coerceIn(TRISTATE_AUTO, TRISTATE_ON)
            )
        }
    }
}

/** Persists one [CaptureOverride] per USB vendor:product in the app's "settings" preferences. */
object CaptureOverrideStore {
    private const val PREFS_NAME = "settings"
    private fun prefix(vendorId: Int, productId: Int) = "capture_override_${vendorId}_${productId}_"

    fun load(context: Context, vendorId: Int, productId: Int): CaptureOverride {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = prefix(vendorId, productId)
        val values = prefs.all.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
        return if (values.isEmpty()) CaptureOverride() else CaptureOverride.fromMap(values)
    }

    fun save(context: Context, vendorId: Int, productId: Int, override: CaptureOverride) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = prefix(vendorId, productId)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        if (override.isActive) {
            override.toMap().forEach { (key, value) ->
                when (value) {
                    is Int -> editor.putInt(prefix + key, value)
                    else -> editor.putString(prefix + key, value.toString())
                }
            }
        }
        editor.apply()
    }

    fun clear(context: Context, vendorId: Int, productId: Int) = save(context, vendorId, productId, CaptureOverride())
}

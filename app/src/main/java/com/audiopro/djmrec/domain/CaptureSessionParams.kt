package com.audiopro.djmrec.domain

/**
 * One description of a capture session: everything the recording service needs to open the
 * source, name it in the notification and journal, and size the output. Built once by the
 * ViewModel from the published device and the user's settings, then handed to the service whole.
 */
data class CaptureSessionParams(
    /** Shown in the notification ("Recording from …") and stored in the crash journal. */
    val deviceLabel: String,
    /** The rate Kotlin chose; the USB source measures the real cadence and that wins. */
    val sampleRateHint: Int,
    val bitDepth: Int,
    val source: CaptureSource,
) {
    val isUsbIso: Boolean get() = source is CaptureSource.UsbIso

    /** Why the service must refuse to open this session, or null if it is usable. */
    fun invalidReason(): String? = when (source) {
        is CaptureSource.UsbIso ->
            if (source.fd < 0 || source.interfaceNumber < 0 || source.endpointAddress < 0 || source.maxPacketSize <= 0) {
                "Invalid USB capture parameters"
            } else {
                null
            }
        is CaptureSource.AudioStack -> if (source.deviceId < 0) "Invalid audio device" else null
        CaptureSource.Demo -> null
    }
}

sealed interface CaptureSource {

    /**
     * Raw libusb isochronous capture of a stereo pair out of the mixer's multichannel interface.
     * [fd] belongs to a `UsbDeviceConnection` that must stay open for the whole session.
     */
    data class UsbIso(
        val fd: Int,
        val interfaceNumber: Int,
        val alternateSetting: Int,
        val endpointAddress: Int,
        val maxPacketSize: Int,
        /** Interleaved channel count on the wire (e.g. 12). */
        val totalChannels: Int,
        /** Bytes per sample container on the wire. */
        val subframeSize: Int,
        val bitResolution: Int,
        /** 0-based first channel of the pair to extract, or [AUTO_CHANNEL_OFFSET]. */
        val channelOffset: Int,
        /** Route REC OUT with the mic bus where the model offers a choice. */
        val includeMic: Boolean,
        /** Null for Pioneer profiles, whose clock entity requests stall some firmware. */
        val clock: ClockControl?,
        val vendorId: Int,
        val productId: Int,
        val rawDescriptors: ByteArray,
        val playbackOverride: Tristate,
        val endpointRateOverride: Tristate,
        /** True when the wire format was entered manually; native logs mismatches instead of rejecting. */
        val allowFormatMismatch: Boolean,
    ) : CaptureSource {
        override fun equals(other: Any?): Boolean =
            other is UsbIso &&
                fd == other.fd &&
                interfaceNumber == other.interfaceNumber &&
                alternateSetting == other.alternateSetting &&
                endpointAddress == other.endpointAddress &&
                maxPacketSize == other.maxPacketSize &&
                totalChannels == other.totalChannels &&
                subframeSize == other.subframeSize &&
                bitResolution == other.bitResolution &&
                channelOffset == other.channelOffset &&
                includeMic == other.includeMic &&
                clock == other.clock &&
                vendorId == other.vendorId &&
                productId == other.productId &&
                rawDescriptors.contentEquals(other.rawDescriptors) &&
                playbackOverride == other.playbackOverride &&
                endpointRateOverride == other.endpointRateOverride &&
                allowFormatMismatch == other.allowFormatMismatch

        override fun hashCode(): Int {
            var result = fd
            result = 31 * result + interfaceNumber
            result = 31 * result + alternateSetting
            result = 31 * result + endpointAddress
            result = 31 * result + maxPacketSize
            result = 31 * result + totalChannels
            result = 31 * result + subframeSize
            result = 31 * result + bitResolution
            result = 31 * result + channelOffset
            result = 31 * result + includeMic.hashCode()
            result = 31 * result + (clock?.hashCode() ?: 0)
            result = 31 * result + vendorId
            result = 31 * result + productId
            result = 31 * result + rawDescriptors.contentHashCode()
            result = 31 * result + playbackOverride.hashCode()
            result = 31 * result + endpointRateOverride.hashCode()
            result = 31 * result + allowFormatMismatch.hashCode()
            return result
        }

        companion object {
            const val AUTO_CHANNEL_OFFSET = -1
        }
    }

    /** A plain stereo class device opened through Android's audio stack. Removed in phase 7. */
    data class AudioStack(val deviceId: Int, val channelCount: Int) : CaptureSource

    /** Debug builds only: the synthetic demo mixer. */
    data object Demo : CaptureSource
}

/** A UAC2 clock source the native side may query or set. */
data class ClockControl(
    val interfaceNumber: Int,
    val sourceId: Int,
    val supportsFrequencySet: Boolean,
)

/** A manual override that either follows the mixer profile or forces a behaviour off or on. */
enum class Tristate(val nativeValue: Int) {
    FOLLOW_PROFILE(-1),
    OFF(0),
    ON(1);

    companion object {
        fun fromNative(value: Int): Tristate = entries.firstOrNull { it.nativeValue == value } ?: FOLLOW_PROFILE
    }
}

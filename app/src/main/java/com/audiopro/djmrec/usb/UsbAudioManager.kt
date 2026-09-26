package com.audiopro.djmrec.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the full USB lifecycle for the mixer: attach/detach detection, runtime permission
 * request, descriptor inspection (to prove the device is really UAC2 stereo audio and to
 * surface its native format to the UI), and resolution of the matching [AudioDeviceInfo] id
 * that the native AAudio/Oboe engine binds to for the actual capture stream.
 *
 * This class deliberately never opens a bulk/iso transfer itself — see class doc on
 * [UsbAudioDescriptorParser] for why.
 */
data class UsbInputOption(val deviceName: String, val label: String, val hasPermission: Boolean, val captureCandidate: Boolean)

class UsbAudioManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbAudioManager"
        const val ACTION_USB_PERMISSION = "com.audiopro.djmrec.USB_PERMISSION"

        /**
         * AlphaTheta / Pioneer DJ USB vendor ID. Every DJM/XDJ this app knows enumerates under
         * it; the legacy Pioneer Corporation ID (0x08E4, DJM-750/850) has no profile here.
         */
        val PIONEER_VENDOR_IDS = setOf(PioneerMixerProfile.ALPHATHETA_VENDOR_ID)

        const val AUTO_CHANNEL_OFFSET = -1
        private fun isPioneerDevice(device: UsbDevice) = device.vendorId in PIONEER_VENDOR_IDS

        /**
         * Wire format assumed for an AlphaTheta device that exposes neither standard UAC
         * AudioStreaming descriptors nor a verified per-model vendor override: the template every
         * multichannel DJM in the Linux quirks table shares (12 ch, S24_3LE, 44.1/48/96 kHz).
         */
        private const val GENERIC_ALPHATHETA_CHANNELS = 12
        private const val GENERIC_ALPHATHETA_SUBFRAME = 3
        private const val GENERIC_ALPHATHETA_BITS = 24
        private val GENERIC_ALPHATHETA_RATES = listOf(44_100, 48_000, 96_000)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _deviceState = MutableStateFlow<UsbAudioDeviceInfo?>(null)
    val deviceState: StateFlow<UsbAudioDeviceInfo?> = _deviceState.asStateFlow()
    private val _inputs = MutableStateFlow<List<UsbInputOption>>(emptyList())
    val inputs = _inputs.asStateFlow()
    private val _connectionNotice = MutableStateFlow<String?>(null)
    val connectionNotice = _connectionNotice.asStateFlow()
    private var requestedDeviceName: String? = null
    private val diagnosticDevices = mutableSetOf<String>()

    private fun trace(device: UsbDevice, stage: String, detail: String = "") =
        com.audiopro.djmrec.diagnostics.RemoteDiagnostics.usbEvent(device.deviceName,
            device.productName ?: "Unknown USB device", device.vendorId, device.productId, stage, detail)

    fun refreshInputs() {
        val connected = usbManager.deviceList.values
        diagnosticDevices.retainAll(connected.map { it.deviceName }.toSet())
        connected.filter { diagnosticDevices.add(it.deviceName) }.forEach { device ->
            trace(device, "refreshInputs", "permission=${usbManager.hasPermission(device)}; capture candidate=${isCaptureCandidate(device)}; " +
                (0 until device.interfaceCount).joinToString { index ->
                    val intf = device.getInterface(index)
                    "if${intf.id}/alt${intf.alternateSetting} class=${intf.interfaceClass}/${intf.interfaceSubclass} protocol=${intf.interfaceProtocol} " +
                        (0 until intf.endpointCount).joinToString { epIndex ->
                            val ep = intf.getEndpoint(epIndex)
                            "ep=${ep.address} direction=${ep.direction} type=${ep.type} packet=${ep.maxPacketSize} interval=${ep.interval}"
                        }
                })
        }
        _inputs.value = usbManager.deviceList.values.map { device ->
            UsbInputOption(device.deviceName, device.productName ?: "USB ${device.vendorId.toString(16)}:${device.productId.toString(16)}",
                usbManager.hasPermission(device), isCaptureCandidate(device))
        }.sortedBy { it.label }
    }

    /** Caller must stop monitoring first. Never close an active raw capture here. */
    fun selectDevice(deviceName: String): Boolean {
        if (activeIsoConnection != null) return false
        val device = usbManager.deviceList[deviceName] ?: return false
        if (!isCaptureCandidate(device)) return false
        requestedDeviceName = deviceName
        _deviceState.value = null
        onDeviceAttached(device)
        return true
    }

    private var registered = false

    /**
     * Kept open (not `.close()`'d) for as long as native libusb capture is running -- its fd
     * is handed to `libusb_wrap_sys_device()` on the native side, so closing it mid-capture
     * would pull the fd out from under libusb. See [openIsoCaptureHandle]/[releaseIsoCaptureConnection].
     */
    private var activeIsoConnection: UsbDeviceConnection? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> handlePermissionResult(intent)
            }
        }
    }

    private val usbDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getIntentDevice(intent) ?: return
                    Log.i(TAG, "Attach broadcast received for ${device.deviceName}")
                    onDeviceAttached(device)
                    refreshInputs()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getIntentDevice(intent) ?: return
                    com.audiopro.djmrec.diagnostics.RemoteDiagnostics.usbDetached(device.deviceName)
                    if (_deviceState.value?.deviceName == device.deviceName) {
                        Log.i(TAG, "Mixer detached: ${device.deviceName}")
                        _deviceState.value = null
                        _connectionNotice.value = "USB input disconnected. Reconnect to continue."
                    }
                    refreshInputs()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getIntentDevice(intent: Intent): UsbDevice? =
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    /** Call once (e.g. from Application.onCreate) to start listening for attach/detach/permission events. */
    fun start() {
        if (registered) return
        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val usbDeviceFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            usbDeviceReceiver,
            usbDeviceFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        registered = true

        // Pick up a mixer that was already plugged in before the app started.
        scanForConnectedMixer("start")
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(permissionReceiver)
        context.unregisterReceiver(usbDeviceReceiver)
        registered = false
        releaseIsoCaptureConnection()
    }

    /** Explicit UI-triggered scan. If Android exposes the mixer in UsbManager, this requests permission/opens it. */
    fun scanForConnectedMixer(reason: String = "manual-rescan"): Boolean {
        refreshInputs()
        logEnumeratedDevices(reason)
        val device = findConnectedAudioClassDevice()
        if (device == null) {
            if (DemoMixer.enabled) {
                // Debug emulator build: stand in for the missing mixer (see DemoMixer).
                _deviceState.value = DemoMixer.device
                _connectionNotice.value = null
                return true
            }
            Log.w(TAG, "$reason: no connected device exposes a supported audio capture interface")
            _deviceState.value = null
            _connectionNotice.value = if (_inputs.value.isEmpty()) "Connect a mixer or USB audio interface using a data cable."
                else "Connected USB devices expose no audio capture input. Use the PC/Mac audio port, not a storage or Link Export connection."
            return false
        }
        Log.i(TAG, "$reason: found USB audio class device ${device.deviceName}; connecting")
        onDeviceAttached(device)
        return true
    }

    private fun logEnumeratedDevices(reason: String) {
        val allDevices = usbManager.deviceList.values
        Log.i(TAG, "$reason: ${allDevices.size} USB device(s) currently enumerated by the host")
        allDevices.forEach { d ->
            val classes = (0 until d.interfaceCount).joinToString { i ->
                val intf = d.getInterface(i)
                "if${intf.id}/alt${intf.alternateSetting}=class:${intf.interfaceClass}/sub:${intf.interfaceSubclass}"
            }
            Log.i(TAG, "  device ${d.deviceName} vid=${d.vendorId} pid=${d.productId} name=${d.productName} interfaces=[$classes]")
        }
    }

    private fun isCaptureCandidate(device: UsbDevice): Boolean =
        PioneerMixerProfile.find(device.vendorId, device.productId) != null ||
            AllInOneProfile.find(device.vendorId, device.productId) != null ||
            (0 until device.interfaceCount).any { i ->
                val intf = device.getInterface(i)
                // Standard UAC streaming interface, or -- for any AlphaTheta device, known PID or
                // not -- an isochronous IN endpoint on a vendor-specific interface (DJM-900NXS2,
                // DJM-V10 and friends never declare class 1 for their audio).
                (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO && intf.interfaceSubclass == 2 ||
                    isPioneerDevice(device)) &&
                    (0 until intf.endpointCount).any { endpointIndex ->
                        val endpoint = intf.getEndpoint(endpointIndex)
                        endpoint.direction == UsbConstants.USB_DIR_IN &&
                            endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                    }
            }

    private fun findConnectedAudioClassDevice(): UsbDevice? =
        usbManager.deviceList.values.filter(::isCaptureCandidate)
            .sortedWith(compareByDescending<UsbDevice> { it.deviceName == _deviceState.value?.deviceName }
                .thenByDescending { PioneerMixerProfile.find(it.vendorId, it.productId) != null }
                .thenBy { it.deviceName })
            .firstOrNull()

    private fun onDeviceAttached(device: UsbDevice) {
        trace(device, "onDeviceAttached", "permission=${usbManager.hasPermission(device)}; capture candidate=${isCaptureCandidate(device)}")
        _deviceState.value?.let { active ->
            // Refresh Android's delayed input registration without reopening a live USB connection.
            if (active.deviceName == device.deviceName && active.audioManagerDeviceId < 0) {
                resolveAudioManagerDeviceId(device)?.let { (id, rates) ->
                    _deviceState.value = active.copy(audioManagerDeviceId = id,
                        supportedSampleRates = (active.supportedSampleRates + rates).distinct())
                }
            }
            return // Never replace a live source on another attach/rescan.
        }
        if (!isCaptureCandidate(device)) {
            Log.w(TAG, "Attached device ${device.deviceName} (${device.vendorId}:${device.productId}) has no USB_CLASS_AUDIO interface; ignoring")
            return
        }

        Log.i(TAG, "UAC candidate attached: ${device.deviceName} (${device.vendorId}:${device.productId})")
        requestedDeviceName = device.deviceName
        _connectionNotice.value = "Checking ${device.productName ?: "USB input"}..."

        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "Already have permission for ${device.deviceName}; inspecting descriptors")
            inspectAndPublish(device)
        } else {
            _connectionNotice.value = "Allow USB access to ${device.productName ?: "your input"}."
            Log.i(TAG, "No permission yet for ${device.deviceName}; requesting")
            requestPermission(device)
        }
    }

    private fun requestPermission(device: UsbDevice) {
        trace(device, "requestPermission", "Requesting USB permission; descriptors unavailable until allowed")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, intent, flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun handlePermissionResult(intent: Intent) {
        val device = getIntentDevice(intent) ?: return
        if (requestedDeviceName != device.deviceName) return
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        trace(device, "handlePermissionResult", "granted=$granted")
        Log.i(TAG, "Permission result for ${device.deviceName}: granted=$granted")
        if (granted) {
            if (_deviceState.value == null && usbManager.deviceList.containsKey(device.deviceName)) {
                inspectAndPublish(device)
            }
        } else {
            Log.w(TAG, "USB permission denied for ${device.deviceName}")
            _connectionNotice.value = "USB access denied. Open Inputs and select the device to retry."
        }
        refreshInputs()
    }

    /**
     * Opens a short-lived control connection purely to read descriptors, resolves the matching
     * routable [AudioDeviceInfo], and publishes the combined [UsbAudioDeviceInfo] snapshot.
     */
    private fun inspectAndPublish(device: UsbDevice) {
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            trace(device, "inspectAndPublish", "FAILED: Android could not open USB control connection")
            Log.e(TAG, "Failed to open control connection to ${device.deviceName}")
            _connectionNotice.value = "Could not open USB input. Check permission and reconnect."
            return
        }

        val streamingInterfaces: List<AudioStreamingInterfaceInfo>
        val rawDescriptors: ByteArray
        val topology: UacTopology
        var clockSampleRates = emptyList<Int>()
        var mixerProfile: PioneerMixerProfile? = null
        var formatGuessed = false
        // Runtime overrides (Recording setup > Mixer profile) let an unknown or mis-detected
        // mixer be driven without a rebuild: forced profile, "class-compliant only", manual wire
        // format / endpoint, duplex and rate-command switches.
        val override = CaptureOverrideStore.load(context, device.vendorId, device.productId)
        if (override.isActive) Log.i(TAG, "${device.deviceName}: capture override active: $override")
        val bestInterface = try {
            rawDescriptors = connection.rawDescriptors ?: ByteArray(0)
            com.audiopro.djmrec.diagnostics.RemoteDiagnostics.descriptors(device.vendorId, device.productId, rawDescriptors, device.deviceName)
            Log.i(TAG, "${device.deviceName}: read ${rawDescriptors.size} bytes of raw descriptors")
            streamingInterfaces = UsbAudioDescriptorParser.findAudioStreamingInterfaces(rawDescriptors)
            topology = UsbAudioDescriptorParser.parseTopology(rawDescriptors)
            mixerProfile = override.resolveProfile(device.vendorId, device.productId)
            clockSampleRates = if (mixerProfile != null) {
                Log.i(TAG, "${device.deviceName}: using ${mixerProfile.displayName} endpoint/vendor clock profile")
                emptyList()
            } else {
                queryClockSampleRates(device, connection, topology)
            }
            Log.i(
                TAG,
                "${device.deviceName}: found ${streamingInterfaces.size} AudioStreaming alternate setting(s): " +
                    streamingInterfaces.joinToString {
                        "if${it.interfaceNumber}/alt${it.alternateSetting} ${it.channelCount}ch " +
                            "${it.bitResolution}bit ep=${it.isochronousInEndpointAddress}"
                    }
            )
                    Log.i(
                    TAG,
                    "${device.deviceName}: UAC topology AC=" +
                        topology.audioControlInterfaces.joinToString(prefix = "[", postfix = "]") {
                            "if${it.interfaceNumber}:v${it.audioClassVersion.toString(16)}"
                        } + " " +
                        "clocks=${topology.clockSources.size} selectors=${topology.clockSelectors.size} " +
                        "features=${topology.featureUnits.size} mixers=${topology.mixerUnits.size} " +
                        "terminals=${topology.inputTerminals.size}/${topology.outputTerminals.size} " +
                            "descriptorRates=${topology.descriptorSampleRates} clockRates=$clockSampleRates"
                    )
            val recordOffset = AllInOneProfile.find(device.vendorId, device.productId)?.recordChannelOffset ?: 0
            val standardBest = UsbAudioDescriptorParser.selectBestStereoInterface(streamingInterfaces.filter {
                it.channelCount >= recordOffset + (if (recordOffset == 0) 1 else 2) &&
                    (mixerProfile?.hasVendorCaptureOverride != true ||
                    (it.interfaceNumber == mixerProfile.vendorCaptureInterface &&
                        it.alternateSetting == mixerProfile.vendorCaptureAlternateSetting &&
                        it.channelCount == mixerProfile.vendorCaptureChannelCount &&
                        it.subframeSize == mixerProfile.vendorCaptureSubframeSize &&
                        it.bitResolution == mixerProfile.vendorCaptureBitResolution))
            })
            val vendorOverride = standardBest ?: mixerProfile?.takeIf { it.hasVendorCaptureOverride }?.let { profile ->
                // Never replace an explicit, conflicting PCM descriptor with guessed bytes.
                if (streamingInterfaces.any { it.interfaceNumber == profile.vendorCaptureInterface &&
                        it.alternateSetting == profile.vendorCaptureAlternateSetting }) return@let null
                Log.i(
                    TAG,
                    "${device.deviceName}: no standard AudioStreaming interface; trying " +
                        "${profile.displayName} vendor-class capture override " +
                        "if${profile.vendorCaptureInterface}/alt${profile.vendorCaptureAlternateSetting}"
                )
                UsbAudioDescriptorParser.findVendorEndpoint(
                    rawDescriptors, profile.vendorCaptureInterface, profile.vendorCaptureAlternateSetting
                )?.copy(
                    channelCount = profile.vendorCaptureChannelCount,
                    bitResolution = profile.vendorCaptureBitResolution,
                    subframeSize = profile.vendorCaptureSubframeSize
                ).also {
                    if (it == null) {
                        Log.w(
                            TAG,
                            "${device.deviceName}: vendor capture override interface has no " +
                                "isochronous IN endpoint either -- descriptor layout doesn't match " +
                                "what was captured when this profile was written"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "${device.deviceName}: using ${profile.displayName} vendor capture format " +
                                "(${it.channelCount}ch/${it.bitResolution}bit); " +
                                "hardwareConfirmed=${profile.isHardwareConfirmed}"
                        )
                    }
                }
            }
            // Last resort for AlphaTheta hardware only: a DJM-V5 with a different product ID, or
            // any future model, that hides its audio behind a vendor-class interface used to be
            // refused outright ("exposes no supported PCM capture format"). Instead, take the
            // largest isochronous IN endpoint on a non-zero alt setting, assume the shared DJM
            // template and say so loudly in the UI -- the pair picker, cadence-based rate
            // detection and the descriptor export make this diagnosable rather than a dead end.
            val genericFallback = vendorOverride ?: run {
                if (device.vendorId != PioneerMixerProfile.ALPHATHETA_VENDOR_ID || streamingInterfaces.isNotEmpty()) return@run null
                val candidate = UsbAudioDescriptorParser.findAnyIsoInEndpoints(rawDescriptors)
                    .filter { it.alternateSetting > 0 && (it.isochronousInMaxPacketSize ?: 0) > 0 }
                    .maxByOrNull { it.isochronousInMaxPacketSize ?: 0 }
                    ?: return@run null
                formatGuessed = true
                Log.w(
                    TAG,
                    "${device.deviceName}: no UAC or profile format; assuming generic AlphaTheta " +
                        "${GENERIC_ALPHATHETA_CHANNELS}ch/${GENERIC_ALPHATHETA_BITS}bit on " +
                        "if${candidate.interfaceNumber}/alt${candidate.alternateSetting} " +
                        "(class ${candidate.interfaceClass}, ep 0x${candidate.isochronousInEndpointAddress?.toString(16)})"
                )
                trace(device, "inspectAndPublish", "generic AlphaTheta fallback if${candidate.interfaceNumber}/alt${candidate.alternateSetting}")
                candidate.copy(
                    channelCount = GENERIC_ALPHATHETA_CHANNELS,
                    bitResolution = GENERIC_ALPHATHETA_BITS,
                    subframeSize = GENERIC_ALPHATHETA_SUBFRAME
                )
            }
            // Manual endpoint / wire format always has the last word.
            if (override.hasFormat || override.hasEndpoint) {
                val manual = override.applyTo(
                    genericFallback, UsbAudioDescriptorParser.findAnyIsoInEndpoints(rawDescriptors))
                if (manual == null) {
                    Log.w(TAG, "${device.deviceName}: manual override names no usable endpoint; using detection result")
                } else {
                    formatGuessed = false
                    Log.i(
                        TAG,
                        "${device.deviceName}: manual USB format if${manual.interfaceNumber}/alt${manual.alternateSetting} " +
                            "ep=0x${manual.isochronousInEndpointAddress?.toString(16)} ${manual.channelCount}ch/" +
                            "${manual.bitResolution}bit/subframe${manual.subframeSize}"
                    )
                }
                manual ?: genericFallback
            } else {
                genericFallback
            }
        } finally {
            // We only needed the descriptors; AAudio/AudioFlinger owns the real data connection.
            connection.close()
        }

        if (bestInterface == null) {
            trace(device, "inspectAndPublish", "FAILED: no supported capture format; configuration logged under MixerCapabilities and UsbDescriptors")
            Log.w(TAG, "${device.deviceName} exposes no usable isochronous IN audio streaming interface")
            _deviceState.value = null
            _connectionNotice.value = AllInOneProfile.find(device.vendorId, device.productId)?.takeIf { it == AllInOneProfile.XDJ_RX3 }?.setupHint
                ?: "${device.productName ?: "This device"} exposes no supported PCM capture format. Try its PC/Mac audio mode, or set a manual USB format under Recording setup > Mixer profile."
            return
        }
        Log.i(
            TAG,
            "${device.deviceName}: selected if${bestInterface.interfaceNumber}/alt${bestInterface.alternateSetting} " +
                "${bestInterface.channelCount}ch/${bestInterface.bitResolution}bit for capture"
        )

        val routedDeviceId = resolveAudioManagerDeviceId(device)
        if (routedDeviceId == null) {
            Log.w(TAG, "${device.deviceName}: no matching AudioManager USB input device found (routing will fail)")
        }

        _deviceState.value = UsbAudioDeviceInfo(
            deviceName = device.deviceName,
            productName = device.productName ?: "USB Audio Device",
            vendorId = device.vendorId,
            productId = device.productId,
            streamingInterfaceNumber = bestInterface.interfaceNumber,
            activeAlternateSetting = bestInterface.alternateSetting,
            isochronousInEndpointAddress = bestInterface.isochronousInEndpointAddress ?: -1,
            isochronousInMaxPacketSize = bestInterface.isochronousInMaxPacketSize ?: -1,
            channelCount = bestInterface.channelCount,
            bitResolution = bestInterface.bitResolution,
            subframeSize = bestInterface.subframeSize,
            supportedSampleRates = mixerProfile?.vendorCaptureSampleRates?.takeIf { it.isNotEmpty() }
                ?: bestInterface.sampleRates.takeIf { it.isNotEmpty() }
                ?: (clockSampleRates + (routedDeviceId?.second ?: emptyList())).distinct().takeIf { it.isNotEmpty() }
                ?: GENERIC_ALPHATHETA_RATES.takeIf { formatGuessed || override.hasFormat || override.hasEndpoint }
                ?: emptyList(),
            audioManagerDeviceId = routedDeviceId?.first ?: -1,
            hasPermission = true,
            isPioneer = isPioneerDevice(device),
            rawDescriptors = rawDescriptors,
            topology = topology,
            formatGuessed = formatGuessed,
            captureOverride = override
        )
        _connectionNotice.value = null
        refreshInputs()
    }

    /**
     * Re-reads descriptors and republishes the current device, e.g. after the user changed a
     * [CaptureOverride]. Callers must have stopped any running capture first (the fresh snapshot
     * may select a different endpoint or format). Returns false when no device is published,
     * it vanished, or permission is missing.
     */
    fun reinspectCurrentDevice(): Boolean {
        val info = _deviceState.value ?: return false
        val device = usbManager.deviceList.values.firstOrNull {
            it.deviceName == info.deviceName && it.vendorId == info.vendorId && it.productId == info.productId
        } ?: return false
        if (!usbManager.hasPermission(device)) return false
        Log.i(TAG, "${device.deviceName}: re-inspecting after capture override change")
        inspectAndPublish(device)
        return true
    }

    private fun queryClockSampleRates(
        device: UsbDevice,
        connection: UsbDeviceConnection,
        topology: UacTopology
    ): List<Int> {
        val rates = linkedSetOf<Int>()
        val recordOffset = AllInOneProfile.find(device.vendorId, device.productId)?.recordChannelOffset ?: 0
        val selected = UsbAudioDescriptorParser.selectBestStereoInterface(topology.audioStreamingInterfaces.filter {
            it.channelCount >= recordOffset + (if (recordOffset == 0) 1 else 2)
        })
        val selectedClock = selected?.let { clockFor(it, topology) }
        topology.clockSources.filter { it.supportsFrequencyControl && it == selectedClock }.forEach { clock ->
            val controlInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.id == clock.interfaceNumber }
            if (controlInterface == null || !connection.claimInterface(controlInterface, true)) {
                Log.w(TAG, "Clock source ${clock.id}: could not claim AC interface ${clock.interfaceNumber}")
                return@forEach
            }
            val buffer = ByteArray(2 + 12 * 32)
            val transferred = try {
                connection.controlTransfer(
                    UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or 0x01,
                    0x82,
                    0x0100,
                    (clock.id shl 8) or clock.interfaceNumber,
                    buffer,
                    buffer.size,
                    500
                )
            } finally {
                connection.releaseInterface(controlInterface)
            }
            if (transferred < 2) {
                Log.w(TAG, "Clock source ${clock.id}: GET_RANGE failed or unsupported ($transferred)")
                return@forEach
            }
            val rangeCount = (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)
            for (rangeIndex in 0 until rangeCount) {
                val base = 2 + rangeIndex * 12
                if (base + 11 >= transferred) break
                val minimum = readLe32(buffer, base)
                val maximum = readLe32(buffer, base + 4)
                rates += CaptureFormatPolicy.ratesInRange(minimum, maximum, readLe32(buffer, base + 8))
            }
        }
        return rates.toList()
    }

    private fun clockFor(
        streaming: AudioStreamingInterfaceInfo,
        topology: UacTopology
    ): ClockSourceInfo? {
        val terminal = (topology.inputTerminals + topology.outputTerminals)
            .firstOrNull { it.id == streaming.terminalLink }
        return topology.clockSources.firstOrNull { it.id == terminal?.clockSourceId }
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /**
     * AAudio/Oboe binds to devices via the *AudioManager* device id space, which is separate
     * from the raw UsbDevice handle. We cross-reference by USB product name, since
     * [AudioDeviceInfo] does not expose vendor/product IDs directly.
     */
    private fun resolveAudioManagerDeviceId(device: UsbDevice): Pair<Int, List<Int>>? {
        val candidates = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        Log.i(
            TAG,
            "AudioManager input devices: " + candidates.joinToString {
                "id=${it.id} type=${it.type} product=${it.productName}"
            }
        )
        val usbInputs = candidates.filter {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val match = usbInputs.firstOrNull { it.address == device.deviceName }
            ?: usbInputs.filter { it.productName?.toString()?.trim() == device.productName?.trim() }
                .singleOrNull()

        return match?.let { it.id to it.sampleRates.toList() }
    }

    /** Re-resolves the negotiated sample rate once the native engine reports the opened stream's rate. */
    fun updateNegotiatedSampleRate(sampleRate: Int) {
        _deviceState.value = _deviceState.value?.copy(negotiatedSampleRate = sampleRate)
    }

    /**
     * Opens (and holds open) a fresh [UsbDeviceConnection] to the currently published device
     * purely for the native libusb capture path, and returns everything
     * `UsbIsoAudioSource`/`AudioEngine.openUsbIso` needs to claim the interface and start
     * pulling isochronous transfers.
     *
     * IMPORTANT: unlike [inspectAndPublish]'s short-lived descriptor-reading connection, the
     * connection opened here is deliberately kept alive in [activeIsoConnection] -- its fd is
     * handed to `libusb_wrap_sys_device()`, and closing the connection while libusb still holds
     * that fd would pull capture out from under it. Call [releaseIsoCaptureConnection] once the
     * native side has fully torn down (after `AudioEngine.close()` returns).
     *
     * Returns null if there is no published device, permission has not been granted, or the
     * connection could not be opened -- callers should fall back to the AAudio path in that case.
     */
    /**
     * Sets the mixer's MIX/REC OUT route via Android's own `UsbDeviceConnection.controlTransfer`
     * API, on the same connection whose fd is about to be handed to libusb.
     *
     * Why here and not in native code: on real DJM-900NXS2 hardware, the route GET/SET requests
     * reliably succeed through this Java API but reliably fail (`LIBUSB_ERROR_BUSY`) through
     * `libusb_control_transfer()` on a `libusb_wrap_sys_device` handle wrapping the very same fd
     * once isochronous transfers are in flight -- regardless of claim/alt-setting ordering.
     *
     * Why this no longer verifies the SET by reading it back: a real USBPcap capture of Pioneer's
     * own Windows Setting Utility toggling this exact output between MIX and another source
     * confirmed two things -- (1) `wValue = ((output+1)<<8)|source` with source `0x0A` for MIX is
     * the correct encoding (the utility sent literally that, for both output 1 and output 5), and
     * (2) the GET response at this wIndex is `00 01 01 01 01` for the *entire* capture -- before
     * the SET, immediately after it, and hundreds of polls later -- never once reflecting the
     * change the utility had just made and the user could see take effect on screen. So this
     * register is not a live route readout (or at least not one the utility itself trusts), and
     * gating success/retry on it -- as this function and its native counterpart used to -- was
     * chasing a signal that was never going to move. The official driver doesn't verify either;
     * it just sends the SET and trusts it. This does the same.
     */
    private fun establishPioneerRoute(
        connection: UsbDeviceConnection,
        profile: PioneerMixerProfile,
        selectedChannelOffset: Int,
        includeMic: Boolean
    ) {
        // DJM-450 is configured after native SET_INTERFACE/SET_CUR, with the actual selected
        // pair. A pre-claim write to the default pair cannot initialize an explicit USB5/6 route.
        if (profile == PioneerMixerProfile.DJM_450) return
        val defaultOutput = profile.defaultCaptureChannelOffset / 2
        val selectedOutput = selectedChannelOffset.takeIf { it >= 0 }?.div(2)
        val outputs = (listOfNotNull(selectedOutput, defaultOutput) + profile.additionalMixOutputs)
            .distinct()
            .filter { it in 0 until profile.outputCount }
        for (output in outputs) {
            writeMixRoute(connection, profile, output, includeMic)
        }
    }

    /** One vendor route SET; returns true when the control transfer was accepted. */
    private fun writeMixRoute(
        connection: UsbDeviceConnection,
        profile: PioneerMixerProfile,
        output: Int,
        includeMic: Boolean
    ): Boolean {
        val setValue = profile.mixRouteValue(output, includeMic)
        if (setValue < 0) return false
        val setResult = connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
            PioneerMixerProfile.ROUTE_SET_REQUEST,
            setValue,
            PioneerMixerProfile.ROUTE_INDEX,
            null,
            0,
            1000
        )
        if (setResult < 0) {
            Log.w(TAG, "${profile.displayName}: route SET output ${output + 1} value 0x${setValue.toString(16)} failed (result=$setResult)")
        } else {
            Log.i(TAG, "${profile.displayName}: sent MIX/REC OUT route SET for output ${output + 1} (value=0x${setValue.toString(16)}, mic=$includeMic) -- not verified by readback, see function doc")
        }
        return setResult >= 0
    }

    /**
     * Requested by the native capture thread (via `AudioEngine.takeRouteFallbackRequest`) after a
     * fully silent first window: route every configurable output to MIX using this connection.
     * Runs on the service's monitor thread -- never on the libusb event thread.
     */
    fun applyRouteFallback(includeMic: Boolean) {
        val connection = activeIsoConnection ?: return
        val profile = _deviceState.value?.pioneerMixerProfile ?: return
        if (profile.routeReadMode == PioneerMixerProfile.RouteReadMode.NONE) return
        Log.i(TAG, "${profile.displayName}: fallback -- routing MIX to all ${profile.outputCount} configurable pairs")
        for (output in 0 until profile.outputCount) writeMixRoute(connection, profile, output, includeMic)
    }

    /**
     * Writes the mixer's USB capture-level register (A9/V10 six-step scale, see
     * [PioneerMixerProfile.CAPTURE_LEVEL_STEPS_DB]). [stepIndex] < 0 leaves the mixer setting alone.
     */
    private fun applyCaptureLevel(connection: UsbDeviceConnection, profile: PioneerMixerProfile, stepIndex: Int) {
        if (stepIndex < 0 || !profile.supportsCaptureLevel) return
        val value = PioneerMixerProfile.captureLevelValue(stepIndex)
        val result = connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
            PioneerMixerProfile.ROUTE_SET_REQUEST,
            value,
            PioneerMixerProfile.CAPTURE_LEVEL_INDEX,
            null,
            0,
            1000
        )
        val db = PioneerMixerProfile.CAPTURE_LEVEL_STEPS_DB.getOrNull(stepIndex)
        if (result < 0) Log.w(TAG, "${profile.displayName}: capture level +$db dB (0x${value.toString(16)}) failed (result=$result)")
        else Log.i(TAG, "${profile.displayName}: capture level set to +$db dB (0x${value.toString(16)})")
    }

    /**
     * @param selectedChannelOffset the user's USB pair (0-based first channel) or
     *   [AUTO_CHANNEL_OFFSET]; the matching MIX output is routed up front so a manual pick on a
     *   write-only model (DJM-V10) records MIX, not whatever the pair carried before.
     * @param includeMic route REC OUT with the mic bus where the model offers a choice.
     * @param captureLevelStep index into [PioneerMixerProfile.CAPTURE_LEVEL_STEPS_DB] or -1.
     */
    fun openIsoCaptureHandle(
        selectedChannelOffset: Int = AUTO_CHANNEL_OFFSET,
        includeMic: Boolean = true,
        captureLevelStep: Int = -1
    ): UsbIsoCaptureHandle? {
        val info = _deviceState.value ?: run {
            Log.w(TAG, "openIsoCaptureHandle: no device currently published")
            return null
        }
        if (activeIsoConnection != null && com.audiopro.djmrec.audio.AudioEngine.isStreamOpen()) {
            // Closing the live connection would yank the fd out from under libusb mid-capture
            // (see releaseIsoCaptureConnection). Callers must stop the running session first.
            Log.w(TAG, "openIsoCaptureHandle: a USB capture session is still open; refusing to replace its connection")
            return null
        }
        val device = usbManager.deviceList.values.firstOrNull {
            it.deviceName == info.deviceName && it.vendorId == info.vendorId && it.productId == info.productId
        } ?: run {
            Log.w(TAG, "openIsoCaptureHandle: ${info.deviceName} is no longer in UsbManager.deviceList")
            return null
        }
        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "openIsoCaptureHandle: no permission for ${device.deviceName}")
            return null
        }

        // Replace any stale connection from a previous session before opening a new one.
        releaseIsoCaptureConnection()

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "openIsoCaptureHandle: openDevice failed for ${device.deviceName}")
            return null
        }
        activeIsoConnection = connection
        info.pioneerMixerProfile?.let { profile ->
            establishPioneerRoute(connection, profile, selectedChannelOffset, includeMic)
            applyCaptureLevel(connection, profile, captureLevelStep)
        }
        val topology = info.topology
        val streaming = topology?.audioStreamingInterfaces?.firstOrNull {
            it.interfaceNumber == info.streamingInterfaceNumber && it.alternateSetting == info.activeAlternateSetting
        }
        // Supported Pioneer profiles use endpoint/vendor clock flow. Entity requests stall
        // some firmware, including the A9, so native code measures the active stream cadence.
        val clock = when {
            info.pioneerMixerProfile != null -> null
            streaming != null -> clockFor(streaming, topology)
            else -> null
        }
        Log.i(
            TAG,
            "openIsoCaptureHandle: if${info.streamingInterfaceNumber}/alt${info.activeAlternateSetting} " +
                "terminal=${streaming?.terminalLink} clock=${clock?.id}@if${clock?.interfaceNumber} " +
                "settable=${clock?.supportsFrequencySet}"
        )

        return UsbIsoCaptureHandle(
            fd = connection.fileDescriptor,
            interfaceNumber = info.streamingInterfaceNumber,
            alternateSetting = info.activeAlternateSetting,
            endpointAddress = info.isochronousInEndpointAddress,
            maxPacketSize = info.isochronousInMaxPacketSize,
            totalChannels = info.channelCount,
            subframeSize = info.subframeSize,
            bitResolution = info.bitResolution,
            rawDescriptors = info.rawDescriptors,
            clockControlInterfaceNumber = clock?.interfaceNumber ?: -1,
            clockSourceId = clock?.id ?: -1,
            clockSupportsFrequencySet = clock?.supportsFrequencySet == true,
            feedbackEndpointAddress = info.streamingInterfaceNumber.let { interfaceNumber ->
                info.topology?.audioStreamingInterfaces
                    ?.firstOrNull { it.interfaceNumber == interfaceNumber && it.alternateSetting == info.activeAlternateSetting }
                    ?.isochronousFeedbackEndpointAddress ?: -1
            },
            feedbackMaxPacketSize = info.streamingInterfaceNumber.let { interfaceNumber ->
                info.topology?.audioStreamingInterfaces
                    ?.firstOrNull { it.interfaceNumber == interfaceNumber && it.alternateSetting == info.activeAlternateSetting }
                    ?.isochronousFeedbackMaxPacketSize ?: -1
            },
            vendorId = info.nativeVendorId,
            productId = info.nativeProductId,
            playbackOverride = info.captureOverride.playbackKeepalive,
            endpointRateOverride = info.captureOverride.endpointRateCommand,
            allowFormatMismatch = info.captureOverride.hasFormat || info.captureOverride.hasEndpoint
        )
    }

    /**
     * Closes the connection opened by [openIsoCaptureHandle], if any. Must only be called once
     * native capture has fully stopped (i.e. after `AudioEngine.close()` returns) -- see that
     * method's contract.
     */
    fun releaseIsoCaptureConnection() {
        activeIsoConnection?.close()
        activeIsoConnection = null
    }
}

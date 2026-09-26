package com.audiopro.djmrec.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.audio.SignalDetector
import com.audiopro.djmrec.ui.components.GhostButton
import com.audiopro.djmrec.ui.components.ListPreference
import com.audiopro.djmrec.ui.components.Preference
import com.audiopro.djmrec.ui.components.PreferenceAction
import com.audiopro.djmrec.ui.components.SettingsNote
import com.audiopro.djmrec.ui.components.SettingsSectionHeader
import com.audiopro.djmrec.ui.components.SrFilledButton
import com.audiopro.djmrec.ui.components.SrOutlinedButton
import com.audiopro.djmrec.ui.components.SrSegmentedButton
import com.audiopro.djmrec.ui.components.SrSlider
import com.audiopro.djmrec.ui.components.SwitchPreference
import com.audiopro.djmrec.ui.theme.SrColor
import com.audiopro.djmrec.update.AppUpdate
import com.audiopro.djmrec.update.UpdateCheckResult
import com.audiopro.djmrec.update.UpdateChecker
import com.audiopro.djmrec.update.UpdateInstaller
import com.audiopro.djmrec.usb.CaptureOverride
import com.audiopro.djmrec.usb.PioneerMixerProfile
import com.audiopro.djmrec.usb.UsbAudioDescriptorParser
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import kotlinx.coroutines.launch

private fun hasNotificationPolicyAccess(context: Context): Boolean = runCatching {
    context.getSystemService(android.app.NotificationManager::class.java)
        ?.isNotificationPolicyAccessGranted == true
}.getOrDefault(false)

private fun isBatteryUnrestricted(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

private fun appDetailsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

/** Summary for a row the connected input cannot use, or that needs a mixer first. */
private fun unavailable(device: UsbAudioDeviceInfo?, purpose: String) =
    if (device == null) "Connect a mixer $purpose" else "Not available on ${device.productName}"

internal fun signed(db: Int) = if (db > 0) "+$db dB" else "$db dB"

/** Settings as one scrolling list (Settings.dc.html in the SET REC design system). */
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val device by viewModel.deviceState.collectAsState()
    val state by viewModel.recordingState.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val locked = saving || !(state is RecordingState.Idle || state is RecordingState.Monitoring || state is RecordingState.Error)

    Column(
        Modifier.fillMaxSize().background(SrColor.Background).verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        if (locked) SettingsNote("Recording settings are locked while a set is recording.")
        RecordingSection(viewModel, device, locked)
        MixerSection(viewModel, device, locked)
        DuringASetSection(viewModel)
        DisplaySection(viewModel)
        AboutSection()
    }
}

/** Choices that apply to every set, whatever is plugged in. */
@Composable
private fun RecordingSection(viewModel: MainViewModel, device: UsbAudioDeviceInfo?, locked: Boolean) {
    val format by viewModel.selectedFormat.collectAsState()
    val gain by viewModel.recordingGainDb.collectAsState()
    val silenceHold by viewModel.silenceHoldMs.collectAsState()
    val confirm by viewModel.confirmStop.collectAsState()
    val enabled = !locked
    val profile = device?.pioneerMixerProfile

    SettingsSectionHeader("Recording")
    Preference(
        "File format",
        summary = "WAV can be repaired if the phone dies mid-set; FLAC files are smaller.",
        enabled = enabled
    ) {
        SrSegmentedButton(
            "File format", viewModel.availableFormats.map { it to it.name }, format, enabled, viewModel::selectFormat
        )
    }

    Preference(
        "Software gain",
        value = signed(gain),
        summary = if (profile?.supportsCaptureLevel == true) "Set the mixer recording level below first."
        else "Applied after capture. 0 dB keeps the input level.",
        enabled = enabled
    ) {
        SrSlider(gain, -12..24, enabled, "Software gain", signed(gain), viewModel::setRecordingGainDb)
    }

    // Only changes what the UI reports, never the audio, so it stays editable mid-set.
    ListPreference(
        "No-signal delay",
        SignalDetector.HOLD_CHOICES_MS.map { it to "${it / 1000} s" },
        silenceHold, viewModel::setSilenceHoldMs
    ) { "$it of silence before showing no signal" }
    SwitchPreference("Confirm before saving", confirm, viewModel::setConfirmStop)
}

/**
 * Rows that depend on the connected input. Sample rate, recording level, input pair and the
 * profile override are remembered per mixer. With no mixer, or one that lacks a control, the row
 * stays in place, greyed out with the reason, so the list never changes shape.
 */
@Composable
private fun MixerDeviceRows(viewModel: MainViewModel, device: UsbAudioDeviceInfo?, locked: Boolean) {
    val selectedRate by viewModel.selectedSampleRate.collectAsState()
    val captureLevel by viewModel.captureLevelStep.collectAsState()
    val includeMic by viewModel.includeMicInMix.collectAsState()
    val pair by viewModel.usbChannelOffset.collectAsState()
    val enabled = !locked
    val profile = device?.pioneerMixerProfile

    val rates = device?.supportedSampleRates?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
    if (rates.size <= 1) {
        Preference(
            "Sample rate",
            summary = when {
                device == null -> "Connect a mixer to choose a rate"
                rates.size == 1 -> "${"${rates[0] / 1000f}".removeSuffix(".0")} kHz · the only rate this input offers"
                else -> "Set by the input"
            },
            enabled = false
        )
    } else {
        val rateOptions = listOf(0 to "Auto") + rates.map { it to "${it / 1000f}".removeSuffix(".0") }
        val rateSummary = if (rates.any { it >= 88_200 }) "96 kHz doubles the file size." else "Auto prefers 48 kHz."
        if (rateOptions.size <= 4) {
            // The last segment carries the unit for the whole row.
            val labelled = rateOptions.mapIndexed { i, (rate, label) ->
                rate to if (i == rateOptions.lastIndex) "$label kHz" else label
            }
            Preference("Sample rate", summary = rateSummary, enabled = enabled) {
                SrSegmentedButton("Sample rate", labelled, selectedRate, enabled, viewModel::setSampleRate)
            }
        } else {
            ListPreference(
                "Sample rate", rateOptions.map { (rate, label) -> rate to if (rate == 0) label else "$label kHz" },
                selectedRate, viewModel::setSampleRate, enabled
            ) { "$it · $rateSummary" }
        }
    }

    if (profile?.supportsCaptureLevel == true) {
        ListPreference(
            "Mixer recording level",
            listOf(-1 to "Mixer setting") +
                PioneerMixerProfile.CAPTURE_LEVEL_STEPS_DB.mapIndexed { index, db -> index to if (db > 0) "+$db dB" else "0 dB" },
            captureLevel, viewModel::setCaptureLevelStep, enabled
        ) { if (it == "Mixer setting") "Uses the level set on the mixer" else it }
    } else {
        Preference("Mixer recording level", summary = unavailable(device, "to set its USB recording level"), enabled = false)
    }

    if (profile?.supportsMicToggle == true) {
        SwitchPreference("Include mixer mic", includeMic, viewModel::setIncludeMicInMix, enabled = enabled)
    } else {
        SwitchPreference("Include mixer mic", false, {}, summary = unavailable(device, "to route its mic"), enabled = false)
    }

    if (device?.requiresIsoCapture == true) {
        val autoSummary = device.allInOneProfile?.recordChannelOffset?.let { "Auto · master return on USB ${it + 1}/${it + 2}" }
            ?: if (profile == null) "Auto · USB 1/2" else "Auto · picks the pair with signal"
        ListPreference(
            "Input channels",
            listOf(-1 to "Auto") + (0 until device.channelCount / 2).map { it * 2 }.map { it to "USB ${it + 1}/${it + 2}" },
            if (pair < 0) -1 else pair, viewModel::setUsbChannelOffset, enabled
        ) { if (it == "Auto") autoSummary else it }
    } else {
        Preference(
            "Input channels",
            summary = if (device == null) "Connect a mixer to choose a USB pair" else "USB 1/2 · this input has one stereo pair",
            enabled = false
        )
    }

}

@Composable
private fun MixerSection(viewModel: MainViewModel, device: UsbAudioDeviceInfo?, locked: Boolean) {
    SettingsSectionHeader(device?.productName?.let { "Mixer · $it" } ?: "Mixer")
    if (device?.formatGuessed == true) {
        SettingsNote(
            "Unverified mixer: the wire format is assumed (12 channels, 24-bit). If audio sounds wrong, " +
                "copy the USB descriptors from Diagnostics and report them.",
            warn = true
        )
    }
    MixerDeviceRows(viewModel, device, locked)
    if (device == null) {
        // Overrides are stored per mixer, so there is nothing to edit until one is connected.
        Preference("Mixer profile", summary = "Connect a mixer to change its profile", enabled = false)
        Preference("Advanced USB format", summary = "Only for a mixer that records silence or noise.", enabled = false)
        return
    }
    val override by viewModel.captureOverride.collectAsState()
    val enabled = !locked
    var advanced by rememberSaveable {
        mutableStateOf(
            override.hasFormat || override.hasEndpoint ||
                override.playbackKeepalive != CaptureOverride.TRISTATE_AUTO ||
                override.endpointRateCommand != CaptureOverride.TRISTATE_AUTO
        )
    }

    val detected = device.detectedMixerProfile?.displayName ?: "unknown mixer"
    ListPreference(
        "Mixer profile",
        listOf(CaptureOverride.PROFILE_AUTO to "Auto ($detected)") +
            PioneerMixerProfile.entries.map { it.name to it.displayName } +
            listOf(CaptureOverride.PROFILE_NONE to "Class-compliant only"),
        override.profile, { choice -> viewModel.updateCaptureOverride { it.copy(profile = choice) } }, enabled
    )
    Preference(
        "Advanced USB format",
        summary = "Only for a mixer that records silence or noise.",
        onClick = { advanced = !advanced },
        modifier = Modifier.semantics { stateDescription = if (advanced) "Expanded" else "Collapsed" },
        trailing = {
            Icon(
                Icons.Filled.ExpandMore, null, Modifier.size(24.dp).rotate(if (advanced) 180f else 0f),
                tint = SrColor.TextSecondary
            )
        }
    )
    if (!advanced) return

    Column(Modifier.padding(start = 16.dp)) {
        ListPreference(
            "Wire channels",
            listOf(0 to "Auto (${device.channelCount})") + CaptureOverride.CHANNEL_CHOICES.map { it to "$it" },
            override.channelCount, { channels -> viewModel.updateCaptureOverride { it.copy(channelCount = channels) } }, enabled
        )
        val containerSelected = CaptureOverride.CONTAINER_CHOICES.indexOfFirst {
            it.first == override.subframeSize && it.second == override.bitResolution
        }
        ListPreference(
            "Sample container",
            listOf(-1 to "Auto (${device.bitResolution}-bit / ${device.subframeSize} B)") +
                CaptureOverride.CONTAINER_CHOICES.mapIndexed { index, (bytes, bits) -> index to "$bits-bit / $bytes B" },
            containerSelected,
            { index ->
                val choice = CaptureOverride.CONTAINER_CHOICES.getOrNull(index)
                viewModel.updateCaptureOverride {
                    it.copy(subframeSize = choice?.first ?: 0, bitResolution = choice?.second ?: 0)
                }
            },
            enabled
        )
        val endpoints = remember(device.rawDescriptors) { UsbAudioDescriptorParser.findAnyIsoInEndpoints(device.rawDescriptors) }
        if (endpoints.isNotEmpty()) {
            val endpointSelected = endpoints.indexOfFirst {
                it.interfaceNumber == override.interfaceNumber && it.alternateSetting == override.alternateSetting &&
                    (override.endpointAddress < 0 || it.isochronousInEndpointAddress == override.endpointAddress)
            }.takeIf { override.hasEndpoint } ?: -1
            ListPreference(
                "Capture endpoint",
                listOf(-1 to "Auto (if${device.streamingInterfaceNumber}/alt${device.activeAlternateSetting})") +
                    endpoints.mapIndexed { index, ep ->
                        index to "if${ep.interfaceNumber}/alt${ep.alternateSetting} ep${ep.isochronousInEndpointAddress?.toString(16)} " +
                            "${ep.isochronousInMaxPacketSize} B" + if (ep.interfaceClass == 255) " vendor" else ""
                    },
                endpointSelected,
                { index ->
                    val ep = endpoints.getOrNull(index)
                    viewModel.updateCaptureOverride {
                        it.copy(
                            interfaceNumber = ep?.interfaceNumber ?: -1,
                            alternateSetting = ep?.alternateSetting ?: -1,
                            endpointAddress = ep?.isochronousInEndpointAddress ?: -1
                        )
                    }
                },
                enabled
            )
        }
        val triState = listOf(
            CaptureOverride.TRISTATE_AUTO to "Auto",
            CaptureOverride.TRISTATE_ON to "On",
            CaptureOverride.TRISTATE_OFF to "Off"
        )
        ListPreference(
            "Silent playback keepalive", triState, override.playbackKeepalive,
            { value -> viewModel.updateCaptureOverride { it.copy(playbackKeepalive = value) } }, enabled
        )
        ListPreference(
            "Sample-rate command", triState, override.endpointRateCommand,
            { value -> viewModel.updateCaptureOverride { it.copy(endpointRateCommand = value) } }, enabled
        )
    }
    if (override.isActive) {
        PreferenceAction(indent = true) { SrOutlinedButton("Reset to auto", viewModel::resetCaptureOverride, enabled) }
    }
}

@Composable
private fun DuringASetSection(viewModel: MainViewModel) {
    val context = LocalContext.current
    val doNotDisturb by viewModel.doNotDisturbWhileRecording.collectAsState()
    val batterySaverScreen by viewModel.batterySaverScreen.collectAsState()
    val keepScreen by viewModel.keepScreenOn.collectAsState()

    // Both grants are made in Android settings, which give the app no callback: re-read on resume.
    var policyAccess by remember { mutableStateOf(hasNotificationPolicyAccess(context)) }
    var batteryUnrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                policyAccess = hasNotificationPolicyAccess(context)
                batteryUnrestricted = isBatteryUnrestricted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsSectionHeader("During a set")
    val needsAccess = doNotDisturb && !policyAccess
    SwitchPreference(
        "Silence calls and notifications", doNotDisturb, viewModel::setDoNotDisturbWhileRecording,
        summary = if (needsAccess) "Needs Do Not Disturb access to work." else "Alarms still ring.",
        summaryWarn = needsAccess
    )
    if (needsAccess) {
        PreferenceAction {
            SrOutlinedButton("Allow access", {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            })
        }
    }
    Preference(
        "Unrestricted battery use",
        summary = if (batteryUnrestricted) "Allowed" else "Recommended so long sets aren’t stopped",
        summaryWarn = !batteryUnrestricted,
        onClick = {
            // Once exempt, Android's request dialog no longer appears; the app page is where it is undone.
            context.startActivity(
                if (batteryUnrestricted) appDetailsIntent(context)
                else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            )
        },
        trailing = { OpenInNewIcon() }
    )
    SwitchPreference(
        "Battery saver screen", batterySaverScreen, viewModel::setBatterySaverScreen,
        summary = "Dims to a black timer after 30 s without a touch."
    )
    SwitchPreference("Keep screen on", keepScreen, viewModel::setKeepScreenOn)
}

@Composable
private fun DisplaySection(viewModel: MainViewModel) {
    val waveform by viewModel.waveformEnabled.collectAsState()
    val smooth by viewModel.smoothWaveform.collectAsState()
    SettingsSectionHeader("Display")
    SwitchPreference("Live waveform", waveform, viewModel::setWaveformEnabled)
    SwitchPreference(
        "Smooth scrolling", smooth, viewModel::setSmoothWaveform,
        summary = "Turn off to save battery.", enabled = waveform
    )
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    var pendingPermissionUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    var installRequest by remember { mutableStateOf<AppUpdate?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateError by remember { mutableStateOf(false) }
    val unknownSourcesPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val update = pendingPermissionUpdate
        pendingPermissionUpdate = null
        if (update != null && context.packageManager.canRequestPackageInstalls()) {
            installRequest = update
        } else if (update != null) {
            updateError = true
            updateStatus = "Install permission was not granted."
        }
    }

    LaunchedEffect(installRequest?.tag) {
        val update = installRequest ?: return@LaunchedEffect
        updateBusy = true
        updateError = false
        updateStatus = "Downloading ${update.version}…"
        runCatching { UpdateInstaller.download(context.applicationContext, update) }
            .onSuccess { apk ->
                updateStatus = "Download verified. Opening the Android installer…"
                runCatching { UpdateInstaller.install(context, apk) }
                    .onFailure { error ->
                        updateError = true
                        updateStatus = error.message ?: "Could not open the Android installer"
                    }
            }
            .onFailure { error ->
                updateError = true
                updateStatus = error.message ?: "Update download failed"
            }
        updateBusy = false
        installRequest = null
    }

    fun checkForUpdates() {
        scope.launch {
            updateBusy = true
            updateError = false
            updateStatus = "Checking for updates…"
            when (val result = UpdateChecker.checkNow(context.applicationContext)) {
                is UpdateCheckResult.Available -> {
                    availableUpdate = result.update
                    updateStatus = null
                }
                UpdateCheckResult.Current -> {
                    availableUpdate = null
                    updateStatus = "Set Recorder ${BuildConfig.VERSION_NAME} · up to date"
                }
                is UpdateCheckResult.Failed -> {
                    updateError = true
                    updateStatus = result.message
                }
            }
            updateBusy = false
        }
    }

    fun requestInstall(update: AppUpdate) {
        if (context.packageManager.canRequestPackageInstalls()) {
            installRequest = update
        } else {
            pendingPermissionUpdate = update
            updateStatus = "Allow Set Recorder to install updates, then come back here."
            unknownSourcesPermission.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
        }
    }

    SettingsSectionHeader("About")
    val update = availableUpdate
    Preference(
        "App updates",
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        summary = updateStatus
            ?: update?.let { "Version ${it.version} is available" }
            ?: "Set Recorder ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        summaryWarn = updateError,
        trailing = {
            when {
                updateBusy -> CircularProgressIndicator(Modifier.size(24.dp), color = SrColor.TextPrimary, strokeWidth = 2.dp)
                update != null -> SrFilledButton("Install", { requestInstall(update) })
                else -> GhostButton("Check", ::checkForUpdates)
            }
        }
    )
    Preference(
        "App info",
        onClick = { context.startActivity(appDetailsIntent(context)) },
        trailing = { OpenInNewIcon() }
    )
}

@Composable
private fun OpenInNewIcon() {
    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(20.dp), tint = SrColor.TextSecondary)
}

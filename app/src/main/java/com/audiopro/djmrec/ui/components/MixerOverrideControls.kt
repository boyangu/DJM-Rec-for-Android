package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.ui.MainViewModel
import com.audiopro.djmrec.ui.theme.TextSecondary
import com.audiopro.djmrec.usb.CaptureOverride
import com.audiopro.djmrec.usb.PioneerMixerProfile
import com.audiopro.djmrec.usb.UsbAudioDescriptorParser
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo

/**
 * Field controls for driving a mixer the app does not (fully) know: force one of the built-in
 * profiles, disable vendor routing entirely, or hand-enter the USB wire format, endpoint,
 * duplex keepalive and rate-command behaviour. Every choice is stored per mixer and applied by
 * re-reading the device; nothing here needs a rebuild.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MixerOverrideControls(device: UsbAudioDeviceInfo, viewModel: MainViewModel, enabled: Boolean) {
    val override by viewModel.captureOverride.collectAsState()
    var advanced by rememberSaveable { mutableStateOf(override.hasFormat || override.hasEndpoint ||
        override.playbackKeepalive != CaptureOverride.TRISTATE_AUTO ||
        override.endpointRateCommand != CaptureOverride.TRISTATE_AUTO) }

    Text("Mixer profile", style = MaterialTheme.typography.titleSmall)
    val detected = device.detectedMixerProfile
    val profileOptions = listOf(
        CaptureOverride.PROFILE_AUTO to ("Auto" + (detected?.let { " (${it.displayName})" } ?: " (unknown mixer)"))
    ) + PioneerMixerProfile.entries.map { it.name to it.displayName } +
        listOf(CaptureOverride.PROFILE_NONE to "Class-compliant only")
    Chips(profileOptions, override.profile, enabled) { choice ->
        viewModel.updateCaptureOverride { it.copy(profile = choice) }
    }
    Text(
        "Applied: ${device.profileDescription}. Forcing a profile applies that model's routing " +
            "commands, keepalive and rate handling to this mixer -- use DJM-V5 or DJM-A9 for an " +
            "unrecognised AlphaTheta mixer; Class-compliant only sends no vendor commands at all.",
        style = MaterialTheme.typography.bodySmall, color = TextSecondary
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { advanced = !advanced }, enabled = true) {
            Text(if (advanced) "Hide advanced USB format" else "Advanced USB format")
        }
        if (override.isActive) {
            TextButton(onClick = viewModel::resetCaptureOverride, enabled = enabled) { Text("Reset to auto") }
        }
    }
    if (!advanced) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Wire channels", style = MaterialTheme.typography.labelLarge)
        Chips(
            listOf(0 to "Auto (${device.channelCount})") + CaptureOverride.CHANNEL_CHOICES.map { it to "$it" },
            override.channelCount, enabled
        ) { channels -> viewModel.updateCaptureOverride { it.copy(channelCount = channels) } }

        Text("Sample container", style = MaterialTheme.typography.labelLarge)
        val containerSelected = CaptureOverride.CONTAINER_CHOICES.indexOfFirst {
            it.first == override.subframeSize && it.second == override.bitResolution
        }
        Chips(
            listOf(-1 to "Auto (${device.bitResolution}-bit / ${device.subframeSize} B)") +
                CaptureOverride.CONTAINER_CHOICES.mapIndexed { index, (bytes, bits) -> index to "$bits-bit / $bytes B" },
            containerSelected, enabled
        ) { index ->
            val choice = CaptureOverride.CONTAINER_CHOICES.getOrNull(index)
            viewModel.updateCaptureOverride {
                it.copy(subframeSize = choice?.first ?: 0, bitResolution = choice?.second ?: 0)
            }
        }

        val endpoints = UsbAudioDescriptorParser.findAnyIsoInEndpoints(device.rawDescriptors)
        if (endpoints.isNotEmpty()) {
            Text("Capture endpoint", style = MaterialTheme.typography.labelLarge)
            val endpointSelected = endpoints.indexOfFirst {
                it.interfaceNumber == override.interfaceNumber && it.alternateSetting == override.alternateSetting &&
                    (override.endpointAddress < 0 || it.isochronousInEndpointAddress == override.endpointAddress)
            }.takeIf { override.hasEndpoint } ?: -1
            Chips(
                listOf(-1 to "Auto (if${device.streamingInterfaceNumber}/alt${device.activeAlternateSetting})") +
                    endpoints.mapIndexed { index, ep ->
                        index to "if${ep.interfaceNumber}/alt${ep.alternateSetting} ep${ep.isochronousInEndpointAddress?.toString(16)} " +
                            "${ep.isochronousInMaxPacketSize}B" + if (ep.interfaceClass == 255) " vendor" else ""
                    },
                endpointSelected, enabled
            ) { index ->
                val ep = endpoints.getOrNull(index)
                viewModel.updateCaptureOverride {
                    it.copy(
                        interfaceNumber = ep?.interfaceNumber ?: -1,
                        alternateSetting = ep?.alternateSetting ?: -1,
                        endpointAddress = ep?.isochronousInEndpointAddress ?: -1
                    )
                }
            }
        }

        Text("Silent playback keepalive", style = MaterialTheme.typography.labelLarge)
        Chips(triStateOptions(), override.playbackKeepalive, enabled) { value ->
            viewModel.updateCaptureOverride { it.copy(playbackKeepalive = value) }
        }
        Text("Endpoint sample-rate command", style = MaterialTheme.typography.labelLarge)
        Chips(triStateOptions(), override.endpointRateCommand, enabled) { value ->
            viewModel.updateCaptureOverride { it.copy(endpointRateCommand = value) }
        }
        Text(
            "Most DJM mixers only emit audio while the host also streams silence to them and after a " +
                "sample-rate command on the capture endpoint. If a manual profile gives digital silence, " +
                "try keepalive On and rate command On; if capture fails to open, try Off. Wrong channel " +
                "count or container produces noise or a wrong-speed recording -- check the meters and " +
                "make a short test file.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary
        )
    }
}

private fun triStateOptions() = listOf(
    CaptureOverride.TRISTATE_AUTO to "Auto",
    CaptureOverride.TRISTATE_ON to "On",
    CaptureOverride.TRISTATE_OFF to "Off"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, enabled: Boolean, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                enabled = enabled,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 40.dp)
            )
        }
    }
}

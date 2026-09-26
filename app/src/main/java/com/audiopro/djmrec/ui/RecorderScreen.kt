package com.audiopro.djmrec.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.audio.*
import com.audiopro.djmrec.ui.components.*
import com.audiopro.djmrec.ui.theme.*
import java.util.Locale

/** Fixed recording workspace; detailed controls live in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(viewModel: MainViewModel, onOpenLibrary: () -> Unit = {}) {
    val device by viewModel.deviceState.collectAsState()
    val state by viewModel.recordingState.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val levels by viewModel.levels.collectAsState()
    val elapsed by viewModel.elapsedMillis.collectAsState()
    val waveform by viewModel.waveformEnabled.collectAsState()
    val smooth by viewModel.smoothWaveform.collectAsState()
    val confirmStop by viewModel.confirmStop.collectAsState()
    val health by viewModel.recordingHealth.collectAsState()
    val format by viewModel.selectedFormat.collectAsState()
    val gain by viewModel.recordingGainDb.collectAsState()
    val markers by viewModel.markerCount.collectAsState()
    val saved by viewModel.lastSaved.collectAsState()
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var stopPrompt by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var inputsOpen by rememberSaveable { mutableStateOf(false) }
    val connectionNotice by viewModel.connectionNotice.collectAsState()
    val active = state is RecordingState.Recording || state is RecordingState.Paused
    val signal by viewModel.signalPresent.collectAsState()
    if (inputsOpen) InputPicker(viewModel) { inputsOpen = false }
    if (setupOpen) ModalBottomSheet(onDismissRequest = { setupOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Recording setup", style = MaterialTheme.typography.headlineSmall)
            RecordingSetupControls(viewModel)
            Button(onClick = { setupOpen = false }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
    if (stopPrompt) AlertDialog(onDismissRequest = { stopPrompt = false },
        title = { Text("Save this set?") }, text = { Text("Recording will finish. Input monitoring stays ready for your next set.") },
        confirmButton = { TextButton(onClick = { stopPrompt = false; viewModel.stopRecording() }) { Text("Stop & save") } },
        dismissButton = { TextButton(onClick = { stopPrompt = false }) { Text("Keep recording") } })
    if (detailsOpen) AlertDialog(onDismissRequest = { detailsOpen = false }, title = { Text("Input status") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text((state as? RecordingState.Error)?.message ?: health.message)
            Text(device?.allInOneProfile?.setupHint ?: device?.pioneerMixerProfile?.let {
                if (it.isHardwareConfirmed) "Recording confirmed on ${it.displayName}."
                else "${it.displayName}: implemented profile, physical validation pending."
            } ?: "Use a USB audio data cable. Grant audio and USB permission when prompted.")
            if (health.freeBytes in 1 until Long.MAX_VALUE)
                Text(String.format(Locale.US, "%.1f GB available", health.freeBytes / 1_073_741_824.0))
        } }, confirmButton = { TextButton(onClick = { detailsOpen = false }) { Text("OK") } })
    val inputHeader: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (device != null) AccentGreen else TextSecondary, CircleShape))
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable { inputsOpen = true }.padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center) {
                Text(device?.productName ?: "Connect your mixer", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                Text(device?.let { "${it.preferredSampleRate / 1000f} kHz / ${it.bitResolution}-bit / USB" }
                    ?: "USB audio input", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            }
            IconButton(onClick = { inputsOpen = true }) {
                Icon(Icons.Default.Usb, "Choose audio input")
            }
            FilledTonalIconButton(onClick = { setupOpen = true }) { Icon(Icons.Default.Tune, "Recording setup") }
        }
    }
    // Only before a set: once recording has started the phone is already however it is, and a
    // prompt would just be taking space away from the meters.
    val preflight: @Composable () -> Unit = {
        if (!active && !saving) SetPreflightBanner(viewModel)
    }
    val signalPanel: @Composable () -> Unit = {
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(20.dp), color = SurfaceDark) {
            BoxWithConstraints(Modifier.padding(12.dp)) {
                val compact = maxHeight < 160.dp
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        val label = when {
                            saving -> "SAVING"
                            state is RecordingState.Recording -> "REC"
                            state is RecordingState.Paused -> "PAUSED"
                            state is RecordingState.Preparing -> "ARMING"
                            state is RecordingState.Monitoring -> if (signal) "INPUT LIVE" else "ARMED / NO SIGNAL"
                            else -> "STANDBY"
                        }
                        AnimatedContent(targetState = label, label = "captureStatus") { status ->
                            Text(status, color = if (active) AccentRed else AccentGreen,
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                        Text(if (waveform) "RGB" else "METERS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    if (waveform && !compact) LiveRgbWaveform(viewModel.waveformBins, Modifier.fillMaxWidth().weight(1f), smooth = smooth,
                        active = state is RecordingState.Monitoring || active, onVisible = viewModel::setWaveformVisible)
                    else if (!compact) Spacer(Modifier.weight(1f))
                    StereoVuMeter(levels, active = state is RecordingState.Monitoring || active)
                }
            }
        }
    }
    val transport: @Composable (Boolean) -> Unit = { compact ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact) TextButton(onClick = { detailsOpen = true }, modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            val message = when {
                saving -> "Finalizing your recording..."
                state is RecordingState.Error -> (state as RecordingState.Error).message
                active -> if (levels.left.isClipping || levels.right.isClipping) "Clipping: lower mixer output" else health.message
                device == null -> connectionNotice ?: "Connect mixer, grant USB access, check signal"
                else -> health.message
            }
            Text(message, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = TextSecondary)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(elapsedText(elapsed), style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace))
                Text("${format.name} / ${if (gain > 0) "+" else ""}$gain dB", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            }
            OutlinedButton(onClick = viewModel::addTrackMarker, enabled = state is RecordingState.Recording && !saving) {
                Icon(Icons.Default.BookmarkAdd, null, Modifier.size(18.dp)); Text(" Mark ($markers)")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (active) {
                OutlinedButton(onClick = { if (state is RecordingState.Paused) viewModel.resumeRecording() else viewModel.pauseRecording() },
                    enabled = !saving, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Icon(if (state is RecordingState.Paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                    Text(if (state is RecordingState.Paused) "Resume" else "Pause")
                }
                Button(onClick = { if (confirmStop) stopPrompt = true else viewModel.stopRecording() }, enabled = !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Stop, null)
                    Text(if (saving) " Saving" else " Save set")
                }
            } else {
                Button(onClick = viewModel::startRecording, enabled = device != null && state !is RecordingState.Preparing && !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed, contentColor = BackgroundDark)) {
                    if (state is RecordingState.Preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.FiberManualRecord, null)
                    Text(if (state is RecordingState.Preparing) " Arming input" else " Record set", fontWeight = FontWeight.Bold)
                }
            }
        }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // The activity is locked to portrait, so this wide branch never fires from a rotation.
        // It is still reachable: Android ignores android:screenOrientation in multi-window, so a
        // split-screen or foldable window can be wider than it is tall.
        if (maxWidth >= 600.dp && maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    inputHeader()
                    Box(Modifier.weight(1f)) { signalPanel() }
                }
                Column(Modifier.weight(1f).align(Alignment.CenterVertically),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    preflight()
                    transport(true)
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                inputHeader()
                preflight()
                Box(Modifier.weight(1f)) { signalPanel() }
                transport(false)
            }
        }
    }
    saved?.let { recording ->
        AlertDialog(onDismissRequest = viewModel::dismissSavedRecording, title = { Text("Set saved") },
            text = { Text("${recording.name}\n${elapsedText(recording.durationMillis)} / Music/DJMRec") },
            confirmButton = { TextButton(onClick = { viewModel.dismissSavedRecording(); onOpenLibrary() }) { Text("Open sets") } },
            dismissButton = { TextButton(onClick = viewModel::dismissSavedRecording) { Text("Done") } })
    }
}

@Composable
internal fun RecordingSetupControls(viewModel: MainViewModel) {
    val device by viewModel.deviceState.collectAsState()
    val state by viewModel.recordingState.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val format by viewModel.selectedFormat.collectAsState()
    val gain by viewModel.recordingGainDb.collectAsState()
    val pair by viewModel.usbChannelOffset.collectAsState()
    val includeMic by viewModel.includeMicInMix.collectAsState()
    val captureLevel by viewModel.captureLevelStep.collectAsState()
    val selectedRate by viewModel.selectedSampleRate.collectAsState()
    val enabled = !saving && (state is RecordingState.Idle || state is RecordingState.Monitoring || state is RecordingState.Error)
    val profile = device?.pioneerMixerProfile
    if (!enabled) Text("Capture settings locked while recording.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    if (device?.formatGuessed == true) {
        Text("Unverified mixer profile: the wire format is assumed (12 channels, 24-bit). If audio sounds wrong, " +
            "open Diagnostics, copy the USB descriptors and report them so the profile can be completed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Text("File format", style = MaterialTheme.typography.titleSmall)
    FormatSelector(format, viewModel.availableFormats, enabled, viewModel::selectFormat)
    val rates = device?.supportedSampleRates?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
    if (rates.size > 1) {
        Text("Sample rate", style = MaterialTheme.typography.titleSmall)
        OptionChips(
            options = listOf(0 to "Auto") + rates.map { it to "${it / 1000f} kHz".replace(".0 kHz", " kHz") },
            selected = selectedRate, enabled = enabled, onSelect = viewModel::setSampleRate
        )
        Text("Auto prefers 48 kHz. 96 kHz doubles file size and USB bandwidth.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Gain: ${if (gain > 0) "+" else ""}$gain dB", modifier = Modifier.weight(1f))
        TextButton(onClick = { viewModel.setRecordingGainDb(0) }, enabled = enabled) { Text("Reset to 0 dB") }
    }
    Slider(gain.toFloat(), { viewModel.setRecordingGainDb(it.toInt()) }, enabled = enabled,
        valueRange = -12f..24f, steps = 35, modifier = Modifier.semantics { contentDescription = "Recording gain in decibels" })
    Text("Software gain applied after capture; 0 dB preserves input level. Keep peaks below 0 dBFS.",
        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    if (profile?.supportsCaptureLevel == true) {
        Text("Mixer USB recording level", style = MaterialTheme.typography.titleSmall)
        OptionChips(
            options = listOf(-1 to "Mixer setting") + com.audiopro.djmrec.usb.PioneerMixerProfile.CAPTURE_LEVEL_STEPS_DB
                .mapIndexed { index, db -> index to (if (db > 0) "+$db dB" else "0 dB") },
            selected = captureLevel, enabled = enabled, onSelect = viewModel::setCaptureLevelStep
        )
        Text("Sets the ${profile.displayName}'s own USB send level (the Setting Utility's recording level), " +
            "applied in the mixer before the audio reaches the phone. Prefer this over software gain.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
    if (profile?.supportsMicToggle == true) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Include microphone", style = MaterialTheme.typography.titleSmall)
                Text("Route REC OUT with the mic bus (on) or REC OUT without mic (off) to the recorded USB pair.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Switch(includeMic, viewModel::setIncludeMicInMix, enabled = enabled)
        }
    }
    if (device != null) {
        MixerOverrideControls(device!!, viewModel, enabled)
    }
    if (device?.requiresIsoCapture == true) {
        Text("Stereo input pair", style = MaterialTheme.typography.titleSmall)
        ChannelPairSelector(pair, device!!.channelCount / 2, enabled, viewModel::setUsbChannelOffset)
        Text(device?.allInOneProfile?.recordChannelOffset?.let { "Auto: master return on USB ${it + 1}/${it + 2}." }
            ?: if (profile == null) "Auto uses USB 1/2. Choose another pair to audition it before recording."
            else "Auto locks an audible pair. A chosen pair is routed to MIX/REC OUT on the mixer first " +
                "(default USB ${profile.defaultCaptureChannelOffset + 1}/${profile.defaultCaptureChannelOffset + 2}); " +
                "S11 uses its dedicated REC OUT. Selection is remembered per mixer.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

internal fun elapsedText(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

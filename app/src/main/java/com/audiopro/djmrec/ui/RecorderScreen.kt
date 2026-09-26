package com.audiopro.djmrec.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiopro.djmrec.audio.*
import com.audiopro.djmrec.ui.components.*
import com.audiopro.djmrec.ui.theme.SrColor
import com.audiopro.djmrec.usb.UsbAudioDeviceInfo
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

private val HeadStyle = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontFeatureSettings = "tnum")
private val RecStyle = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp,
    letterSpacing = 1.6.sp
)
private val TimerStyle = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, fontSize = 36.sp, lineHeight = 44.sp,
    letterSpacing = (-1).sp, fontFeatureSettings = "tnum"
)
private val LabelStyle = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp,
    letterSpacing = 0.9.sp
)
private val SmallStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val ButtonShape = RoundedCornerShape(10.dp)

/**
 * The recording workspace (Recorder.dc.html): the input card, a signal panel with the clock,
 * recording timer, waveform and meters, and the transport. Every recording option lives in
 * Settings; the top bar's setup button goes there.
 */
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
    val connectionNotice by viewModel.connectionNotice.collectAsState()
    val signal by viewModel.signalPresent.collectAsState()
    var stopPrompt by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var inputsOpen by rememberSaveable { mutableStateOf(false) }
    val active = state is RecordingState.Recording || state is RecordingState.Paused
    val locked = saving || !(state is RecordingState.Idle || state is RecordingState.Monitoring || state is RecordingState.Error)

    if (inputsOpen) InputPicker(viewModel) { inputsOpen = false }
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

    val deviceCard: @Composable () -> Unit = {
        DeviceStatusCard(
            device, "${format.name} · ${signed(gain)}", connectionNotice,
            rescanEnabled = !locked, onRescan = viewModel::rescanUsbDevices, onOpenInputs = { inputsOpen = true }
        )
    }
    val signalPanel: @Composable () -> Unit = {
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(16.dp), color = SrColor.Surface) {
            BoxWithConstraints(Modifier.padding(16.dp)) {
                val compact = maxHeight < 200.dp
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        WallClock()
                        when {
                            active -> RecIndicator(paused = state is RecordingState.Paused)
                            else -> StatusLabel(
                                when {
                                    saving -> "SAVING"
                                    state is RecordingState.Preparing -> "ARMING"
                                    state is RecordingState.Monitoring -> if (signal) "INPUT LIVE" else "ARMED / NO SIGNAL"
                                    else -> "STANDBY"
                                },
                                live = state is RecordingState.Monitoring && signal
                            )
                        }
                    }
                    if (active) {
                        Row(Modifier.fillMaxWidth().offset(y = (-4).dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(elapsedText(elapsed), style = TimerStyle, color = SrColor.TextPrimary, maxLines = 1, softWrap = false)
                            OutlinedButton(
                                onClick = viewModel::showSaverNow, shape = ButtonShape,
                                border = BorderStroke(1.dp, SrColor.LineStrong),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = SrColor.TextSecondary),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                modifier = Modifier.heightIn(min = 40.dp)
                            ) { Text("Battery saver") }
                        }
                    }
                    val live = state is RecordingState.Monitoring || active
                    if (waveform && !compact) {
                        LiveBandWaveform(viewModel.waveformBins, Modifier.fillMaxWidth().weight(1f), smooth = smooth,
                            active = live, onVisible = viewModel::setWaveformVisible)
                    } else if (!compact) Spacer(Modifier.weight(1f))
                    StereoVuMeter(levels, active = live)
                }
            }
        }
    }
    val transport: @Composable (Boolean) -> Unit = { compact ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = viewModel::addTrackMarker,
                    enabled = state is RecordingState.Recording && !saving,
                    shape = ButtonShape,
                    colors = ButtonDefaults.textButtonColors(contentColor = SrColor.TextPrimary,
                        disabledContentColor = SrColor.DisabledContent),
                    modifier = Modifier.semantics { contentDescription = "Add track marker, $markers so far" }
                ) {
                    Icon(Icons.Default.BookmarkAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("$markers")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (active) {
                    OutlinedButton(
                        onClick = { if (state is RecordingState.Paused) viewModel.resumeRecording() else viewModel.pauseRecording() },
                        enabled = !saving, shape = ButtonShape,
                        border = BorderStroke(1.dp, if (saving) SrColor.DisabledFill else SrColor.LineStrong),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SrColor.TextPrimary,
                            disabledContentColor = SrColor.DisabledContent),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                    ) {
                        Icon(if (state is RecordingState.Paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state is RecordingState.Paused) "Resume" else "Pause")
                    }
                    Button(
                        onClick = { if (confirmStop) stopPrompt = true else viewModel.stopRecording() },
                        enabled = !saving, shape = ButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SrColor.Inverse, contentColor = SrColor.OnInverse,
                            disabledContainerColor = SrColor.DisabledFill, disabledContentColor = SrColor.DisabledContent),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = SrColor.DisabledContent, strokeWidth = 2.dp)
                        else Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (saving) "Saving" else "Stop & save")
                    }
                } else {
                    val arming = state is RecordingState.Preparing
                    Button(
                        onClick = viewModel::startRecording,
                        enabled = device != null && !arming && !saving, shape = ButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SrColor.Rec, contentColor = SrColor.OnInverse,
                            disabledContainerColor = SrColor.DisabledFill, disabledContentColor = SrColor.DisabledContent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    ) {
                        if (arming) CircularProgressIndicator(Modifier.size(18.dp), color = SrColor.DisabledContent, strokeWidth = 2.dp)
                        else Icon(Icons.Default.FiberManualRecord, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (arming) "Arming input" else "Record set")
                    }
                }
            }
            if (!compact) {
                val message = when {
                    saving -> "Finalizing your recording…"
                    state is RecordingState.Error -> (state as RecordingState.Error).message
                    active -> if (levels.left.isClipping || levels.right.isClipping) "Clipping: lower mixer output" else health.message
                    device == null -> connectionNotice ?: "Connect mixer, grant USB access, check signal"
                    else -> health.message
                }
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Input status") { detailsOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(message, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = SmallStyle, color = SrColor.TextSecondary)
                    Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = SrColor.TextSecondary)
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(SrColor.Background)
        .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        // The activity is locked to portrait, so this wide branch never fires from a rotation.
        // It is still reachable: Android ignores android:screenOrientation in multi-window, so a
        // split-screen or foldable window can be wider than it is tall.
        if (maxWidth >= 600.dp && maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    deviceCard()
                    Box(Modifier.weight(1f)) { signalPanel() }
                }
                Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
                    transport(true)
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                deviceCard()
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

/** The connected input: name, live dot, format line and profile line, with a rescan button. */
@Composable
private fun DeviceStatusCard(
    device: UsbAudioDeviceInfo?,
    captureText: String,
    notice: String?,
    rescanEnabled: Boolean,
    onRescan: () -> Unit,
    onOpenInputs: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = SrColor.Surface) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Choose audio input", onClick = onOpenInputs)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Usb, null, Modifier.size(22.dp), tint = SrColor.TextSecondary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(if (device != null) SrColor.Live else SrColor.LineStrong, CircleShape))
                    Text(device?.productName ?: "No USB mixer connected", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                        color = SrColor.TextPrimary)
                }
                val lines = if (device == null) {
                    listOf(notice ?: "Connect the mixer's USB audio port with a data / OTG cable, then scan.")
                } else {
                    val rate = "${device.preferredSampleRate / 1000f}".removeSuffix(".0")
                    listOf(
                        "$rate kHz · ${device.bitResolution}-bit · $captureText · ${device.channelCount} ch",
                        profileLine(device)
                    )
                }
                lines.forEach { Text(it, style = SmallStyle, color = SrColor.TextSecondary) }
            }
            IconButton(onClick = onRescan, enabled = rescanEnabled) {
                Icon(Icons.Default.Refresh, "Rescan USB devices",
                    tint = if (rescanEnabled) SrColor.TextSecondary else SrColor.DisabledContent)
            }
        }
    }
}

private fun profileLine(device: UsbAudioDeviceInfo): String = when {
    device.captureOverride.isActive -> device.profileDescription
    device.formatGuessed -> "Unverified profile · verify signal before recording"
    device.pioneerMixerProfile?.isHardwareConfirmed == true -> "Recording confirmed on this model"
    device.pioneerMixerProfile != null -> "Experimental profile · verify signal before recording"
    else -> "Class-compliant USB input · verify signal before recording"
}

/** Time of day, updated on the minute. */
@Composable
private fun WallClock() {
    val context = LocalContext.current
    val format = remember { DateFormat.getTimeFormat(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    Text(format.format(Date(now)), style = HeadStyle, color = SrColor.TextPrimary, maxLines = 1)
}

/** Blinking red REC while recording; a still grey dot and PAUSED while paused. */
@Composable
private fun RecIndicator(paused: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (paused) {
            Box(Modifier.size(10.dp).background(SrColor.LineStrong, CircleShape))
            Text("PAUSED", style = RecStyle, color = SrColor.TextSecondary)
        } else {
            val blink by rememberInfiniteTransition(label = "rec").animateFloat(
                initialValue = 1f, targetValue = 1f,
                animationSpec = infiniteRepeatable(keyframes {
                    durationMillis = 1_000
                    1f at 490
                    0.15f at 500
                    0.15f at 990
                }),
                label = "recBlink"
            )
            Box(Modifier.size(10.dp).alpha(blink).background(SrColor.Rec, CircleShape))
            Text("REC", style = RecStyle, color = SrColor.TextPrimary)
        }
    }
}

@Composable
private fun StatusLabel(text: String, live: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(if (live) SrColor.Live else SrColor.LineStrong, CircleShape))
        Text(text, style = LabelStyle, color = SrColor.TextPrimary)
    }
}

/** `hh:mm:ss`, always with hours, as the recorder and battery saver screens show it. */
internal fun elapsedText(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

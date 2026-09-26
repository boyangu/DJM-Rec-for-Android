package com.audiopro.djmrec.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.audio.SignalDetector
import com.audiopro.djmrec.ui.components.OptionChips
import com.audiopro.djmrec.ui.theme.TextSecondary
import com.audiopro.djmrec.update.AppUpdate
import com.audiopro.djmrec.update.UpdateCheckResult
import com.audiopro.djmrec.update.UpdateChecker
import com.audiopro.djmrec.update.UpdateInstaller
import kotlinx.coroutines.launch

private fun hasNotificationPolicyAccess(context: android.content.Context): Boolean = runCatching {
    context.getSystemService(android.app.NotificationManager::class.java)
        ?.isNotificationPolicyAccessGranted == true
}.getOrDefault(false)

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val waveform by viewModel.waveformEnabled.collectAsState()
    val smooth by viewModel.smoothWaveform.collectAsState()
    val keepScreen by viewModel.keepScreenOn.collectAsState()
    val batterySaverScreen by viewModel.batterySaverScreen.collectAsState()
    val doNotDisturb by viewModel.doNotDisturbWhileRecording.collectAsState()
    val confirm by viewModel.confirmStop.collectAsState()
    val silenceHold by viewModel.silenceHoldMs.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostics by com.audiopro.djmrec.diagnostics.RemoteDiagnostics.enabled.collectAsState()
    val diagnosticsStatus by com.audiopro.djmrec.diagnostics.RemoteDiagnostics.status.collectAsState()
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
        updateStatus = "Downloading Set Recorder ${update.version}..."
        runCatching { UpdateInstaller.download(context.applicationContext, update) }
            .onSuccess { apk ->
                updateStatus = "Download verified. Opening Android installer..."
                runCatching { UpdateInstaller.install(context, apk) }
                    .onFailure { error ->
                        updateError = true
                        updateStatus = error.message ?: "Could not open Android installer"
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
            updateStatus = "Checking GitHub for updates..."
            when (val result = UpdateChecker.checkNow(context.applicationContext)) {
                is UpdateCheckResult.Available -> {
                    availableUpdate = result.update
                    updateStatus = "Set Recorder ${result.update.version} is available."
                }
                UpdateCheckResult.Current -> {
                    availableUpdate = null
                    updateStatus = "Set Recorder is up to date."
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
            updateStatus = "Allow Set Recorder to install updates, then return here."
            unknownSourcesPermission.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Capture", style = MaterialTheme.typography.titleLarge)
        Text("Monitoring arms automatically after USB connection and permission. Recording starts only when you press Record.", color = TextSecondary)
        PreferenceSwitch("Confirm stop", "Ask before stopping from the recorder. Notification Save & close always acts immediately.", confirm, viewModel::setConfirmStop)
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Silence hold", style = MaterialTheme.typography.titleSmall)
                Text(
                    "How long the input must stay below -60 dBFS before the recorder reports no signal. " +
                        "Music is full of brief quiet moments, so a short hold makes the status flicker. " +
                        "Signal is always detected instantly; only the return to \"no signal\" waits.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary
                )
                OptionChips(
                    options = SignalDetector.HOLD_CHOICES_MS.map { it to "${it / 1000} s" },
                    selected = silenceHold,
                    enabled = true,
                    onSelect = viewModel::setSilenceHoldMs
                )
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { RecordingSetupControls(viewModel) }
        }
        Text("Diagnostics & privacy", style = MaterialTheme.typography.titleLarge)
        PreferenceSwitch("Automatic diagnostics", "Send bounded mixer connection and recording-health events to Firebase Analytics, plus non-fatal errors and crashes to Crashlytics. No recorded audio or filenames. Enabled by default in production builds.",
            diagnostics, com.audiopro.djmrec.diagnostics.RemoteDiagnostics::setEnabled)
        Text(diagnosticsStatus, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text("Support reports and the USB descriptor export are under Diagnostics in the menu drawer (top-left).", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text("Display", style = MaterialTheme.typography.titleLarge)
        PreferenceSwitch("Live waveform", "RGB: red bass, green mids, blue highs. Mixed frequencies blend colors.", waveform, viewModel::setWaveformEnabled)
        PreferenceSwitch("Smooth waveform", "Scroll at the display frame rate. Turn off to reduce graphics work.", smooth, viewModel::setSmoothWaveform)
        Text("During a set", style = MaterialTheme.typography.titleLarge)
        PreferenceSwitch(
            "Silence calls and notifications",
            "Puts the phone in Do Not Disturb for the length of each recording and restores your " +
                "previous setting afterwards. Alarms still sound. A Do Not Disturb mode you turned " +
                "on yourself is left alone.",
            doNotDisturb, viewModel::setDoNotDisturbWhileRecording
        )
        // Re-read on resume: the grant is made in Android settings, which gives the app no callback.
        var policyAccess by remember { mutableStateOf(hasNotificationPolicyAccess(context)) }
        val policyAccessLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { policyAccess = hasNotificationPolicyAccess(context) }
        val settingsOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(settingsOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    policyAccess = hasNotificationPolicyAccess(context)
                }
            }
            settingsOwner.lifecycle.addObserver(observer)
            onDispose { settingsOwner.lifecycle.removeObserver(observer) }
        }
        if (doNotDisturb) {
            if (policyAccess) {
                Text("Do Not Disturb access: granted", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            } else {
                Text("Android needs one more permission before this can work.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Button(onClick = {
                    policyAccessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Allow Do Not Disturb access")
                }
            }
        }
        PreferenceSwitch(
            "Battery saver screen",
            "Keeps the screen on for the whole recording while Set Recorder is in front. After " +
                "30 seconds without a touch it dims to a black screen with the recording timer " +
                "and the time, at the display's lowest refresh rate. Tap to go back.",
            batterySaverScreen, viewModel::setBatterySaverScreen
        )
        PreferenceSwitch("Keep recorder screen awake", "Keeps the meters and waveform visible while monitoring or recording. Capture also works with the screen locked.", keepScreen, viewModel::setKeepScreenOn)
        Text("Background recording", style = MaterialTheme.typography.titleLarge)
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        var batteryExempt by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
        val batteryExemptionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName) }
        Text("Keep the persistent notification enabled. Android's battery optimization is the most common reason a long set stops in the background; exempt Set Recorder once so a 2-hour recording survives with the screen off. Force-stop, reboot or disconnecting USB still ends capture.", color = TextSecondary)
        if (batteryExempt) {
            Text("Battery optimization: unrestricted (recommended)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        } else {
            Button(onClick = {
                batteryExemptionLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Allow unrestricted battery use") }
        }
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }, modifier = Modifier.fillMaxWidth()) { Text("Android app settings") }
        Text("App updates", style = MaterialTheme.typography.titleLarge)
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Set Recorder", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Installed version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                updateStatus?.let { status ->
                    Text(
                        status,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (updateError) MaterialTheme.colorScheme.error else TextSecondary
                    )
                }
                if (updateBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = ::checkForUpdates,
                    enabled = !updateBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(if (updateBusy) "Please wait" else "Check for updates") }
                availableUpdate?.let { update ->
                    Button(
                        onClick = { requestInstall(update) },
                        enabled = !updateBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Download & install ${update.version}") }
                    Text(
                        "Download is verified before Android asks you to confirm installation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
        Text("Track markers", style = MaterialTheme.typography.titleLarge)
        Text("Tap Mark during recording to identify tracks. Find markers in Sets and export the track list. Markers never cut or modify your recording.", color = TextSecondary)
        OutlinedButton(onClick = viewModel::stopAndClose, modifier = Modifier.fillMaxWidth()) { Text("Save everything & close") }
    }
}

@Composable
private fun PreferenceSwitch(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().toggleable(value = value, role = Role.Switch, onValueChange = onChange).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Switch(value, null)
        }
    }
}

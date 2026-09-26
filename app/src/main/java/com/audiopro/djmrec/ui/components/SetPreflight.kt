package com.audiopro.djmrec.ui.components

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.audiopro.djmrec.ui.MainViewModel
import com.audiopro.djmrec.ui.theme.TextSecondary

/** One thing about the phone that can cost you a set, and whether it has been dealt with. */
enum class SetCheck(val title: String, val detail: String, val action: String) {
    DoNotDisturb(
        "Silence calls and notifications",
        "An incoming call takes audio focus and drops a full-screen call UI over the transport " +
            "controls. Allow Do Not Disturb access and Set Recorder silences the phone for the " +
            "length of each recording, then puts it back exactly as it was.",
        "Allow Do Not Disturb access",
    ),
    BatteryUnrestricted(
        "Allow unrestricted battery use",
        "Android's battery optimization is the most common reason a long set stops part-way " +
            "through. Exempt Set Recorder once and a multi-hour recording survives in the " +
            "background.",
        "Allow unrestricted battery",
    ),
    ScreenAwake(
        "Keep the screen awake",
        "Capture continues with the screen off, so this is optional -- it keeps the meters and " +
            "waveform visible while you play instead of having to wake the phone to check them.",
        "Keep the screen on",
    ),
    SystemBatterySaver(
        "Turn on Battery Saver",
        "Android's own Battery Saver trims background work across the whole phone. Set Recorder " +
            "cannot switch it on itself; turn it on for the set and the recording carries on as " +
            "normal.",
        "Open Battery Saver settings",
    ),
}

/**
 * Which of the checks still need doing, in the order they should be offered.
 *
 * Pure so the ordering and the "nothing left to do" case can be tested without an emulator. Do Not
 * Disturb comes first because it is the only one that protects a recording already in progress;
 * the screen setting is last because it is a convenience rather than a risk.
 */
fun outstandingSetChecks(
    doNotDisturbWanted: Boolean,
    doNotDisturbAccessGranted: Boolean,
    batteryUnrestricted: Boolean,
    keepScreenOn: Boolean,
    batterySaverScreen: Boolean = false,
    systemBatterySaverOn: Boolean = true,
): List<SetCheck> = buildList {
    // Nothing to nag about if the user has deliberately turned the feature off.
    if (doNotDisturbWanted && !doNotDisturbAccessGranted) add(SetCheck.DoNotDisturb)
    if (!batteryUnrestricted) add(SetCheck.BatteryUnrestricted)
    // The battery saver screen already keeps the screen on for the length of a recording.
    if (!keepScreenOn && !batterySaverScreen) add(SetCheck.ScreenAwake)
    if (!systemBatterySaverOn) add(SetCheck.SystemBatterySaver)
}

/**
 * A one-line prompt shown before recording when the phone is not set up for a long set, opening a
 * dialog that explains each item and fixes it in one tap.
 *
 * Deliberately not a blocking dialog: it must never stand between the user and the record button
 * when a set is about to start. It is dismissible for the session and disappears on its own once
 * there is nothing left to do.
 */
@Composable
fun SetPreflightBanner(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val batterySaverScreen by viewModel.batterySaverScreen.collectAsState()
    val doNotDisturbWanted by viewModel.doNotDisturbWhileRecording.collectAsState()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }

    // Both system grants are changed in Android settings, which gives the app no callback, so
    // re-read them every time the screen comes back to the foreground.
    var doNotDisturbAccess by remember { mutableStateOf(hasDoNotDisturbAccess(context)) }
    var batteryUnrestricted by remember { mutableStateOf(hasBatteryExemption(context)) }
    var systemBatterySaverOn by remember { mutableStateOf(isSystemBatterySaverOn(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                doNotDisturbAccess = hasDoNotDisturbAccess(context)
                batteryUnrestricted = hasBatteryExemption(context)
                systemBatterySaverOn = isSystemBatterySaverOn(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // StartActivityForResult rather than a bare startActivity: the result callback is the one
    // reliable moment to re-read a grant on the settings screens that finish without ON_RESUME
    // ordering guarantees.
    val systemSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        doNotDisturbAccess = hasDoNotDisturbAccess(context)
        batteryUnrestricted = hasBatteryExemption(context)
        systemBatterySaverOn = isSystemBatterySaverOn(context)
    }

    val outstanding = outstandingSetChecks(
        doNotDisturbWanted = doNotDisturbWanted,
        doNotDisturbAccessGranted = doNotDisturbAccess,
        batteryUnrestricted = batteryUnrestricted,
        keepScreenOn = keepScreenOn,
        batterySaverScreen = batterySaverScreen,
        systemBatterySaverOn = systemBatterySaverOn,
    )

    fun resolve(check: SetCheck) {
        when (check) {
            SetCheck.DoNotDisturb ->
                systemSettings.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            SetCheck.BatteryUnrestricted -> systemSettings.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
            )
            SetCheck.ScreenAwake -> viewModel.setKeepScreenOn(true)
            SetCheck.SystemBatterySaver ->
                systemSettings.launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }
    }

    if (detailsOpen) {
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            title = { Text("Before your set") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (outstanding.isEmpty()) {
                        Text("Your phone is set up. Calls and notifications will be silenced for " +
                            "the length of each recording and restored afterwards.")
                    }
                    outstanding.forEach { check ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(check.title, style = MaterialTheme.typography.titleSmall)
                            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            OutlinedButton(
                                onClick = { resolve(check) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) { Text(check.action) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detailsOpen = false }) { Text("Done") } },
            dismissButton = {
                if (outstanding.isNotEmpty()) {
                    TextButton(onClick = { detailsOpen = false; dismissed = true }) { Text("Not now") }
                }
            },
        )
    }

    if (dismissed || outstanding.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth().clickable { detailsOpen = true },
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.NotificationsOff, null, Modifier.size(18.dp), tint = TextSecondary)
            Text(
                if (outstanding.size == 1) outstanding.first().title
                else "Set up your phone for a long set",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { detailsOpen = true }) { Text("Set up") }
        }
    }
}

private fun hasDoNotDisturbAccess(context: Context): Boolean = runCatching {
    context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
}.getOrDefault(false)

private fun hasBatteryExemption(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(false)

private fun isSystemBatterySaverOn(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
}.getOrDefault(true)

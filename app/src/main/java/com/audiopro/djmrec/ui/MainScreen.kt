package com.audiopro.djmrec.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.DjmRecApplication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.audiopro.djmrec.BuildConfig
import com.audiopro.djmrec.ui.theme.SrColor
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.update.AppUpdate
import com.audiopro.djmrec.update.UpdateChecker
import kotlinx.coroutines.launch

/** Every screen in the app. The navigation drawer is the only way to move between them. */
private enum class Destination(val label: String, val icon: ImageVector) {
    RECORDING("Recording", Icons.Filled.FiberManualRecord),
    RECORDINGS("Library", Icons.Filled.LibraryMusic),
    SETTINGS("Settings", Icons.Filled.Settings),
    DIAGNOSTICS("Diagnostics", Icons.Filled.BugReport)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val destinationState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val context = LocalContext.current
    val application = context.applicationContext as DjmRecApplication
    val recoveryNotice by application.recoveryNotice.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedDestination by rememberSaveable { mutableStateOf(Destination.RECORDING) }
    var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    val saverActive by viewModel.saverActive.collectAsState()

    LaunchedEffect(Unit) {
        availableUpdate = UpdateChecker.check(context.applicationContext)
    }

    if (saverActive) {
        BatterySaverScreen(viewModel)
        return
    }

    recoveryNotice?.let { message ->
        AlertDialog(
            onDismissRequest = application::dismissRecoveryNotice,
            title = { Text("Recording recovered") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = application::dismissRecoveryNotice) { Text("OK") }
            }
        )
    }

    val mayPromptForUpdate = selectedDestination == Destination.SETTINGS && recoveryNotice == null &&
        recordingState !is RecordingState.Recording &&
        recordingState !is RecordingState.Paused &&
        recordingState !is RecordingState.Preparing
    if (availableUpdate != null && mayPromptForUpdate) {
        val update = availableUpdate!!
        AlertDialog(
            onDismissRequest = {
                UpdateChecker.defer(context, update.tag)
                availableUpdate = null
            },
            title = { Text("Set Recorder ${update.version} available") },
            text = { Text("A newer release is ready on GitHub. Recording will never be interrupted for an update.") },
            confirmButton = {
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                    availableUpdate = null
                }) { Text("View update") }
            },
            dismissButton = {
                TextButton(onClick = {
                    UpdateChecker.defer(context, update.tag)
                    availableUpdate = null
                }) { Text("Later") }
            }
        )
    }

    val recording = recordingState is RecordingState.Recording || recordingState is RecordingState.Paused
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = SrColor.Surface,
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            ) {
                Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("SET REC", style = BrandStyle, color = SrColor.TextPrimary)
                        Text("Unofficial DJM/XDJ/FLX Recording", fontSize = 12.sp, lineHeight = 16.sp,
                            color = SrColor.TextSecondary)
                    }
                    Destination.entries.forEach { dest ->
                        NavigationDrawerItem(
                            icon = { Icon(dest.icon, null, Modifier.size(20.dp)) },
                            label = { Text(dest.label, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                            badge = if (dest == Destination.RECORDING && recording) {
                                {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(Modifier.size(8.dp).background(SrColor.Rec, CircleShape))
                                        Text("REC", style = MonoLabelStyle, color = SrColor.TextSecondary)
                                    }
                                }
                            } else null,
                            selected = selectedDestination == dest,
                            onClick = {
                                selectedDestination = dest
                                scope.launch { drawerState.close() }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = SrColor.SurfaceRaised,
                                unselectedContainerColor = Color.Transparent,
                                selectedTextColor = SrColor.TextPrimary,
                                unselectedTextColor = SrColor.TextSecondary,
                                selectedIconColor = SrColor.TextPrimary,
                                unselectedIconColor = SrColor.TextSecondary,
                            ),
                            modifier = Modifier.height(48.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("V${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 16.dp),
                        style = MonoLabelStyle, color = SrColor.TextSecondary)
                }
            }
        }
    ) {
        Scaffold(
            containerColor = SrColor.Background,
            topBar = {
                TopAppBar(
                    title = {
                        if (selectedDestination == Destination.RECORDING) Text("SET REC", style = BrandStyle)
                        else Text(selectedDestination.label, fontSize = 22.sp, lineHeight = 28.sp)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                        }
                    },
                    actions = {
                        if (selectedDestination == Destination.RECORDING) {
                            IconButton(onClick = { selectedDestination = Destination.SETTINGS }) {
                                Icon(Icons.Filled.Tune, contentDescription = "Recording setup")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = SrColor.Background,
                        scrolledContainerColor = SrColor.Background,
                        titleContentColor = SrColor.TextPrimary,
                        navigationIconContentColor = SrColor.TextPrimary,
                        actionIconContentColor = SrColor.TextPrimary,
                    )
                )
            }
        ) { padding ->
            // navigationBarsPadding(): without a bottomBar the Scaffold no longer reserves an
            // inset for the system navigation bar, so content would otherwise run underneath it.
            Column(modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
                destinationState.SaveableStateProvider(selectedDestination.name) {
                when (selectedDestination) {
                    Destination.RECORDING -> RecorderScreen(
                        viewModel = viewModel,
                        onOpenLibrary = { selectedDestination = Destination.RECORDINGS }
                    )
                    Destination.RECORDINGS -> LibraryScreen()
                    Destination.SETTINGS -> SettingsScreen(viewModel = viewModel)
                    Destination.DIAGNOSTICS -> DiagnosticsScreen()
                }
                }
            }
        }
    }
}

private val BrandStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
private val MonoLabelStyle = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp,
    letterSpacing = 0.9.sp
)

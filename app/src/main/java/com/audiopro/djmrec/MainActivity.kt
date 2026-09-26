package com.audiopro.djmrec

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.audiopro.djmrec.audio.RecordingState
import kotlinx.coroutines.flow.combine
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.audiopro.djmrec.ui.MainScreen
import com.audiopro.djmrec.ui.MainViewModel
import com.audiopro.djmrec.ui.theme.DjmRecTheme

private const val SAVER_BRIGHTNESS = 0.02f

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.ensureLiveMonitoring() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val events = (application as DjmRecApplication).sessionEvents
        events.closeRequested.value = false
        lifecycleScope.launch { events.closeRequested.collect { if (it) finishAndRemoveTask() } }
        lifecycleScope.launch {
            combine(viewModel.recordingState, viewModel.batterySaverScreen, viewModel.keepScreenOn) { state, saver, keepOn ->
                val recording = state is RecordingState.Recording || state is RecordingState.Paused
                (recording && saver) || (keepOn && (recording || state is RecordingState.Monitoring))
            }.collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        lifecycleScope.launch { viewModel.saverActive.collect(::applySaverWindow) }
        requestRuntimePermissions()

        setContent {
            DjmRecTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setUiVisible(true)
    }

    override fun onStop() {
        viewModel.setUiVisible(false)
        super.onStop()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        viewModel.noteUserInteraction()
    }

    override fun onPause() {
        viewModel.setAppResumed(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.setAppResumed(true)
        // USB attach can precede Activity creation/resume. Reconcile the framework device list
        // here so monitoring does not depend on opening the Mixer USB picker first.
        viewModel.rescanUsbDevices()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.rescanUsbDevices()
    }

    /**
     * Everything an app may do to its own window to save power, applied while the battery saver
     * screen shows and undone the moment it goes. All of it is per-window: Android reverts it by
     * itself as soon as another app comes to the front.
     */
    private fun applySaverWindow(on: Boolean) {
        val attributes = window.attributes
        attributes.screenBrightness =
            if (on) SAVER_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        attributes.preferredDisplayModeId = if (on) lowestRefreshModeId() else 0
        window.attributes = attributes
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * The display's slowest mode at the current resolution, or 0 (no preference). The saver screen
     * changes once a second, so a variable-refresh panel can idle far below its usual 60-120 Hz.
     */
    private fun lowestRefreshModeId(): Int {
        val display = window.decorView.display ?: return 0
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { it.refreshRate }
            ?.modeId ?: 0
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions.launch(permissions.toTypedArray())
    }
}

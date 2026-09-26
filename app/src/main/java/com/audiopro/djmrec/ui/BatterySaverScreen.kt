package com.audiopro.djmrec.ui

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiopro.djmrec.audio.RecordingState
import kotlinx.coroutines.delay
import java.util.Date

private val SaverText = Color(0xFF8A8A8A)
private val SaverFaint = Color(0xFF3A3A3A)
private val SaverRed = Color(0xFFB3261E)

/**
 * Near-black stand-in for the app during a long recording: a recording dot and timer, the time of
 * day, and a faint label. Everything else about low power lives in MainActivity (brightness,
 * refresh rate, hidden system bars) and the service (meters and waveform stop while this shows).
 *
 * Redraws exactly once a second, on the second, and never animates, so the display can idle at its
 * lowest refresh rate between ticks. Any touch returns to the live screen; that is handled by
 * MainActivity.onUserInteraction, and this composable only swallows the gesture so it cannot land
 * on a control once the live screen is back.
 */
@Composable
fun BatterySaverScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.recordingState.collectAsState()
    val elapsed by viewModel.elapsedMillis.collectAsState()
    val paused = state is RecordingState.Paused

    // Anchor the timer to the last value the service sent and count on from it locally: at the
    // service's once-a-second background rate the raw value would now and then skip a second.
    val anchorAt = remember(elapsed) { SystemClock.elapsedRealtime() }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var wallClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            wallClock = System.currentTimeMillis()
            now = SystemClock.elapsedRealtime()
            delay(1_000L - wallClock % 1_000L)
        }
    }
    val shown = if (paused) elapsed else elapsed + (now - anchorAt).coerceIn(0L, 5_000L)
    val timeFormat = remember { DateFormat.getTimeFormat(context) }

    // Burn-in guard: move the block to a new spot every minute.
    val minute = wallClock / 60_000L
    val dx = ((minute * 7) % 5 - 2) * 6
    val dy = ((minute * 3) % 5 - 2) * 10

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.offset(dx.dp, dy.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(18.dp).background(if (paused) SaverFaint else SaverRed, CircleShape))
                Text(
                    saverElapsedText(shown),
                    color = SaverText,
                    fontSize = 56.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                )
            }
            if (paused) Text("PAUSED", color = SaverText, fontSize = 14.sp, letterSpacing = 3.sp)
            Text(timeFormat.format(Date(wallClock)), color = SaverText, fontSize = 22.sp)
        }
        Text(
            "Battery saver mode",
            color = SaverFaint,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
        )
    }
}

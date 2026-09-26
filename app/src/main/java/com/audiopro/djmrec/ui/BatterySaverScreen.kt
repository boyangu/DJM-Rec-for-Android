package com.audiopro.djmrec.ui

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.ui.theme.SrColor
import kotlinx.coroutines.delay
import java.util.Date

private val HeadStyle = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontFeatureSettings = "tnum")
private val RecStyle = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp,
    letterSpacing = 1.6.sp
)
private val TimerStyle = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, fontSize = 56.sp, lineHeight = 64.sp,
    letterSpacing = (-1).sp, fontFeatureSettings = "tnum"
)

/**
 * Near-black stand-in for the app during a long recording (Battery Saver.dc.html): the time of
 * day and a REC dot above the recording timer, and a faint label. Everything else about low power
 * lives in MainActivity (brightness, refresh rate, hidden system bars) and the service (meters and
 * waveform stop while this shows).
 *
 * Redraws exactly twice a second, on the half-second, and never animates, so the display can idle
 * at its lowest refresh rate between ticks. The REC dot flips on every tick, 500 ms on and 500 ms
 * off; the timer text still only changes once a second. Any touch returns to the live screen; that is handled by MainActivity.onUserInteraction,
 * and this composable only swallows the gesture so it cannot land on a control once the live
 * screen is back.
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
            delay(500L - wallClock % 500L)
        }
    }
    val shown = if (paused) elapsed else elapsed + (now - anchorAt).coerceIn(0L, 5_000L)
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    val dotColor = when {
        paused -> SrColor.SaverFaint
        (wallClock / 500L) % 2L == 0L -> SrColor.SaverRed
        else -> Color.Transparent
    }

    // Burn-in guard: move the block to a new spot every minute, up to 12 dp sideways and 20 dp up
    // or down.
    val minute = (wallClock / 60_000L).toInt()
    val dx = ((minute * 7) % 5 - 2) * 6
    val dy = ((minute * 3) % 5 - 2) * 10

    Box(
        Modifier
            .fillMaxSize()
            .background(SrColor.SaverGround)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.offset(dx.dp, dy.dp).width(IntrinsicSize.Max),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    timeFormat.format(Date(wallClock)), Modifier.padding(start = 3.dp, end = 16.dp),
                    style = HeadStyle, color = SrColor.SaverText, maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(13.dp).background(dotColor, CircleShape))
                    Text("REC", style = RecStyle, color = SrColor.SaverText)
                }
            }
            Text(elapsedText(shown), style = TimerStyle, color = SrColor.SaverText, maxLines = 1, softWrap = false)
            if (paused) {
                Text(
                    "PAUSED", Modifier.fillMaxWidth(), color = SrColor.SaverText, fontSize = 14.sp,
                    letterSpacing = 3.sp, textAlign = TextAlign.Center
                )
            }
        }
        Text(
            "Battery saver mode",
            color = SrColor.SaverFaint,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
        )
    }
}

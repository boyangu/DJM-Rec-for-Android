package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.audiopro.djmrec.ui.theme.SrColor
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow

private const val BIN_COUNT = 512

/** Collect here so waveform updates do not recompose the entire recorder workspace. */
@Composable
fun LiveBandWaveform(source: StateFlow<FloatArray>, modifier: Modifier = Modifier,
                     smooth: Boolean = true, active: Boolean = true, onVisible: (Boolean) -> Unit = {}) {
    DisposableEffect(Unit) {
        onVisible(true)
        onDispose { onVisible(false) }
    }
    val bins by source.collectAsState()
    BandWaveform(bins, modifier, smooth, active)
}

/**
 * Rekordbox-style three-band waveform: blue lows, orange mids and cream highs as layered
 * envelopes mirrored about the centre line. Fixed historical envelopes translate on the display
 * frame clock ([WaveformTimeline]); new audio enters at the right.
 */
@Composable
fun BandWaveform(bins: FloatArray, modifier: Modifier = Modifier, smooth: Boolean = true, active: Boolean = true) {
    val timeline = remember { WaveformTimeline() }
    // Per band (low, mid, high), BIN_COUNT heights each.
    val heights = remember { Array(3) { FloatArray(BIN_COUNT) } }
    val scratch = remember { FloatArray(3) }
    val path = remember { Path() }
    val frame = remember { mutableLongStateOf(0L) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(bins) {
        timeline.accept(bins)
        for (i in 0 until BIN_COUNT) {
            val base = i * 4
            bandHeights(bins.getOrElse(base) { 0f }, bins.getOrElse(base + 1) { 0f },
                bins.getOrElse(base + 2) { 0f }, bins.getOrElse(base + 3) { 0f }, scratch)
            for (band in 0 until 3) heights[band][i] = scratch[band]
        }
        if (!active || !smooth) frame.longValue = System.nanoTime()
    }
    LaunchedEffect(active, smooth, owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            val limiter = WaveformFrameLimiter()
            if (active && smooth) while (isActive) withFrameNanos {
                if (limiter.shouldRender(it)) frame.longValue = it
            }
        }
    }
    Canvas(modifier.fillMaxSize()
        .semantics { contentDescription = "Live waveform, three bands: blue lows under 250 Hz, orange mids to 2 kHz, white highs. New audio enters at right." }) {
        val lag = timeline.lag(frame.longValue, smooth && active)
        val center = size.height / 2f
        drawLine(SrColor.WaveAxis, Offset(0f, center), Offset(size.width, center))
        if (timeline.bins.isEmpty()) return@Canvas
        // Keep 24 bins outside the viewport for jitter recovery; never resample shifted peaks.
        val step = size.width / 487f
        clipRect {
            drawBand(path, heights[0], lag, step, center * 0.94f, center, SrColor.WaveLow)
            drawBand(path, heights[1], lag, step, center * 0.94f, center, SrColor.WaveMid)
            drawBand(path, heights[2], lag, step, center * 0.94f, center, SrColor.WaveHigh)
        }
    }
}

/** One band as a single closed shape: the top edge left to right, then the mirror back. */
private fun DrawScope.drawBand(path: Path, heights: FloatArray, lag: Float, step: Float,
                               scale: Float, center: Float, color: Color) {
    val first = (24 - lag - 1).toInt().coerceIn(0, BIN_COUNT - 1)
    val last = ((size.width / step) + 24 - lag + 1).toInt().coerceIn(first, BIN_COUNT - 1)
    fun x(i: Int) = (i - 24 + lag) * step
    path.reset()
    path.moveTo(x(first), center - heights[first] * scale)
    for (i in first + 1..last) path.lineTo(x(i), center - heights[i] * scale)
    for (i in last downTo first) path.lineTo(x(i), center + heights[i] * scale)
    path.close()
    drawPath(path, color)
}

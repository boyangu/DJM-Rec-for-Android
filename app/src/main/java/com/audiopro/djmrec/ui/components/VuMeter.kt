package com.audiopro.djmrec.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import com.audiopro.djmrec.audio.ChannelLevel
import com.audiopro.djmrec.audio.StereoLevels
import com.audiopro.djmrec.ui.theme.MeterAmber
import com.audiopro.djmrec.ui.theme.MeterGreen
import com.audiopro.djmrec.ui.theme.MeterRed
import com.audiopro.djmrec.ui.theme.SrColor
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Number of lit blocks across the bar; also the granularity of the colour zones. */
internal const val METER_SEGMENT_COUNT = 48

internal const val METER_FLOOR_DB = -60f
// 0 dBFS, not +3: the native meter clamps to 0 (MeterCalculator.h amplitudeToDb), so a
// higher ceiling left the red zone permanently unreachable.
internal const val METER_CEILING_DB = 0f
private const val CLIP_LATCH_MS = 1500L

internal fun dbToFraction(db: Float): Float =
    ((db - METER_FLOOR_DB) / (METER_CEILING_DB - METER_FLOOR_DB)).coerceIn(0f, 1f)

// The bar length follows RMS, not peak, so these are RMS thresholds. Programme material sits
// around -18 to -12 dBFS RMS, so the old peak-oriented -6/0 dB zones meant the bar was green
// essentially always: amber needed a level most music never reaches and red was unreachable
// outright (see drawHorizontalMeterFill for the other half of that bug).
internal const val METER_AMBER_DB = -20f
internal const val METER_RED_DB = -9f

internal fun colorForFraction(fraction: Float): Color = when {
    fraction >= dbToFraction(METER_RED_DB)   -> MeterRed
    fraction >= dbToFraction(METER_AMBER_DB) -> MeterAmber
    else                                      -> MeterGreen
}

/**
 * Horizontal stereo VU meter. Two horizontal bars (L on top, R below) with clip indicators
 * and a compact dB scale row beneath. Designed for a CDJ-style stacked layout.
 *
 * Digital peak-meter ballistics: instant attack, exponential release, and a peak-hold marker
 * (see [MeterBallistics]). The service publishes levels at only ~15 Hz, so the bars are advanced
 * on the display frame clock instead of being drawn straight from each sample -- otherwise they
 * stair-step and snap to the floor between polls.
 *
 * @param active whether audio is flowing. When false the frame loop does not run, so an idle
 *   meter costs nothing.
 */
@Composable
fun StereoVuMeter(levels: StereoLevels, modifier: Modifier = Modifier, active: Boolean = true) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalChannelMeter(label = "L", level = levels.left, active = active)
        HorizontalChannelMeter(label = "R", level = levels.right, active = active)
        HorizontalDbScale()
    }
}

@Composable
private fun HorizontalChannelMeter(label: String, level: ChannelLevel, active: Boolean) {
    var clipLatched by remember { mutableStateOf(false) }

    LaunchedEffect(level.isClipping) {
        if (level.isClipping) {
            clipLatched = true
        } else {
            delay(CLIP_LATCH_MS)
            clipLatched = false
        }
    }

    val ballistics = remember { MeterBallistics() }
    // Snapshot state the frame loop writes and the Canvas reads.
    val peakDb = remember { mutableFloatStateOf(METER_FLOOR_DB) }
    val peakHoldDb = remember { mutableFloatStateOf(METER_FLOOR_DB) }
    val rmsDb = remember { mutableFloatStateOf(METER_FLOOR_DB) }
    val owner = LocalLifecycleOwner.current

    // Keeps the frame loop pointed at the newest sample without restarting it when levels change
    // -- restarting every 66 ms would reset the time base and defeat the decay entirely.
    val latest by rememberUpdatedState(level)

    LaunchedEffect(active, owner) {
        if (!active) {
            ballistics.reset()
            peakDb.floatValue = METER_FLOOR_DB
            peakHoldDb.floatValue = METER_FLOOR_DB
            rmsDb.floatValue = METER_FLOOR_DB
            return@LaunchedEffect
        }
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Reuse the waveform's 60 Hz cap so a 144 Hz panel does not burn battery here.
            val limiter = WaveformFrameLimiter()
            while (isActive) withFrameNanos { nanos ->
                if (limiter.shouldRender(nanos)) {
                    val sample = latest
                    ballistics.update(nanos, sample.peakDb, sample.rmsDb)
                    peakDb.floatValue = ballistics.peakDb
                    peakHoldDb.floatValue = ballistics.peakHoldDb
                    rmsDb.floatValue = ballistics.rmsDb
                }
            }
        }
    }

    // The bars are read inside the draw lambdas below, not here, so a new frame only re-runs the
    // draw phase. The numeric readout goes through derivedStateOf so it recomposes when the
    // displayed integer changes rather than on all 60 frames a second.
    //
    // It shows the *held* peak rather than the instantaneous one: the raw value crosses several
    // integers a second, which made the number an unreadable blur. This matches the white marker.
    val readoutDb by remember { derivedStateOf { peakHoldDb.floatValue.toInt() } }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "$label input, peak $readoutDb dBFS" +
                if (clipLatched) ", clipping" else ""
        }
    ) {
        Text(text = label, style = MeterLabelStyle, color = SrColor.TextSecondary, modifier = Modifier.width(10.dp))

        Box(modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(3.dp)).background(SrColor.SurfaceRaised)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawHorizontalMeterFill(dbToFraction(rmsDb.floatValue))
                drawHorizontalPeakLine(dbToFraction(peakHoldDb.floatValue))
            }
        }

        // Clip light, latched for CLIP_LATCH_MS.
        Box(
            Modifier.width(6.dp).height(10.dp)
                .background(if (clipLatched) MeterRed else SrColor.SurfaceRaised, RoundedCornerShape(2.dp))
        )

        // Peak dB readout. Fixed width, one line, no wrap and tabular figures, so "-11" and
        // "-60" are the same width and the row never re-measures as the number changes.
        Text(
            text = "$readoutDb",
            style = MeterLabelStyle,
            color = SrColor.TextSecondary,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp)
        )
    }
}

private val MeterLabelStyle = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp,
    fontFeatureSettings = "tnum"
)

@Composable
private fun HorizontalDbScale() {
    val marks = listOf(-60, -40, -20, -9, 0)
    // Insets line the scale up with the bar: label + gap before it, gap + clip + gap + readout after.
    Layout(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 50.dp),
        content = {
            marks.forEach { db ->
                Text(
                    text = if (db > 0) "+$db" else "$db",
                    style = MeterLabelStyle.copy(fontSize = 10.sp),
                    color = SrColor.TextSecondary
                )
            }
        }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable ->
                val fraction = dbToFraction(marks[index].toFloat())
                val centeredX = (constraints.maxWidth * fraction - placeable.width / 2f).toInt()
                val maxX = (constraints.maxWidth - placeable.width).coerceAtLeast(0)
                val x = centeredX.coerceIn(0, maxX)
                placeable.placeRelative(x, 0)
            }
        }
    }
}

// --- Horizontal meter drawing helpers ---

/**
 * Position used to colour segment [index]: its right edge, not its left.
 *
 * With the left edge the topmost segment evaluated at 59/60 = 0.983, so a fraction of exactly
 * 1.0 was never tested and the red zone could never light no matter how hot the input.
 */
internal fun meterSegmentFraction(index: Int): Float = (index + 1f) / METER_SEGMENT_COUNT

private fun DrawScope.drawHorizontalMeterFill(fraction: Float) {
    val fillWidth = size.width * fraction
    val segmentCount = METER_SEGMENT_COUNT
    val segmentWidth = size.width / segmentCount
    val gapRatio = 0.22f

    for (i in 0 until segmentCount) {
        val segLeft = i * segmentWidth
        if (segLeft >= fillWidth) break
        val segFraction = meterSegmentFraction(i)
        val color = colorForFraction(segFraction)
        drawRect(
            color = color,
            topLeft = Offset(segLeft, 0f),
            size = Size(segmentWidth * (1f - gapRatio), size.height)
        )
    }
}

private fun DrawScope.drawHorizontalPeakLine(fraction: Float) {
    val width = 2.dp.toPx()
    val x = (size.width * fraction - width * fraction).coerceIn(0f, size.width - width)
    drawRect(color = SrColor.MeterPeak, topLeft = Offset(x, 0f), size = Size(width, size.height))
}

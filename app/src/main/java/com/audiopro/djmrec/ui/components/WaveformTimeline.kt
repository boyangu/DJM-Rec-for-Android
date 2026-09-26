package com.audiopro.djmrec.ui.components

import kotlin.math.pow
import kotlin.math.sqrt

/** Audio cursor drives translation; historical peaks never morph between snapshots. */
internal class WaveformTimeline {
    var bins = FloatArray(0)
        private set
    private var sequence = -1
    private var cursor = 0.0
    private var end = 0.0
    private var lastFrame = 0L
    private var binMillis = 1000.0 / 163

    fun accept(snapshot: FloatArray) {
        if (snapshot.size < 2050 || snapshot[2049] <= 0f) {
            bins = FloatArray(0)
            sequence = -1
            lastFrame = 0
            return
        }
        val next = snapshot[2048].toInt()
        if (next == sequence) return
        val delta = (next - sequence + 1048576) % 1048576
        if (sequence < 0 || delta > 512) {
            end = next.toDouble()
            cursor = end - 8
            lastFrame = 0
        } else {
            end += delta
        }
        sequence = next
        binMillis = snapshot[2049].toDouble()
        bins = snapshot
    }

    /** Number of newest bins withheld; small latency absorbs polling jitter. */
    fun lag(frameNanos: Long, smooth: Boolean): Float {
        if (!smooth) { cursor = end; lastFrame = frameNanos; return 0f }
        if (lastFrame != 0L) {
            val elapsed = ((frameNanos - lastFrame) / 1_000_000.0).coerceIn(0.0, 100.0)
            cursor = (cursor + elapsed / binMillis).coerceAtMost(end)
        }
        lastFrame = frameNanos
        // A stalled UI catches up without reshaping or averaging historical samples.
        cursor = cursor.coerceAtLeast(end - 24)
        return (end - cursor).toFloat()
    }
}

/**
 * Heights (0..1 of the half-height) of the low, mid and high layers for one bin.
 *
 * The outer shape is the peak envelope, square-root compressed so quiet passages stay visible.
 * The loudest band fills it; the others are drawn in proportion, softened by a 0.65 power so a
 * band a quarter as strong is still clearly there. Drawn low at the back, high in front.
 */
internal fun bandHeights(peak: Float, low: Float, mid: Float, high: Float, out: FloatArray) {
    fun clean(value: Float) = if (value.isFinite()) value.coerceAtLeast(0f) else 0f
    val envelope = sqrt(clean(peak).coerceAtMost(1f))
    val l = clean(low); val m = clean(mid); val h = clean(high)
    val max = maxOf(l, m, h)
    if (max < 0.000001f || envelope == 0f) { out.fill(0f); return }
    out[0] = envelope * (l / max).pow(0.65f)
    out[1] = envelope * (m / max).pow(0.65f)
    out[2] = envelope * (h / max).pow(0.65f)
}

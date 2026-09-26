package com.audiopro.djmrec.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours from the SET REC design system (claude.ai/design, tokens.css). Dark only. Chrome is
 * monochrome; colour is kept for signal (meter, waveform) and recording.
 *
 * Only the redesigned screens use these so far. The rest of the app still reads Color.kt until
 * the UI revamp (phase 10) moves every screen over.
 */
object SrColor {
    val Background = Color(0xFF0A0A0B)
    val Surface = Color(0xFF131315)
    val SurfaceRaised = Color(0xFF1C1C1F)
    val Line = Color(0xFF2A2A2E)
    val LineStrong = Color(0xFF6B6B72)
    val TextPrimary = Color(0xFFF4F4F5)
    val TextSecondary = Color(0xFF9A9AA1)
    val Inverse = Color(0xFFF4F4F5)
    val OnInverse = Color(0xFF0A0A0B)
    val Tonal = Color(0xFF2E2E33)
    val Rec = Color(0xFFFF453A)
    val Live = Color(0xFF32D74B)
    val Warn = Color(0xFFFF9F0A)
    val DisabledFill = Color(0x14F4F4F5)
    val DisabledContent = Color(0x61F4F4F5)
    val Scrim = Color(0x99000000)
    val MeterPeak = Color(0xFFF4F4F5)

    // Rekordbox-style three-band waveform.
    val WaveLow = Color(0xFF1E5BE0)
    val WaveMid = Color(0xFFF08A24)
    val WaveHigh = Color(0xFFFDF5DE)
    val WaveAxis = Color(0xFF2A2A2E)

    // Battery saver screen: dim on true black.
    val SaverGround = Color(0xFF000000)
    val SaverText = Color(0xFF8A8A8A)
    val SaverFaint = Color(0xFF3A3A3A)
    val SaverRed = Color(0xFFB3261E)
}

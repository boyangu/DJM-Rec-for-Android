package com.audiopro.djmrec.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// SET REC design system: monochrome chrome, so primary is the inverse (near-white) colour and
// dialogs, sheets and buttons pick that up. Colour is reserved for signal and recording.
private val DjmRecColorScheme = darkColorScheme(
    primary = SrColor.Inverse,
    onPrimary = SrColor.OnInverse,
    primaryContainer = SrColor.Tonal,
    onPrimaryContainer = SrColor.TextPrimary,
    secondary = SrColor.TextSecondary,
    onSecondary = SrColor.OnInverse,
    secondaryContainer = SrColor.Tonal,
    onSecondaryContainer = SrColor.TextPrimary,
    background = SrColor.Background,
    onBackground = SrColor.TextPrimary,
    surface = SrColor.Background,
    onSurface = SrColor.TextPrimary,
    surfaceVariant = SrColor.SurfaceRaised,
    onSurfaceVariant = SrColor.TextSecondary,
    surfaceContainerLowest = SrColor.Background,
    surfaceContainerLow = SrColor.Surface,
    surfaceContainer = SrColor.Surface,
    surfaceContainerHigh = SrColor.SurfaceRaised,
    surfaceContainerHighest = SrColor.SurfaceRaised,
    outline = SrColor.LineStrong,
    outlineVariant = SrColor.Line,
    error = SrColor.Rec,
    onError = SrColor.OnInverse,
    scrim = SrColor.Scrim,
)

/** A permanently-dark theme: meters and transport need a low-glare surface regardless of device theme. */
@Composable
fun DjmRecTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DjmRecColorScheme,
        typography = DjmRecTypography,
        content = content
    )
}

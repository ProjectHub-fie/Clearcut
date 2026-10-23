package com.projecthub.clearcut.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---- ClearCut premium palette ----
object ClearCutColors {
    // Backgrounds
    val Ink = Color(0xFF0C0E11)
    val InkElevated = Color(0xFF14171C)
    val InkRaised = Color(0xFF1B1F26)
    val SurfaceDark = Color(0xFF16191F)
    val SurfaceLight = Color(0xFFF7F7F5)
    val CardDark = Color(0xFF1E232B)
    val OutlineDark = Color(0xFF2A303A)
    val OutlineLight = Color(0xFFE0E0DC)

    // Accent — restrained teal/indigo
    val Accent = Color(0xFF5F8DED)
    val AccentSoft = Color(0xFF8AA9F2)
    val AccentMuted = Color(0xFF3C5587)
    val Teal = Color(0xFF37B6A5)
    val TealSoft = Color(0xFF7FD9CE)

    // Findings
    val RepeatedAudio = Color(0xFFB48BEA)   // violet
    val SimilarAudio = Color(0xFF8A9CF0)    // periwinkle
    val RepeatedVisual = Color(0xFF37BFA9)  // teal
    val SimilarVisual = Color(0xFF6FC2E8)   // sky
    val Structural = Color(0xFFE8B45A)      // amber
    val CrossFile = Color(0xFFF08A6A)       // coral
    val Warning = Color(0xFFEDB45A)
    val Error = Color(0xFFE86A6A)
    val Success = Color(0xFF5CC08A)

    val OnInk = Color(0xFFE6E9F2)
    val OnInkDim = Color(0xFF9AA2B2)
    val OnInkFaint = Color(0xFF6B7385)
}

private val DarkColors = darkColorScheme(
    primary = ClearCutColors.Accent,
    onPrimary = Color(0xFF06101F),
    primaryContainer = ClearCutColors.AccentMuted,
    onPrimaryContainer = ClearCutColors.AccentSoft,
    secondary = ClearCutColors.Teal,
    onSecondary = Color(0xFF04211C),
    background = ClearCutColors.Ink,
    onBackground = ClearCutColors.OnInk,
    surface = ClearCutColors.SurfaceDark,
    onSurface = ClearCutColors.OnInk,
    surfaceVariant = ClearCutColors.InkRaised,
    onSurfaceVariant = ClearCutColors.OnInkDim,
    outline = ClearCutColors.OutlineDark,
    error = ClearCutColors.Error,
    onError = Color(0xFF2B0A0A),
    surfaceTint = ClearCutColors.Accent,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3A5FC9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FB),
    onPrimaryContainer = Color(0xFF1B2E6B),
    secondary = Color(0xFF1E7869),
    onSecondary = Color.White,
    background = ClearCutColors.SurfaceLight,
    onBackground = Color(0xFF1B1D22),
    surface = Color.White,
    onSurface = Color(0xFF1B1D22),
    surfaceVariant = Color(0xFFEFEFEA),
    onSurfaceVariant = Color(0xFF52565E),
    outline = ClearCutColors.OutlineLight,
    error = Color(0xFFB3261E),
    onError = Color.White,
    surfaceTint = Color(0xFF3A5FC9),
)

@Composable
fun ClearCutTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ClearCutTypography,
        content = content,
    )
}

package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val StudioDarkColorScheme = darkColorScheme(
    primary = TallyRecordingRed,
    onPrimary = CrispStudioWhite,
    primaryContainer = ElevatedHudSurface,
    onPrimaryContainer = CrispStudioWhite,
    secondary = AmberSignal,
    onSecondary = ObsidianBackground,
    secondaryContainer = AnodizedSlateSurface,
    onSecondaryContainer = CrispStudioWhite,
    tertiary = PrecisionCyan,
    onTertiary = ObsidianBackground,
    background = ObsidianBackground,
    onBackground = CrispStudioWhite,
    surface = AnodizedSlateSurface,
    onSurface = CrispStudioWhite,
    surfaceVariant = ElevatedHudSurface,
    onSurfaceVariant = MutedAluminum,
    outline = GlassBorderSubtle,
    error = TallyRecordingRed,
    onError = CrispStudioWhite
)

val StudioShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = Typography,
        shapes = StudioShapes,
        content = content
    )
}

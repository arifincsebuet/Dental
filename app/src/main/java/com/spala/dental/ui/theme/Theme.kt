package com.spala.dental.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ClinicColors = lightColorScheme(
    primary = Teal700,
    onPrimary = Color.White,
    primaryContainer = Teal100,
    onPrimaryContainer = Teal900,
    secondary = Blue600,
    onSecondary = Color.White,
    secondaryContainer = Blue100,
    onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = Amber600,
    onTertiary = Color.White,
    tertiaryContainer = Amber100,
    onTertiaryContainer = Color(0xFF78350F),
    background = AppBackground,
    onBackground = Ink,
    surface = SurfaceWhite,
    onSurface = Ink,
    surfaceVariant = SurfaceTint,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = SurfaceWhite,
    surfaceContainerLow = Color(0xFFF8FAFB),
    surfaceContainer = SurfaceWhite,
    surfaceContainerHigh = SurfaceWhite,
    surfaceContainerHighest = SurfaceTint,
    outline = Outline,
    outlineVariant = Hairline,
    error = Rose600,
    errorContainer = Rose50,
    onErrorContainer = Color(0xFF881337),
)

private val ClinicShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Clinic theme. Clinical screens use a fixed light palette so status colours, chart legends and
 * printed/exported views stay consistent between devices (dynamic colour is intentionally off).
 */
@Composable
fun DentalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ClinicColors, typography = Typography, shapes = ClinicShapes, content = content)
}

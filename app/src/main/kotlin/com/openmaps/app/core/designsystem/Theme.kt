package com.openmaps.app.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** OpenMaps brand palette: dark navy, white type, electric-blue accent. */
object OpenMapsColors {
    val Navy950 = Color(0xFF070D1B)
    val Navy900 = Color(0xFF0A1224)
    val Navy800 = Color(0xFF111B33)
    val Navy700 = Color(0xFF1A2745)

    /** Accent for icons, text on navy, the location dot. 4.8:1 on Navy900. */
    val ElectricBlue = Color(0xFF2F7BFF)

    /** Fill behind white text (buttons, selected segment). 5.1:1 with white; ElectricBlue itself is only 3.9:1. */
    val ElectricBlueFilled = Color(0xFF2468E0)
    val Cyan = Color(0xFF5FE3FF)
    val Warning = Color(0xFFFFB84D)

    val TextPrimary = Color.White
    val TextSecondary = Color(0xFFB9C6DD) // 9.6:1 on Navy900
}

private val OpenMapsColorScheme = darkColorScheme(
    primary = OpenMapsColors.ElectricBlueFilled,
    onPrimary = Color.White,
    secondary = OpenMapsColors.Cyan,
    onSecondary = OpenMapsColors.Navy900,
    background = OpenMapsColors.Navy900,
    onBackground = OpenMapsColors.TextPrimary,
    surface = OpenMapsColors.Navy800,
    onSurface = OpenMapsColors.TextPrimary,
    surfaceVariant = OpenMapsColors.Navy700,
    onSurfaceVariant = OpenMapsColors.TextSecondary,
    outline = Color.White.copy(alpha = 0.12f),
    error = OpenMapsColors.Warning,
    onError = OpenMapsColors.Navy900,
)

private val OpenMapsTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.02.em),
)

/** Dark-only theme: the map is always the backdrop for dark glass controls. */
@Composable
fun OpenMapsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OpenMapsColorScheme,
        typography = OpenMapsTypography,
        content = content,
    )
}

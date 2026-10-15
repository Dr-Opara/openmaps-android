package com.openmaps.app.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * OpenMaps brand palette.
 *
 * Two families, matching the two surfaces the product actually uses:
 *  - **Light surfaces** (Map, Search, Destination Details, Route Preview): white/light cards on the
 *    map, so text is DARK navy and the accent is OpenMaps blue.
 *  - **Dark surfaces** (the navy's own controls over the map, and the Live HUD's compact pills over
 *    camera): near-black fills with white type.
 *
 * The mockup is white-first, so [TextPrimary]/[TextSecondary] (used by nearly every screen) are now
 * DARK and intended for light cards. Anything drawn on a navy fill must ask for [TextOnDark]/
 * [TextOnDarkSecondary] explicitly rather than assuming the default reads correctly — that inversion
 * is the single easiest way to get an unreadable screen here.
 */
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

    // ---- Light surfaces (Map / Search / Details / Route Preview) ----

    /** Card fill over the map. Opaque white: the mockup's cards are solid, not translucent. */
    val Surface = Color(0xFFFFFFFF)

    /** Slightly recessed fill for inputs and tiles that sit inside a white card. */
    val SurfaceMuted = Color(0xFFF1F4F9)

    /** Hairline that separates a light card from the map behind it. */
    val SurfaceBorder = Color(0xFFE2E7F0)

    /** Default body text on a light surface. 15.9:1 on [Surface]. */
    val TextPrimary = Color(0xFF0A1224)

    /** Supporting text on a light surface. 7.6:1 on [Surface]. */
    val TextSecondary = Color(0xFF5B6780)

    /** Positive/confirmation green, used only for genuinely open/positive real state. */
    val Success = Color(0xFF1E8E3E)

    // ---- Dark surfaces (navy controls, Live HUD pills over camera) ----

    val TextOnDark = Color.White
    val TextOnDarkSecondary = Color(0xFFB9C6DD) // 9.6:1 on Navy900
}

private val OpenMapsColorScheme = lightColorScheme(
    primary = OpenMapsColors.ElectricBlueFilled,
    onPrimary = Color.White,
    secondary = OpenMapsColors.Cyan,
    onSecondary = OpenMapsColors.Navy900,
    background = OpenMapsColors.Surface,
    onBackground = OpenMapsColors.TextPrimary,
    surface = OpenMapsColors.Surface,
    onSurface = OpenMapsColors.TextPrimary,
    surfaceVariant = OpenMapsColors.SurfaceMuted,
    onSurfaceVariant = OpenMapsColors.TextSecondary,
    outline = OpenMapsColors.SurfaceBorder,
    error = Color(0xFFD92D20),
    onError = Color.White,
)

private val OpenMapsTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.02.em),
    // Large figures (speed, trip totals, route duration on Route Preview) need to be readable at a
    // glance while driving, which the mockup shows at display scale rather than body scale.
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
)

/**
 * Light theme: the map is the backdrop for white cards, so the scheme itself is light. The Live
 * screen sits over the camera rather than the map and paints its own explicit colors, which is why
 * a single light scheme covers the whole app.
 */
@Composable
fun OpenMapsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OpenMapsColorScheme,
        typography = OpenMapsTypography,
        content = content,
    )
}

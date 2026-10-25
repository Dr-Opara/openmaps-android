package com.openmaps.app.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Maps-wide surface appearance shared by Map, Search, POI and Destination overlays.
 * Unlike map tiles, these Compose cards must switch their OWN colors or night map mode
 * leaves bright white sheets in front of dark streets.
 */
val LocalMapNightMode = compositionLocalOf { false }

@Composable
fun mapSheetSurfaceColor(): Color =
    if (LocalMapNightMode.current) OpenMapsColors.Navy900 else OpenMapsColors.Surface

@Composable
fun mapSheetMutedColor(): Color =
    if (LocalMapNightMode.current) OpenMapsColors.Navy800 else OpenMapsColors.SurfaceMuted

@Composable
fun mapSheetPrimaryTextColor(): Color =
    if (LocalMapNightMode.current) Color.White else OpenMapsColors.TextPrimary

@Composable
fun mapSheetSecondaryTextColor(): Color =
    if (LocalMapNightMode.current) OpenMapsColors.TextOnDarkSecondary else OpenMapsColors.TextSecondary

@Composable
fun mapSheetBorderColor(): Color =
    if (LocalMapNightMode.current) Color.White.copy(alpha = 0.16f) else OpenMapsColors.SurfaceBorder

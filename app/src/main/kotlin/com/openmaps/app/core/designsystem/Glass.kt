package com.openmaps.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

object GlassShapes {
    val Card = RoundedCornerShape(20.dp)
    val Pill = RoundedCornerShape(percent = 50)
    val Circle: Shape = CircleShape

    /** Medium radius used for list rows and inputs inside a card. */
    val Row = RoundedCornerShape(14.dp)
}

/**
 * The primary surface treatment: an opaque white card with a hairline border.
 *
 * Replaces the previous dark translucent panel as the default for Map/Search/Details/Preview. The
 * mockup's cards are solid white on a light map, not glass — true background blur is also impossible
 * over a GL map surface, so a translucent fill would only muddy the map underneath.
 */
fun Modifier.surfaceCard(shape: Shape = GlassShapes.Card): Modifier =
    this
        .background(OpenMapsColors.Surface, shape)
        .border(1.dp, OpenMapsColors.SurfaceBorder, shape)
        .clip(shape)

/**
 * Dark fill for controls that sit directly on the map or over the camera feed (category chips,
 * Live HUD pills, the Live mode toggle). Kept separate from [surfaceCard] so a screen can mix
 * light cards with dark controls, exactly as the mockup does.
 */
fun Modifier.glass(shape: Shape = GlassShapes.Card, alpha: Float = 0.86f): Modifier =
    this
        .background(OpenMapsColors.Navy900.copy(alpha = alpha), shape)
        .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
        .clip(shape)

/** A glass row used for single-line banners/pills. */
@Composable
fun GlassRow(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.Pill,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.glass(shape).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

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
}

/**
 * "Translucent glass" surface: very dark navy at high alpha with a hairline border.
 * (True background blur is not possible over a GL map surface, so this stays a tinted panel.)
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

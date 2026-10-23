package com.openmaps.app.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openmaps.app.core.routing.ManeuverType

/**
 * OpenMaps Navigation Flow APPROVED HUD icon.
 *
 * This intentionally has NO cobalt tile. In the approved camera mockup a single large,
 * solid white navigation arrow sits directly on the rounded, translucent navy banner.
 * The compact "Then" pill uses the SAME geometry at a smaller size.
 *
 * This is a 2-D maneuver INSTRUCTION GLYPH, not the geographically anchored AR road
 * chevrons. It must never replace ARArrowPipeline or imply real-world AR alignment.
 */
@Composable
fun ReferenceManeuverArrow(
    type: ManeuverType,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    color: Color = Color.White,
) {
    Canvas(modifier = modifier.size(size)) {
        when (type) {
            ManeuverType.TurnLeft -> drawRightAngleArrow(color, right = false)
            ManeuverType.TurnRight -> drawRightAngleArrow(color, right = true)
            ManeuverType.SlightLeft -> drawSlantedArrow(color, right = false, sharp = false)
            ManeuverType.SlightRight -> drawSlantedArrow(color, right = true, sharp = false)
            ManeuverType.SharpLeft -> drawSlantedArrow(color, right = false, sharp = true)
            ManeuverType.SharpRight -> drawSlantedArrow(color, right = true, sharp = true)
            ManeuverType.UTurn -> drawReturnArrow(color)
            ManeuverType.Roundabout -> drawCircleArrow(color)
            ManeuverType.Unknown -> drawUnknownDirection(color)
            else -> drawForwardArrow(color) // Start, Continue, Merge, Ramp, Destination
        }
    }
}

private fun DrawScope.p(x: Float, y: Float): Offset =
    Offset(size.width * x, size.height * y)

private fun DrawScope.drawFilledTip(
    tip: Offset, base: Offset, side: Offset, color: Color,
) {
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + side.x, base.y + side.y)
        lineTo(base.x - side.x, base.y - side.y)
        close()
    }
    drawPath(path, color)
}

/** Thick upright shaft + solid arrowhead matching the provided cropped mockup. */
private fun DrawScope.drawForwardArrow(color: Color) {
    val stroke = size.minDimension * 0.15f
    drawLine(color, p(0.50f, 0.84f), p(0.50f, 0.27f),
        strokeWidth = stroke, cap = StrokeCap.Round)
    drawFilledTip(p(0.50f, 0.055f), p(0.50f, 0.38f),
        p(0.24f, 0f), color)
}

/** Navigation-style right/left turn, not a rotated Material directional icon. */
private fun DrawScope.drawRightAngleArrow(color: Color, right: Boolean) {
    val x = if (right) 0.27f else 0.73f
    val end = if (right) 0.80f else 0.20f
    val tip = if (right) 0.96f else 0.04f
    val path = Path().apply {
        val start = p(x, 0.83f)
        moveTo(start.x, start.y)
        val bend = p(x, 0.44f)
        lineTo(bend.x, bend.y)
        val control = p(x, 0.29f)
        val cornerEnd = p(if (right) 0.43f else 0.57f, 0.29f)
        quadraticTo(control.x, control.y, cornerEnd.x, cornerEnd.y)
        val finish = p(end, 0.29f)
        lineTo(finish.x, finish.y)
    }
    drawPath(path, color, style = Stroke(
        width = size.minDimension * 0.135f,
        cap = StrokeCap.Round, join = StrokeJoin.Round,
    ))
    val side = size.height * 0.18f
    drawFilledTip(
        tip = p(tip, 0.29f),
        base = p(if (right) 0.75f else 0.25f, 0.29f),
        side = Offset(0f, side),
        color = color,
    )
}

private fun DrawScope.drawSlantedArrow(color: Color, right: Boolean, sharp: Boolean) {
    val side = if (right) 1f else -1f
    val path = Path().apply {
        val start = p(0.50f, 0.85f)
        moveTo(start.x, start.y)
        val bend = p(0.50f, 0.54f)
        lineTo(bend.x, bend.y)
        val end = p(0.50f + side * (if (sharp) 0.35f else 0.23f), 0.24f)
        lineTo(end.x, end.y)
    }
    drawPath(path, color, style = Stroke(
        width = size.minDimension * 0.135f, cap = StrokeCap.Round, join = StrokeJoin.Round,
    ))
    drawFilledTip(
        tip = p(0.50f + side * (if (sharp) 0.42f else 0.30f), 0.12f),
        base = p(0.50f + side * (if (sharp) 0.30f else 0.17f), 0.36f),
        side = Offset(size.width * 0.12f, size.height * 0.065f),
        color = color,
    )
}

private fun DrawScope.drawReturnArrow(color: Color) {
    val path = Path().apply {
        moveTo(p(0.29f, 0.84f).x, p(0.29f, 0.84f).y)
        lineTo(p(0.29f, 0.40f).x, p(0.29f, 0.40f).y)
        cubicTo(p(0.29f, 0.08f).x, p(0.29f, 0.08f).y,
            p(0.75f, 0.08f).x, p(0.75f, 0.08f).y,
            p(0.75f, 0.39f).x, p(0.75f, 0.39f).y)
        lineTo(p(0.75f, 0.58f).x, p(0.75f, 0.58f).y)
    }
    drawPath(path, color, style = Stroke(
        width = size.minDimension * 0.13f,
        cap = StrokeCap.Round, join = StrokeJoin.Round,
    ))
    drawFilledTip(p(0.75f, 0.82f), p(0.75f, 0.53f),
        Offset(size.width * 0.20f, 0f), color)
}

private fun DrawScope.drawCircleArrow(color: Color) {
    val d = size.minDimension
    drawArc(
        color = color,
        startAngle = -115f,
        sweepAngle = 278f,
        useCenter = false,
        topLeft = p(0.15f, 0.14f),
        size = androidx.compose.ui.geometry.Size(size.width * 0.70f, size.height * 0.70f),
        style = Stroke(width = d * 0.13f, cap = StrokeCap.Round),
    )
    drawFilledTip(
        tip = p(0.14f, 0.33f),
        base = p(0.19f, 0.57f),
        side = Offset(d * 0.18f, d * 0.04f),
        color = color,
    )
}

private fun DrawScope.drawUnknownDirection(color: Color) {
    // If the route provider lacks turn classification, don't draw a misleading arrow.
    drawCircle(color, radius = size.minDimension * 0.10f, center = p(0.50f, 0.50f))
}

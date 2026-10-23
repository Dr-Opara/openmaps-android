package com.openmaps.app.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The reference screenshots' maneuver icon: a bold white glyph (hooked turn, straight shaft, U, or
 * roundabout loop) on a large rounded cobalt tile — never a Material "arrow" glyph rotated to a
 * compass angle, which reads as a plain arrow rather than a turn instruction.
 *
 * Drawn with [Canvas] rather than per-type vector drawables: every glyph in the family shares one
 * stroke weight, one cap/join style and one coordinate system, so the set reads as one design
 * language instead of 14 independently authored assets drifting apart over time.
 */
@Composable
fun ManeuverIconTile(
    type: ManeuverType,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    tileColor: Color = OpenMapsColors.ElectricBlueFilled,
    glyphColor: Color = Color.White,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(tileColor),
        contentAlignment = Alignment.Center,
    ) {
        ManeuverGlyph(
            type = type,
            tint = glyphColor,
            // Canvas has no intrinsic measurement: without fillMaxSize this child measured
            // to zero inside the Box (the blue tile rendered with NO maneuver arrow).
            // The explicit size makes the icon visible for ALL maneuver types.
            modifier = Modifier
                .fillMaxSize()
                .padding(size * 0.16f),
        )
    }
}

/** The bare glyph, with no tile — used at small sizes (e.g. a secondary "Next turn" line). */
@Composable
fun ManeuverGlyph(
    type: ManeuverType,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * STROKE_FRACTION
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (type) {
            ManeuverType.UTurn -> drawUTurnGlyph(tint, stroke, strokeWidth)
            ManeuverType.Roundabout -> drawRoundaboutGlyph(tint, stroke, strokeWidth)
            ManeuverType.SlightLeft -> drawHookGlyph(tint, stroke, angleDegrees = -SLIGHT_ANGLE)
            ManeuverType.SlightRight -> drawHookGlyph(tint, stroke, angleDegrees = SLIGHT_ANGLE)
            ManeuverType.TurnLeft -> drawHookGlyph(tint, stroke, angleDegrees = -TURN_ANGLE)
            ManeuverType.TurnRight -> drawHookGlyph(tint, stroke, angleDegrees = TURN_ANGLE)
            ManeuverType.SharpLeft -> drawHookGlyph(tint, stroke, angleDegrees = -SHARP_ANGLE)
            ManeuverType.SharpRight -> drawHookGlyph(tint, stroke, angleDegrees = SHARP_ANGLE)

            // Straight family: a trip start, a plain continue, a merge, a ramp, the destination pin
            // moment, or an unmapped provider code — none of these imply a turn, so they all read as
            // "keep going" rather than guessing a direction that was never provided.
            ManeuverType.Start,
            ManeuverType.Continue,
            ManeuverType.Merge,
            ManeuverType.Ramp,
            ManeuverType.Destination,
            ManeuverType.Unknown,
            -> drawStraightGlyph(tint, stroke)
        }
    }
}

// All glyphs are authored in a unit square centered on (0, 0), y-down, x-right, then scaled to the
// canvas by [unitScale]/[unitCenter] so one set of coordinates covers every tile size the HUD uses.

private fun DrawScope.unitCenter(): Offset = Offset(size.width / 2f, size.height / 2f)
private fun DrawScope.unitScale(): Float = size.minDimension / 2f

private fun DrawScope.toCanvas(x: Float, y: Float): Offset {
    val c = unitCenter()
    val s = unitScale()
    return Offset(c.x + x * s, c.y + y * s)
}

private fun DrawScope.drawStraightGlyph(color: Color, stroke: Stroke) {
    val shaft = Path().apply {
        moveTo(toCanvas(0f, 0.72f).x, toCanvas(0f, 0.72f).y)
        lineTo(toCanvas(0f, -0.28f).x, toCanvas(0f, -0.28f).y)
    }
    drawPath(shaft, color, style = stroke)
    drawArrowhead(color, tip = Offset(0f, -0.82f), forward = Offset(0f, -1f))
}

private fun DrawScope.drawHookGlyph(color: Color, stroke: Stroke, angleDegrees: Float) {
    val bottom = Offset(0f, 0.74f)
    val bend = Offset(0f, -0.06f)
    val rad = Math.toRadians(angleDegrees.toDouble())
    // 0 deg points straight up; positive is clockwise (right).
    val endDir = Offset(sin(rad).toFloat(), -cos(rad).toFloat())
    val end = bend + endDir * HOOK_LENGTH
    val control = bend + Offset(0f, -0.32f)

    val path = Path().apply {
        moveTo(toCanvas(bottom.x, bottom.y).x, toCanvas(bottom.x, bottom.y).y)
        lineTo(toCanvas(bend.x, bend.y).x, toCanvas(bend.x, bend.y).y)
        val c = toCanvas(control.x, control.y)
        val e = toCanvas(end.x, end.y)
        quadraticTo(c.x, c.y, e.x, e.y)
    }
    drawPath(path, color, style = stroke)

    // Tangent at the curve's end (t=1 of a quadratic bezier is end - control).
    val tangent = (end - control).normalized()
    drawArrowhead(color, tip = end + tangent * 0.24f, forward = tangent)
}

private fun DrawScope.drawUTurnGlyph(color: Color, stroke: Stroke, strokeWidth: Float) {
    val legX = 0.36f
    val path = Path().apply {
        val start = toCanvas(-legX, 0.74f)
        moveTo(start.x, start.y)
        val top = toCanvas(-legX, -0.18f)
        lineTo(top.x, top.y)
        val rect = androidx.compose.ui.geometry.Rect(
            left = toCanvas(-legX, -0.60f).x,
            top = toCanvas(-legX, -0.60f).y,
            right = toCanvas(legX, -0.18f).x,
            bottom = toCanvas(legX, 0.24f).y,
        )
        arcTo(rect, startAngleDegrees = 180f, sweepAngleDegrees = -180f, forceMoveTo = false)
    }
    drawPath(path, color, style = stroke)

    val downStart = toCanvas(legX, -0.18f)
    val downEnd = toCanvas(legX, 0.30f)
    drawLine(color, downStart, downEnd, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    drawArrowhead(color, tip = Offset(legX, 0.62f), forward = Offset(0f, 1f))
}

private fun DrawScope.drawRoundaboutGlyph(color: Color, stroke: Stroke, strokeWidth: Float) {
    val radius = 0.52f
    val rect = androidx.compose.ui.geometry.Rect(
        left = toCanvas(-radius, -radius).x,
        top = toCanvas(-radius, -radius).y,
        right = toCanvas(radius, radius).x,
        bottom = toCanvas(radius, radius).y,
    )
    // A ring with a gap, not a closed circle: the gap is where the arrowhead breaks tangentially,
    // the universal "roundabout" pictogram.
    drawArc(
        color = color,
        startAngle = -40f,
        sweepAngle = 260f,
        useCenter = false,
        topLeft = rect.topLeft,
        size = rect.size,
        style = stroke,
    )
    val endAngleRad = Math.toRadians(-40.0)
    val tip = Offset((cos(endAngleRad) * radius).toFloat(), (sin(endAngleRad) * radius).toFloat())
    val tangent = Offset(-sin(endAngleRad).toFloat(), cos(endAngleRad).toFloat())
    drawArrowhead(color, tip = tip + tangent * 0.08f, forward = tangent)
}

/** A small filled triangle, [tip] and [forward] in the same unit space as the rest of the glyph. */
private fun DrawScope.drawArrowhead(color: Color, tip: Offset, forward: Offset) {
    val fwd = forward.normalized()
    val perp = Offset(-fwd.y, fwd.x)
    val base = tip - fwd * ARROWHEAD_LENGTH
    val left = base + perp * ARROWHEAD_WIDTH
    val right = base - perp * ARROWHEAD_WIDTH
    val path = Path().apply {
        val t = toCanvas(tip.x, tip.y)
        val l = toCanvas(left.x, left.y)
        val r = toCanvas(right.x, right.y)
        moveTo(t.x, t.y)
        lineTo(l.x, l.y)
        lineTo(r.x, r.y)
        close()
    }
    drawPath(path, color)
}

private fun Offset.normalized(): Offset {
    val len = hypot(x, y)
    return if (len < 0.0001f) Offset(0f, -1f) else Offset(x / len, y / len)
}

private const val STROKE_FRACTION = 0.14f
private const val HOOK_LENGTH = 0.62f
private const val ARROWHEAD_LENGTH = 0.30f
private const val ARROWHEAD_WIDTH = 0.22f
private const val SLIGHT_ANGLE = 42f
private const val TURN_ANGLE = 88f
private const val SHARP_ANGLE = 132f

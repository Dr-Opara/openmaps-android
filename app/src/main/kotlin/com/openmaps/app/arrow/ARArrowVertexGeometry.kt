package com.openmaps.app.arrow

import com.openmaps.app.core.arrow.ScreenArrow
import kotlin.math.hypot

/**
 * Thick, open V-shaped road chevrons matching the original Live design.
 * Each arm is a quad (two triangles), so one chevron uses 12 vertices.
 * Positions are still projected from actual route geometry, never painted at a fake location.
 */
internal object ARArrowVertexGeometry {
    const val MAX_ARROWS = 24
    const val FLOATS_PER_VERTEX = 2
    const val VERTICES_PER_ARROW = 12
    const val FLOATS_PER_ARROW = FLOATS_PER_VERTEX * VERTICES_PER_ARROW
    const val MAX_FLOATS = MAX_ARROWS * FLOATS_PER_ARROW
    const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

    fun pixelToNdcX(x: Float, width: Int): Float = (2f * x / width) - 1f
    fun pixelToNdcY(y: Float, height: Int): Float = 1f - (2f * y / height)

    /**
     * @param radiusScale Scales the whole chevron (span and stroke thickness) around each arrow's
     * own anchor point, without changing its position. `1f` (the default) reproduces the exact
     * geometry this function always drew, so every existing caller/test is untouched. Values above
     * `1f` are used to emit a second, larger, enlarged pass ([ARArrowRenderer] draws it underneath
     * the normal pass with additive blending and low alpha) so the chevrons read as a glowing/neon
     * path rather than a flat, thin outline — matching the reference design's bold, lit look rather
     * than inventing a shader-based bloom pass.
     */
    fun write(
        arrows: List<ScreenArrow>,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        output: FloatArray,
        radiusScale: Float = 1f,
    ): Int {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0 || output.size < FLOATS_PER_ARROW) return 0
        var offset = 0
        fun vertex(x: Float, y: Float) {
            output[offset++] = pixelToNdcX(x, viewportWidthPx)
            output[offset++] = pixelToNdcY(y, viewportHeightPx)
        }
        fun quad(ax: Float, ay: Float, bx: Float, by: Float, thickness: Float) {
            val dx = bx - ax
            val dy = by - ay
            val len = hypot(dx, dy)
            if (len <= 0f) return
            val nx = -dy / len * thickness * 0.5f
            val ny = dx / len * thickness * 0.5f
            vertex(ax + nx, ay + ny)
            vertex(ax - nx, ay - ny)
            vertex(bx + nx, by + ny)
            vertex(bx + nx, by + ny)
            vertex(ax - nx, ay - ny)
            vertex(bx - nx, by - ny)
        }
        for (arrow in arrows) {
            if (offset + FLOATS_PER_ARROW > output.size || offset + FLOATS_PER_ARROW > MAX_FLOATS) break
            if (!arrow.onScreen || !arrow.x.isFinite() || !arrow.y.isFinite() ||
                !arrow.radiusPx.isFinite() || arrow.radiusPx <= 0f) continue

            // The bottom points fan outward, then both bright arms converge at the
            // upper middle. Unlike the old filled triangle, the center stays transparent.
            val r = arrow.radiusPx * radiusScale
            val halfWidth = r * 1.16f
            val tipY = arrow.y - r * 0.68f
            val outerY = arrow.y + r * 0.58f
            val thickness = (r * 0.38f).coerceAtLeast(2f * radiusScale)
            quad(arrow.x - halfWidth, outerY, arrow.x, tipY, thickness)
            quad(arrow.x + halfWidth, outerY, arrow.x, tipY, thickness)
        }
        return offset
    }
}

package com.openmaps.app.arrow

import com.openmaps.app.core.arrow.ScreenArrow

/**
 * Converts projected screen pixels (origin top-left, y down) to OpenGL clip coordinates
 * (origin center, y up). This is pure Kotlin to make the render math JVM-testable.
 */
internal object ARArrowVertexGeometry {
    const val MAX_ARROWS = 24
    const val FLOATS_PER_VERTEX = 2
    const val VERTICES_PER_ARROW = 3
    const val FLOATS_PER_ARROW = FLOATS_PER_VERTEX * VERTICES_PER_ARROW
    const val MAX_FLOATS = MAX_ARROWS * FLOATS_PER_ARROW
    const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

    fun pixelToNdcX(x: Float, width: Int): Float = (2f * x / width) - 1f
    fun pixelToNdcY(y: Float, height: Int): Float = 1f - (2f * y / height)

    /** Writes up to MAX_ARROWS triangles. Returns the number of floats written. */
    fun write(
        arrows: List<ScreenArrow>,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        output: FloatArray,
    ): Int {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0 || output.size < FLOATS_PER_ARROW) return 0
        var offset = 0
        for (arrow in arrows) {
            if (offset + FLOATS_PER_ARROW > output.size || offset + FLOATS_PER_ARROW > MAX_FLOATS) break
            if (!arrow.onScreen || !arrow.x.isFinite() || !arrow.y.isFinite() ||
                !arrow.radiusPx.isFinite() || arrow.radiusPx <= 0f) continue
            val r = arrow.radiusPx
            val corners = floatArrayOf(
                arrow.x, arrow.y - r * 1.15f,
                arrow.x - r * 0.8f, arrow.y + r * 0.8f,
                arrow.x + r * 0.8f, arrow.y + r * 0.8f,
            )
            for (i in corners.indices step 2) {
                output[offset++] = pixelToNdcX(corners[i], viewportWidthPx)
                output[offset++] = pixelToNdcY(corners[i + 1], viewportHeightPx)
            }
        }
        return offset
    }
}

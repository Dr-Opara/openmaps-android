package com.openmaps.app.arrow

import android.opengl.GLES20
import com.openmaps.app.core.arrow.ScreenArrow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the AR chevrons. The only file that turns arrow data into OpenGL calls.
 *
 * **Deliberately primitive.** Each arrow is one small triangle fan built on the CPU and uploaded as
 * a single dynamic vertex buffer, so the whole arrow set is one `glDrawArrays` per frame regardless
 * of arrow count. That keeps per-frame allocation and draw-call cost flat as the arrow count grows,
 * which is what lets the cap in [com.openmaps.app.core.arrow.ARArrowConfig] stay generous without a
 * performance cliff.
 *
 * Styling is intentionally restrained — flat electric blue, no glow, no bloom, no texture — because
 * the road ahead must stay the dominant visual. A glowing neon arrow over a night-time road is both
 * the cliché this feature is trying to avoid and a genuine readability problem in daylight.
 */
class ARArrowRenderer {

    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var alphaUniform = 0

    /**
     * Pre-allocated for the maximum arrow count so `onDrawFrame` never allocates. Grown only if the
     * cap is ever raised at runtime.
     */
    private var vertexBuffer: FloatBuffer = allocate(MAX_VERTICES * FLOATS_PER_VERTEX)

    /** Scratch buffer reused every frame. */
    private val pending = FloatArray(MAX_VERTICES * FLOATS_PER_VERTEX)

    /** Set from onSurfaceCreated; false until GL is ready, so draw calls are simply skipped. */
    private var ready = false

    /** Called once when the GL surface is created. */
    fun onSurfaceCreated() {
        program = buildProgram()
        if (program != 0) {
            positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
            colorHandle = GLES20.glGetAttribLocation(program, "a_Color")
            alphaUniform = GLES20.glGetUniformLocation(program, "u_Alpha")
        }
        ready = program != 0
    }

    /**
     * Draws [arrows] back-to-front. Each chevron is a filled triangle (a simple forward wedge):
     * cheap, legible at a distance, and unambiguous in direction — a thin outline would disappear
     * against a bright road.
     */
    fun draw(arrows: List<ScreenArrow>, alpha: Float) {
        if (!ready || arrows.isEmpty()) return
        val required = arrows.size * FLOATS_PER_CHEVRON
        if (required > pending.size) return // capped upstream; never grow mid-frame

        var offset = 0
        arrows.forEach { arrow ->
            val r = arrow.radiusPx
            // Forward wedge pointing along the node's route tangent, in screen space.
            val tipX = arrow.x
            val tipY = arrow.y - r * TIP_SCALE
            val baseLeftX = arrow.x - r * BASE_SCALE
            val baseLeftY = arrow.y + r * BASE_SCALE
            val baseRightX = arrow.x + r * BASE_SCALE
            val baseRightY = arrow.y + r * BASE_SCALE

            pending[offset++] = tipX; pending[offset++] = tipY; pending[offset++] = COLOR_R
            pending[offset++] = COLOR_G; pending[offset++] = COLOR_B
            pending[offset++] = baseLeftX; pending[offset++] = baseLeftY; pending[offset++] = COLOR_R
            pending[offset++] = COLOR_G; pending[offset++] = COLOR_B
            pending[offset++] = baseRightX; pending[offset++] = baseRightY; pending[offset++] = COLOR_R
            pending[offset++] = COLOR_G; pending[offset++] = COLOR_B
        }

        vertexBuffer.clear()
        vertexBuffer.put(pending, 0, offset)
        vertexBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glUniform4f(alphaUniform, COLOR_R, COLOR_G, COLOR_B, alpha)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        // Colour is a uniform; disable the per-vertex attribute the shader declares but we ignore.
        GLES20.glDisableVertexAttribArray(colorHandle)
        // Depth test off: arrows are an overlay, ordered back-to-front by the projector. Writing
        // depth would let an arrow occlude the road it is drawn on top of.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, arrows.size * 3)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        if (vertex == 0) return 0
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (fragment == 0) return 0
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertex)
            GLES20.glAttachShader(it, fragment)
            GLES20.glLinkProgram(it)
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private companion object {
        /** Electric blue, matching the app's route line and CTA accent. */
        const val COLOR_R = 47f / 255f
        const val COLOR_G = 123f / 255f
        const val COLOR_B = 255f / 255f

        const val FLOATS_PER_VERTEX = 4
        const val FLOATS_PER_CHEVRON = FLOATS_PER_VERTEX * 3
        const val VERTICES_PER_CHEVRON = 3
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

        /** Matches ARArrowConfig.maxVisibleArrows; hard ceiling so the buffer is bounded. */
        const val MAX_ARROWS = 24
        const val MAX_VERTICES = MAX_ARROWS * VERTICES_PER_CHEVRON

        const val TIP_SCALE = 1.15f
        const val BASE_SCALE = 0.8f

        fun allocate(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        const val VERTEX_SHADER = """
            attribute vec2 a_Position;
            attribute vec4 a_Color;
            varying vec4 v_Color;
            void main() {
                gl_Position = vec4(a_Position, 0.0, 1.0);
                v_Color = a_Color;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Alpha;
            varying vec4 v_Color;
            void main() {
                gl_FragColor = vec4(u_Alpha.rgb, u_Alpha.a);
            }
        """
    }
}
package com.openmaps.app.arrow

import android.opengl.GLES20
import com.openmaps.app.core.arrow.ScreenArrow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the AR chevrons. The only file that turns arrow data into OpenGL calls.
 *
 * Each arrow is a triangle and all arrows are uploaded in a single bounded, reusable buffer.
 * The projectors intentionally return screen pixels; ARArrowVertexGeometry converts these to
 * OpenGL normalized-device coordinates using the *actual GL view's pixel dimensions*.
 */
class ARArrowRenderer {

    private var program = 0
    private var positionHandle = 0
    private var alphaUniform = 0

    /** Sized for at most 24 triangles; exactly two floats (x, y) per vertex. */
    private val vertexBuffer: FloatBuffer = allocate(ARArrowVertexGeometry.MAX_FLOATS)
    private val pending = FloatArray(ARArrowVertexGeometry.MAX_FLOATS)

    /** Set from onSurfaceCreated; false until GL is ready. */
    private var ready = false

    fun onSurfaceCreated() {
        program = buildProgram()
        if (program != 0) {
            positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
            alphaUniform = GLES20.glGetUniformLocation(program, "u_Alpha")
        }
        ready = program != 0 && positionHandle >= 0 && alphaUniform >= 0
    }

    /**
     * Draws chevrons back-to-front. The viewport matches the GLSurfaceView's measured size
     * (physical pixels, not dp). Never pass projected pixel values directly to gl_Position.
     *
     * Two passes per frame, both from the exact same [arrows]/positions — only size, color and
     * blend mode differ, so the glow can never drift from the core shape it is lighting:
     *  1. **Glow**: each chevron enlarged (wider span, thicker stroke) and drawn with additive
     *     blending at low alpha. Additive blending is what makes overlapping glow regions brighten
     *     together into one soft halo instead of a flat translucent shape.
     *  2. **Core**: the exact geometry [ARArrowVertexGeometry] always produced, at full brightness,
     *     normal alpha blending on top — this is what actually reads as a crisp chevron; the glow
     *     pass alone would look like a blur with no edge.
     *
     * Without this, the reference design's lit/neon look was unreachable: a flat single-pass fill is
     * the single biggest reason the same geometry can look like "a thin line is technically there"
     * on a bright daylight camera feed instead of an unmistakable glowing path.
     */
    fun draw(arrows: List<ScreenArrow>, alpha: Float, viewportWidthPx: Int, viewportHeightPx: Int) {
        if (!ready || arrows.isEmpty() || alpha <= 0f) return
        val clampedAlpha = alpha.coerceIn(0f, 1f)

        GLES20.glUseProgram(program)
        GLES20.glVertexAttribPointer(
            positionHandle,
            ARArrowVertexGeometry.FLOATS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            ARArrowVertexGeometry.STRIDE_BYTES,
            vertexBuffer,
        )
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)

        // Pass 1: glow. Additive (GL_ONE, GL_ONE) so overlapping halos accumulate brightness rather
        // than averaging into a flat tint, and alpha stays low so it never reads as its own shape.
        val glowFloats = ARArrowVertexGeometry.write(
            arrows = arrows,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            output = pending,
            radiusScale = GLOW_RADIUS_SCALE,
        )
        if (glowFloats > 0) {
            vertexBuffer.clear()
            vertexBuffer.put(pending, 0, glowFloats)
            vertexBuffer.position(0)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            GLES20.glUniform4f(
                alphaUniform,
                GLOW_COLOR_R,
                GLOW_COLOR_G,
                GLOW_COLOR_B,
                clampedAlpha * GLOW_ALPHA_SCALE,
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, glowFloats / ARArrowVertexGeometry.FLOATS_PER_VERTEX)
        }

        // Pass 2: the crisp core chevron, exactly as always, normal alpha blending on top of the glow.
        val coreFloats = ARArrowVertexGeometry.write(
            arrows = arrows,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            output = pending,
        )
        if (coreFloats > 0) {
            vertexBuffer.clear()
            vertexBuffer.put(pending, 0, coreFloats)
            vertexBuffer.position(0)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUniform4f(alphaUniform, COLOR_R, COLOR_G, COLOR_B, clampedAlpha)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, coreFloats / ARArrowVertexGeometry.FLOATS_PER_VERTEX)
        }

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
        /**
         * Core chevron color: electric blue blended toward the brand's cyan accent (both already
         * used elsewhere in Live — see `OpenMapsColors`), rather than the flat electric blue this
         * used before. A pure mid-blue photographs as dull/grey on a bright daylight camera feed;
         * this stays brand-accurate while reading as a visibly "lit" blue instead.
         */
        const val COLOR_R = 66f / 255f
        const val COLOR_G = 165f / 255f
        const val COLOR_B = 255f / 255f

        /** Glow pass color: leans further toward cyan/white, the way a light source's halo is paler
         * than the light itself. */
        const val GLOW_COLOR_R = 140f / 255f
        const val GLOW_COLOR_G = 225f / 255f
        const val GLOW_COLOR_B = 255f / 255f

        /** How much larger the glow pass is than the core chevron it surrounds. */
        const val GLOW_RADIUS_SCALE = 2.1f

        /** Glow alpha as a fraction of the frame's overall arrow alpha — low enough that overlapping
         * glow from adjacent chevrons brightens gradually rather than instantly saturating white. */
        const val GLOW_ALPHA_SCALE = 0.38f

        fun allocate(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        const val VERTEX_SHADER = """
            attribute vec2 a_Position;
            void main() {
                gl_Position = vec4(a_Position, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Alpha;
            void main() {
                gl_FragColor = u_Alpha;
            }
        """
    }
}

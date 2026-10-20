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
     */
    fun draw(arrows: List<ScreenArrow>, alpha: Float, viewportWidthPx: Int, viewportHeightPx: Int) {
        if (!ready || arrows.isEmpty() || alpha <= 0f) return

        val floatCount = ARArrowVertexGeometry.write(
            arrows = arrows,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            output = pending,
        )
        if (floatCount == 0) return

        vertexBuffer.clear()
        vertexBuffer.put(pending, 0, floatCount)
        vertexBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glUniform4f(alphaUniform, COLOR_R, COLOR_G, COLOR_B, alpha.coerceIn(0f, 1f))
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
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(
            GLES20.GL_TRIANGLES,
            0,
            floatCount / ARArrowVertexGeometry.FLOATS_PER_VERTEX,
        )
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

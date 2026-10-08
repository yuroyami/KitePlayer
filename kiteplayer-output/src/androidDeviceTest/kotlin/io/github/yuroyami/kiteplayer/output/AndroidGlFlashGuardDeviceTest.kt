package io.github.yuroyami.kiteplayer.output

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi
import io.github.yuroyami.kiteplayer.spi.VideoFlashGuard
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The flash guard on the GL tier (#500), in real pixels through a real GLES2 driver: the blit draws
 * the picture into the guard's small framebuffer, the detector reads it, and the blit then draws
 * the picture dimmed. The blit's body runs over an ordinary texture, as in
 * [AndroidGlRenderQualityDeviceTest], because only MediaCodec can fill an external one.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(KitePlayerLowLevelApi::class)
class AndroidGlFlashGuardDeviceTest {

    private fun light(code: Int): Float {
        val encoded = code / 255f
        return if (encoded <= 0.04045f) encoded / 12.92f else ((encoded + 0.055f) / 1.055f).pow(2.4f)
    }

    /**
     * Draws [shades], grey pictures at 30 a second, as the renderer does: measure, ask the detector,
     * draw with the factor. Answers each picture's factor and its drawn bytes.
     */
    private fun drawn(shades: List<Int>, guarded: Boolean = true): List<Pair<Float, ByteArray>> {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) { "eglInitialize" }
        val configs = arrayOfNulls<EGLConfig>(1)
        check(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, IntArray(1), 0,
            ),
        ) { "no RGBA8 pbuffer config" }
        val context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        val surface = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE), 0,
        )
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
        try {
            GLES20.glDisable(GLES20.GL_DITHER)
            val blit = BlitProgram(link())
            val texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val meter = GlFlashMeter()
            val guard = VideoFlashGuard()
            var measureNanos = 0L
            fun draw(adjust: FloatArray?) = blit.draw(
                GLES20.GL_TEXTURE_2D, texture, IDENTITY, GlState.TEX_COORDS, SIZE, SIZE,
                adjust = adjust, ditherStep = 0f, debandThreshold = 0f, debandRange = 0f, debandGrain = 0f,
                debandSeed = 0f, bicubic = false, linearLight = false,
            )
            val out = shades.mapIndexed { index, shade ->
                val source = ByteBuffer.allocateDirect(SIZE * SIZE * 4)
                repeat(SIZE * SIZE) { source.put(shade.toByte()).put(shade.toByte()).put(shade.toByte()).put(-1) }
                source.position(0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, SIZE, SIZE, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, source,
                )
                val factor = if (guarded) {
                    val startedAt = System.nanoTime()
                    val cells = meter.measure { draw(null) }
                    measureNanos += System.nanoTime() - startedAt
                    guard.factorFor(cells, index * 1_000_000_000L / 30)
                } else {
                    1f
                }
                GLES20.glViewport(0, 0, SIZE, SIZE)
                draw(dimGlAdjust(null, factor))
                val read = ByteBuffer.allocateDirect(SIZE * SIZE * 4)
                GLES20.glReadPixels(0, 0, SIZE, SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, read)
                check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "a GL error after picture $index" }
                factor to ByteArray(SIZE * SIZE * 4).also { read.position(0); read.get(it) }
            }
            if (guarded) Log.i(TAG, "measuring took ${measureNanos / shades.size / 1000} microseconds a picture")
            meter.close()
            return out
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }

    private fun link(): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, GlState.VERTEX_SHADER))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, GlState.PLAIN_FRAGMENT_SHADER))
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        return program
    }

    @Test
    fun aStrobeIsDimmedFromThePictureThatMakesItsRun() {
        // Black and white, three pictures each: five flashes a second.
        val out = drawn(List(90) { if (it / 3 % 2 == 0) 0 else 255 })
        val reds = out.map { it.second[(SIZE / 2 * SIZE + SIZE / 2) * 4].toInt() and 0xFF }
        // The seventh leg is on picture 21. It is measured before it is drawn, so it is dimmed itself.
        assertEquals(21, out.indexOfFirst { it.first < 1f }, "the factors were ${out.map { it.first }}")
        assertEquals(List(21) { if (it / 3 % 2 == 0) 0 else 255 }, reds.take(21), "whole before the run")
        val lights = reds.drop(21).map(::light)
        val largest = lights.zipWithNext().maxOf { (a, b) -> abs(b - a) }
        assertTrue(largest < 0.10f, "a leg of $largest came out after the run started")
        assertTrue(lights.max() > 0.05f, "the picture is dimmed, not blacked out: ${lights.max()}")
    }

    @Test
    fun aPictureThatDoesNotFlashIsDrawnBitForBit() {
        // A slow ramp and one cut: never a run.
        val shades = List(60) { if (it < 30) 40 + it else 200 }
        val guarded = drawn(shades)
        val plain = drawn(shades, guarded = false)
        assertTrue(guarded.all { it.first == 1f })
        guarded.indices.forEach { assertContentEquals(plain[it].second, guarded[it].second, "picture $it") }
    }

    private companion object {
        const val TAG = "KiteFlashGuard"
        const val SIZE = 64
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }
}

package io.github.yuroyami.kiteplayer.output

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertTrue

/**
 * The animation upscaler on a real GLES driver (#67): both tiers draw what the CPU reference
 * draws, through the exact sequence the renderer runs, and what each costs a frame.
 *
 * The renderer reads MediaCodec's external texture, which only a codec can fill, so the picture
 * here is an ordinary texture holding the fixture top row first, the way a decoded buffer is laid
 * out. SurfaceTexture's transform for such a buffer is the row flip, and that is the matrix the
 * picture is drawn through. Everything after that is [drawUpscaled], which the renderer calls too.
 *
 * The cost test writes one line per tier to logcat: `adb logcat -s KiteUpscaleCost`.
 */
@RunWith(AndroidJUnit4::class)
class AnimationUpscaleDeviceTest {

    /** One offscreen context, made current on the test's thread. */
    private class Context : AutoCloseable {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface

        init {
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) { "eglInitialize" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(
                EGL14.eglChooseConfig(
                    display,
                    intArrayOf(
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_NONE,
                    ),
                    0, configs, 0, 1, count, 0,
                ) && count[0] > 0,
            ) { "no RGBA8 pbuffer config" }
            val config = requireNotNull(configs[0])
            // Version 2, as the renderer asks: the upscaler has to work with what that returns.
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            check(context !== EGL14.EGL_NO_CONTEXT) { "no GLES2 context" }
            surface = EGL14.eglCreatePbufferSurface(
                display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
            GLES20.glDisable(GLES20.GL_DITHER)
        }

        override fun close() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }

    /** An RGBA8 texture and the framebuffer that draws into it. */
    private class Target(val width: Int, val height: Int) : AutoCloseable {
        val texture: Int = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        val framebuffer: Int

        init {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            framebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0,
            )
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        /** The written pixels, top row first: GL's row 0 is the bottom, as an EGL window shows it. */
        fun readUpright(): ByteArray {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            val read = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, read)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            val bottomFirst = ByteArray(width * height * 4).also { read.get(it) }
            val upright = ByteArray(bottomFirst.size)
            for (y in 0 until height) {
                System.arraycopy(bottomFirst, (height - 1 - y) * width * 4, upright, y * width * 4, width * 4)
            }
            return upright
        }

        override fun close() {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    private fun pictureTexture(rgba: FloatArray, width: Int, height: Int): Int {
        val bytes = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder())
        rgba.forEach { bytes.put((it.coerceIn(0f, 1f) * 255f).roundToInt().toByte()) }
        bytes.position(0)
        val texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes,
        )
        return texture
    }

    private fun plainBlit(): BlitProgram {
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
        return BlitProgram(program)
    }

    private fun drawFrame(upscale: AnimationUpscaleGl, blit: BlitProgram, picture: Int, target: Target) {
        drawUpscaled(
            upscale, blit, GLES20.GL_TEXTURE_2D, picture, GlState.TOP_FIRST_TEXTURE_MATRIX,
            blit, target.framebuffer, target.width, target.height,
            adjust = null, ditherStep = 0f, debandThreshold = 0f, debandRange = 16f, debandGrain = 0f,
            debandSeed = 0f, bicubic = false, linearLight = false,
        )
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "GL error 0x${error.toString(16)} after the upscaled frame" }
    }

    /**
     * The golden test. Drawn at exactly twice its size, the blit after the network adds nothing of
     * its own, so the frame is the network's output in 8 bits: within one level of the reference
     * wherever the half-float storage rounds, and well away from what bilinear alone would draw.
     * Upside down it would miss by about 70 levels on average.
     */
    @Test
    fun bothTiersDrawTheReferencePictureUpright() {
        val fixture = Anime4kFixture()
        Context().use {
            val picture = pictureTexture(fixture.picture, fixture.width, fixture.height)
            val blit = plainBlit()
            for (network in listOf(Anime4kNetworks.small, Anime4kNetworks.medium)) {
                val reference = Anime4kReference.upscale(network, fixture.picture, fixture.width, fixture.height)
                AnimationUpscaleGl.create(network).use { upscale ->
                    upscale.resize(fixture.width, fixture.height)
                    Target(fixture.width * 2, fixture.height * 2).use { target ->
                        drawFrame(upscale, blit, picture, target)
                        val drawn = target.readUpright()
                        var worst = 0
                        var total = 0L
                        var fromBilinear = 0L
                        for (i in drawn.indices) {
                            if (i % 4 == 3) continue
                            val got = drawn[i].toInt() and 0xFF
                            val want = (reference[i].coerceIn(0f, 1f) * 255f).roundToInt()
                            worst = maxOf(worst, abs(got - want))
                            total += abs(got - want)
                        }
                        val bilinear = bilinearDoubled(fixture)
                        for (i in drawn.indices) if (i % 4 != 3) fromBilinear += abs((drawn[i].toInt() and 0xFF) - bilinear[i])
                        val samples = drawn.size / 4 * 3
                        val mean = total.toDouble() / samples
                        assertTrue(worst <= 2 && mean < 0.25, "${network.name} misses the reference by up to $worst levels, $mean on average")
                        assertTrue(
                            fromBilinear.toDouble() / samples > 2.0,
                            "${network.name} drew within ${fromBilinear.toDouble() / samples} levels of plain bilinear",
                        )
                    }
                }
            }
            GLES20.glDeleteProgram(blit.program)
            GLES20.glDeleteTextures(1, intArrayOf(picture), 0)
        }
    }

    /**
     * What a frame costs on this GPU: a 1280x720 picture doubled and drawn at 2560x1440, as on a
     * 1440p phone, the median of 30 frames each finished before the next. Logged, not judged: the
     * numbers are what decides whether a tier may ever default on, per device class (#67).
     */
    @Test
    fun eachTierReportsWhatAFrameCosts() {
        val width = 1280
        val height = 720
        Context().use {
            val pixels = FloatArray(width * height * 4) { i -> if (i % 4 == 3) 1f else ((i / 4) % 251) / 250f }
            val picture = pictureTexture(pixels, width, height)
            val blit = plainBlit()
            for (network in listOf(Anime4kNetworks.small, Anime4kNetworks.medium)) {
                AnimationUpscaleGl.create(network).use { upscale ->
                    upscale.resize(width, height)
                    Target(width * 2, height * 2).use { target ->
                        repeat(3) { drawFrame(upscale, blit, picture, target) }
                        GLES20.glFinish()
                        val times = DoubleArray(30) {
                            val start = SystemClock.elapsedRealtimeNanos()
                            drawFrame(upscale, blit, picture, target)
                            GLES20.glFinish()
                            (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                        }.sorted()
                        val line = "${network.name}: ${width}x$height to ${width * 2}x${height * 2} " +
                            "median %.2f ms, fastest %.2f ms, slowest %.2f ms on %s".format(
                                times[times.size / 2], times.first(), times.last(),
                                GLES20.glGetString(GLES20.GL_RENDERER),
                            )
                        Log.i("KiteUpscaleCost", line)
                        println(line)
                    }
                }
            }
            GLES20.glDeleteProgram(blit.program)
            GLES20.glDeleteTextures(1, intArrayOf(picture), 0)
        }
    }

    /** The fixture doubled with bilinear at texel centres, in 8 bits, as the plain blit draws it. */
    private fun bilinearDoubled(fixture: Anime4kFixture): IntArray {
        val nothing = Anime4kNetwork(
            "bilinear",
            listOf(
                Anime4kLayer(
                    listOf(Anime4kTerm(Anime4kTerm.SOURCE, Anime4kPart.Raw, 0, 0, List(16) { "0.0" }.joinToString(", "))),
                    "0.0, 0.0, 0.0, 0.0",
                ),
            ),
        )
        val doubled = Anime4kReference.upscale(nothing, fixture.picture, fixture.width, fixture.height)
        return IntArray(doubled.size) { (doubled[it].coerceIn(0f, 1f) * 255f).roundToInt() }
    }
}

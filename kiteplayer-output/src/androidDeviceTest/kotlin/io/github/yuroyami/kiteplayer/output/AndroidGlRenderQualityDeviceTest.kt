package io.github.yuroyami.kiteplayer.output

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 17.21 RQ-1 to RQ-3 on the Android tier, in real pixels through a real GLES2 driver.
 *
 * The Metal half of the ladder has had this from the start and the GLES2 half never did, which is
 * how two passes that compiled and did nothing survived review. Nothing here mocks GL: the driver
 * compiles [GlState.FRAGMENT_BODY], the exact string the blit runs, draws it, and the assertions
 * read the bytes back.
 *
 * The one difference from the blit is the sampler's type. MediaCodec is the only thing that can
 * fill an external texture, so a test holding one could not put a known pattern in; the body is
 * compiled over an ordinary texture instead through [GlState.TEST_FRAGMENT_SHADER]. That header
 * is two lines and the arithmetic under it is shared verbatim.
 */
@RunWith(AndroidJUnit4::class)
class AndroidGlRenderQualityDeviceTest {

    /** One offscreen GLES2 context running the blit body over a texture the caller fills. */
    private class Harness : AutoCloseable {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val config: EGLConfig
        private val context: EGLContext
        private val program: Int
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private val texture: Int

        init {
            check(display !== EGL14.EGL_NO_DISPLAY) { "EGL has no display" }
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
            config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            check(context !== EGL14.EGL_NO_CONTEXT) { "no GLES2 context" }
            resize(1, 1)
            // The blit does the same, for the reason recorded there: one ordered pattern in the
            // picture, and it should be the ladder's.
            GLES20.glDisable(GLES20.GL_DITHER)
            program = linkTestProgram()
            texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun resize(width: Int, height: Int) {
            if (width == surfaceWidth && height == surfaceHeight) return
            if (surface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                EGL14.eglDestroySurface(display, surface)
            }
            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
            )
            check(surface !== EGL14.EGL_NO_SURFACE) { "no ${width}x$height pbuffer" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
            surfaceWidth = width
            surfaceHeight = height
        }

        private fun linkTestProgram(): Int {
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
            GLES20.glAttachShader(
                program, compile(GLES20.GL_FRAGMENT_SHADER, GlState.TEST_FRAGMENT_SHADER),
            )
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
            return program
        }

        /**
         * Draws [source] through the blit body and returns the written pixels.
         *
         * Rows run bottom-up on both sides: texture row 0 sits at v = 0, and glReadPixels starts
         * at the bottom, so index arithmetic on the two agrees without a flip.
         */
        fun render(
            source: IntArray,
            sourceWidth: Int,
            sourceHeight: Int,
            outputWidth: Int = sourceWidth,
            outputHeight: Int = sourceHeight,
            ditherStep: Float = 0f,
            debandThreshold: Float = 0f,
            debandRange: Float = 16f,
            debandGrain: Float = 0f,
            debandSeed: Float = 7f,
            bicubic: Boolean = false,
            colourOffset: Float = 0f,
            repeat: Int = 1,
        ): IntArray {
            require(source.size == sourceWidth * sourceHeight)
            resize(outputWidth, outputHeight)
            val pixels = ByteBuffer.allocateDirect(source.size * 4).order(ByteOrder.nativeOrder())
            source.forEach { pixels.putInt(Integer.reverseBytes(it)) }
            pixels.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, sourceWidth, sourceHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels,
            )
            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            fun uniform(name: String) = GLES20.glGetUniformLocation(program, name)
            GLES20.glUniform1i(uniform("uTexture"), 0)
            GLES20.glUniformMatrix4fv(uniform("uTexMatrix"), 1, false, IDENTITY_4X4, 0)
            GLES20.glUniform2f(uniform("uSourceSize"), sourceWidth.toFloat(), sourceHeight.toFloat())
            GLES20.glUniformMatrix3fv(uniform("uColorMatrix"), 1, false, IDENTITY_3X3, 0)
            GLES20.glUniform3f(uniform("uColorOffset"), colourOffset, colourOffset, colourOffset)
            GLES20.glUniform1f(uniform("uColorEnabled"), if (colourOffset != 0f) 1f else 0f)
            GLES20.glUniform1f(uniform("uDitherStep"), ditherStep)
            GLES20.glUniform1f(uniform("uDebandThreshold"), debandThreshold)
            GLES20.glUniform1f(uniform("uDebandRange"), debandRange)
            GLES20.glUniform1f(uniform("uDebandGrain"), debandGrain)
            GLES20.glUniform1f(uniform("uDebandSeed"), debandSeed)
            GLES20.glUniform1f(uniform("uBicubic"), if (bicubic) 1f else 0f)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glEnableVertexAttribArray(texCoord)
            GlState.VERTICES.position(0)
            GlState.TEX_COORDS.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, GlState.VERTICES)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, GlState.TEX_COORDS)
            // Drawing more than once is how a COST is taken: the upload and the readback around
            // this call dwarf one blit, so a per-pass number only appears when the draw repeats.
            kotlin.repeat(repeat) { GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4) }
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "GL error 0x${error.toString(16)} after draw" }
            val read = ByteBuffer.allocateDirect(outputWidth * outputHeight * 4)
                .order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(
                0, 0, outputWidth, outputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, read,
            )
            read.position(0)
            return IntArray(outputWidth * outputHeight) { Integer.reverseBytes(read.int) }
        }

        override fun close() {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (surface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }

        private companion object {
            val IDENTITY_4X4 = floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            )
            val IDENTITY_3X3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
    }

    private fun grey(value: Int): Int = (value shl 24) or (value shl 16) or (value shl 8) or 0xFF

    private fun red(pixel: Int): Int = (pixel ushr 24) and 0xFF

    /** [GlState]'s Bayer value, recomputed here so the shader is checked against arithmetic. */
    private fun bayer8(x: Int, y: Int): Int {
        val px = x % 8
        val py = y % 8
        return 32 * (px / 4) + 16 * (py / 4) + 8 * (px / 2 % 2) + 4 * (py / 2 % 2) +
            2 * (px % 2) + (py % 2)
    }

    /** The shader's own centring: one output step of Bayer, centred on zero. */
    private fun pattern(bayer: Int): Float = (bayer + 0.5f) / 64f - 0.5f

    private inline fun <T> harness(block: (Harness) -> T): T = Harness().use(block)

    /**
     * Where THIS driver's float-to-8-bit write actually crosses from [level] to the next one.
     *
     * Measured rather than assumed, and the difference is not academic: the emulator crosses at
     * about 120.52 of 255, not at 120.5, so every prediction that hard-codes a half step is off by
     * a fraction of a level. That is the driver's business and not the shader's, and a test that
     * asserts the shader must not silently be asserting the driver too.
     */
    private fun Harness.writeBoundaryAbove(level: Int): Float {
        val black = IntArray(1) { grey(0) }
        fun writes(value: Float) = red(render(black, 1, 1, colourOffset = value / 255f)[0])
        var low = level.toFloat()
        var high = level + 1f
        check(writes(low) == level && writes(high) == level + 1) {
            "the write is not monotone around $level, so nothing below can be calibrated"
        }
        repeat(24) {
            val mid = (low + high) / 2f
            if (writes(mid) > level) high = mid else low = mid
        }
        return high
    }

    /** RQ's first law: everything off writes the pre-17.21 pixels, and here that is bit for bit. */
    @Test
    fun neutralQualityIsBitExact() = harness { gl ->
        val source = IntArray(16 * 16) { grey((it * 7 + 3) % 256) }
        assertContentEquals(
            source,
            gl.render(source, 16, 16),
            "the neutral pass changed the write, so no rung below it can be measured on its own",
        )
    }

    /**
     * The ordered pattern is present, and it is the Bayer matrix rather than noise.
     *
     * The value under test comes from the colour offset, not from the texture, and that is the
     * whole point. An 8-bit texel is already exactly representable in an 8-bit target, so a
     * half-step of dither rounds straight back to where it started and the pass looks broken when
     * it is working. Only a value BETWEEN two output levels can show it, so the shader is handed
     * one: 120.36 of 255, which must land on 120 where the pattern is low and 121 where it is high.
     */
    @Test
    fun ditherSpreadsOneStepAlongTheBayerMatrix() = harness { gl ->
        val source = IntArray(8 * 8) { grey(0) }
        val offset = 120.36f / 255f
        val plain = gl.render(source, 8, 8, colourOffset = offset)
        assertTrue(
            plain.all { red(it) == 120 },
            "an undithered 120.36 must truncate to one level, got ${plain.map(::red).distinct()}",
        )
        val dithered = gl.render(source, 8, 8, ditherStep = 1f / 255f, colourOffset = offset)
        val levels = dithered.map(::red).distinct().sorted()
        assertEquals(listOf(120, 121), levels, "dither must spread over the two bracketing levels")
        // The pattern, not just its presence: the high level appears at exactly the positions
        // whose Bayer value pushes 120.36 over where this driver's write actually crosses.
        val highs = (0 until 64).filter { red(dithered[it]) == 121 }
        val threshold = highs.minOf { bayer8(it % 8, it / 8) }
        val outOfOrder = (0 until 64).filter { index ->
            (red(dithered[index]) == 121) != (bayer8(index % 8, index / 8) >= threshold)
        }
        assertEquals(
            emptyList(),
            outOfOrder,
            "the high level must follow the Bayer ORDER with no exception; threshold " +
                "$threshold, map " +
                (0 until 64).joinToString { "${bayer8(it % 8, it / 8)}=${red(dithered[it])}" },
        )
        val boundary = gl.writeBoundaryAbove(120)
        val predicted = (0..63).first { bayer -> 120.36f + pattern(bayer) >= boundary }
        assertEquals(
            predicted,
            threshold,
            "the ordered pattern must carry 120.36 across $boundary at exactly the Bayer value " +
                "the arithmetic says, and nowhere else",
        )
    }

    /** A flat field has nothing to smooth, so the pass must leave every pixel alone. */
    @Test
    fun debandLeavesAFlatFieldAlone() = harness { gl ->
        val source = IntArray(32 * 32) { grey(100) }
        assertContentEquals(
            source,
            gl.render(source, 32, 32, debandThreshold = 48f / 16384f, debandRange = 3f),
            "debanding moved a field that has no band in it",
        )
    }

    /** The other half: a real edge is not a band, and the same threshold must keep it. */
    @Test
    fun debandRefusesAHardEdge() = harness { gl ->
        val edge = IntArray(32 * 32) { grey(if (it % 32 < 16) 0 else 255) }
        assertContentEquals(
            gl.render(edge, 32, 32),
            gl.render(edge, 32, 32, debandThreshold = 48f / 16384f, debandRange = 3f),
            "debanding softened a real edge, which is the failure this threshold exists to avoid",
        )
    }

    /**
     * RQ-2 and RQ-1 are one pass in practice, and this is the measurement that says so.
     *
     * Debanding a one-step band produces values BETWEEN two 8-bit levels, and an 8-bit target
     * cannot hold one. Most of that smoothing is therefore thrown away at the write unless
     * something carries the fraction across, and dither is what carries it. So the comparison is
     * not deband against nothing; it is how much MORE of the pass survives once dither is under
     * it. On the emulator that is 25 pixels alone against 115 carried, and both sets sit inside
     * the ring's own reach of the boundary, which is the second half of the claim.
     */
    @Test
    fun ditherCarriesWhatDebandingCannotWriteOnItsOwn() = harness { gl ->
        val threshold = 48f / 16384f
        val band = IntArray(32 * 32) { grey(if (it % 32 < 16) 100 else 101) }
        val plain = gl.render(band, 32, 32)
        val debandOnly = gl.render(band, 32, 32, debandThreshold = threshold, debandRange = 3f)
        val ditherOnly = gl.render(band, 32, 32, ditherStep = 1f / 255f)
        val both = gl.render(
            band, 32, 32,
            ditherStep = 1f / 255f, debandThreshold = threshold, debandRange = 3f,
        )
        assertEquals(
            listOf(100, 101),
            plain.map(::red).distinct().sorted(),
            "the untouched band must be its own two levels and nothing else",
        )
        val alone = plain.indices.filter { plain[it] != debandOnly[it] }
        val carried = ditherOnly.indices.filter { ditherOnly[it] != both[it] }
        assertTrue(
            carried.size > 2 * alone.size,
            "debanding must reach the write far more often with dither under it than without; " +
                "got ${carried.size} carried against ${alone.size} alone, which means the two " +
                "passes are not compounding and one of them is doing nothing",
        )
        // CONCENTRATED in the ring's reach, not confined to it, and the difference is the driver's.
        // Where the write does not cross at the half step, a pixel whose dithered value lands
        // within a hundredth of a level of the crossing can flip on nothing more than the two
        // renders taking different branches of the shader. That is a handful of pixels anywhere in
        // the frame and it is not either pass doing work. The Adreno crosses close enough to the
        // half step to have none; the emulator has a few.
        fun withinReach(indices: List<Int>) = indices.count { (it % 32) in 12..19 }
        assertTrue(
            withinReach(carried) * 5 >= carried.size * 4,
            "at least four fifths of the carried change must sit within the ring's reach of the " +
                "boundary at 16; columns were ${carried.map { it % 32 }.distinct().sorted()}",
        )
        assertTrue(
            withinReach(alone) * 5 >= alone.size * 4,
            "and the same for what debanding writes on its own; columns were " +
                "${alone.map { it % 32 }.distinct().sorted()}",
        )
        assertTrue(
            both.map(::red).all { it in 99..102 },
            "neither pass may smear the band beyond one level outside it, got " +
                "${both.map(::red).distinct().sorted()}",
        )
    }

    /**
     * The kernel is an interpolating cubic, proven by the two things that separate one from
     * bilinear rather than by counting samples, which does not.
     *
     * A step edge enlarged eight times is spread over four pixels by BOTH kernels, so the count of
     * intermediate values says nothing. What separates them is the shape: Catmull-Rom rises faster
     * at the middle, and it rings, so it leaves the source's own range on both sides. Bilinear can
     * do neither.
     */
    @Test
    fun catmullRomIsSteeperThanBilinearAndRings() = harness { gl ->
        val low = 40
        val high = 210
        val source = IntArray(8 * 8) { grey(if (it % 8 < 4) low else high) }
        val row = 4
        fun scan(bicubic: Boolean): List<Int> {
            val out = gl.render(source, 8, 8, outputWidth = 64, outputHeight = 64, bicubic = bicubic)
            return (16 until 48).map { red(out[row * 64 + it]) }
        }

        val bilinear = scan(bicubic = false)
        val cubic = scan(bicubic = true)
        fun steepness(scan: List<Int>) = scan.zipWithNext().maxOf { abs(it.second - it.first) }
        assertTrue(
            steepness(cubic) > steepness(bilinear),
            "Catmull-Rom must rise faster than bilinear: ${steepness(cubic)} vs " +
                "${steepness(bilinear)}. Equal means the kernel silently degraded to bilinear.",
        )
        assertTrue(
            bilinear.all { it in low..high },
            "bilinear cannot leave the source's range, got ${bilinear.min()}..${bilinear.max()}",
        )
        assertTrue(
            cubic.min() < low && cubic.max() > high,
            "an interpolating cubic rings on both sides of a step; got " +
                "${cubic.min()}..${cubic.max()} against $low..$high",
        )
    }

    /** At 1:1 the kernel's own weights are (0, 1, 0, 0), so asking for it must change nothing. */
    @Test
    fun catmullRomAtNativeSizeIsTheSourceItself() = harness { gl ->
        val source = IntArray(16 * 16) { grey((it * 11 + 5) % 256) }
        assertContentEquals(
            source,
            gl.render(source, 16, 16, bicubic = true),
            "the kernel must be the identity at 1:1, where every tap sits on a texel centre",
        )
    }
}

package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.AshimaNoise
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Silk
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.SilkStrand
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.hslRgb
import io.github.yuroyami.kiteplayer.audioviz.viz.restart
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SilkTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsTheStrandOnTheLivelySong() {
        val silk = Silk()
        assertEquals(PostSpec.Off, silk.post)
        var ink = 0f
        var blown = 0f
        var grain = 0f
        RenderHarness.forEachFrame(silk, 320, 180, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val pixels = pixels(bitmap)
            ink = maxOf(ink, ribbonShare(pixels))
            blown = maxOf(blown, blownShare(pixels))
            // The grey corner carries the coloured grain only when the vignette's shader ran.
            var tinted = 0
            for (y in 0 until 20) for (x in 0 until 20) if (chroma(pixels[y * 320 + x]) >= 3) tinted++
            grain = maxOf(grain, tinted / 400f)
        }
        assertTrue(ink > 0.004f, "the ribbons should put coloured ink down, had ${ink * 100} percent")
        assertTrue(blown < 0.3f, "at most the white middle of the vignette may sit at full brightness, had ${blown * 100} percent")
        assertTrue(grain > 0.5f, "the vignette should carry its coloured grain, had ${grain * 100} percent tinted")
    }

    @Test
    fun silenceHoldsTheStrandStill() {
        val quiet = meanChange(RenderHarness.Song.Silence)
        val lively = meanChange(RenderHarness.Song.Lively)
        println("Silk frame-to-frame change: silence $quiet, lively $lively")
        assertTrue(lively > 0.002f, "the strand should move under music, changed $lively a frame")
        assertTrue(quiet <= 0.05f * lively, "silence should hold the strand still: $quiet against $lively")
    }

    @Test
    fun lineThreeDAddsOneToClipW() {
        // A silent strand at time 0: the ends have no radius, so they sit at y = z = 0.
        val strand = SilkStrand()
        strand.frame(1920f, 1080f, 0f)
        strand.line(0)
        val aspect = 1920f / 1080f
        // With the page's +1 on w, x = 1 lands at FOCAL / aspect / 1.5, about 0.80 of the half width.
        val share = SilkStrand.FOCAL / aspect / (SilkStrand.CAMERA_DISTANCE + 1f)
        assertEquals(960f + 960f * share, strand.centreX[SilkStrand.STEPS - 1], 0.01f)
        assertEquals(960f - 960f * share, strand.centreX[0], 0.01f)
        assertEquals(0.804f, share, 0.002f)
        // Without it the end would sit at FOCAL / aspect / 0.5, far outside the frame.
        assertTrue(SilkStrand.FOCAL / aspect / SilkStrand.CAMERA_DISTANCE > 2f)

        // A portrait frame keeps the share of the width the strand has at 16:9.
        strand.frame(1080f, 2400f, 0f)
        strand.line(0)
        val portrait = (strand.centreX[SilkStrand.STEPS - 1] - 540f) / 540f
        assertEquals(SilkStrand.FOCAL / SilkStrand.FRAMING_ASPECT / 1.5f, portrait, 0.001f)
    }

    @Test
    fun theRenderedStrandFitsInsideTheFrame() {
        var left = Int.MAX_VALUE
        var right = -1
        val width = 480
        val height = 270
        RenderHarness.forEachFrame(Silk(), width, height, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val pixels = pixels(bitmap)
            for (y in 0 until height) for (x in 0 until width) {
                if (chroma(pixels[y * width + x]) > RIBBON_CHROMA) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                }
            }
        }
        // The ends sit near 0.10 and 0.90 of the width and fade out a little inside them.
        assertTrue(left > width * 0.05f && right < width * 0.95f, "the strand should end inside the frame: $left to $right")
        assertTrue(right - left > width * 0.6f, "the strand should span most of the width: $left to $right")
    }

    @Test
    fun theSpectrumLiesAlongTheStrandBassAtTheLeft() {
        val strand = SilkStrand()
        val bytes = IntArray(1024) { it % 256 }
        strand.listen(bytes)
        for (i in 0 until SilkStrand.STEPS) {
            val u = strand.x[i] * 0.5f + 0.5f
            val bin = floor(u * 1024f).toInt().coerceIn(0, 1023)
            assertEquals(bytes[bin] / 256f, strand.frequencies[i], "point $i should read bin $bin")
        }
        assertEquals(0f, strand.frequencies[0])
        assertEquals(bytes[1023] / 256f, strand.frequencies[SilkStrand.STEPS - 1])
    }

    @Test
    fun theAnalyserIsReadSixtyTimesASecondOnAFastDisplay() {
        val silk = Silk()
        silk.restart()
        val player = RenderHarness.player(RenderHarness.Song.Lively, 6f)
        val bitmap = ImageBitmap(64, 36)
        val scope = CanvasDrawScope()
        val delta = 1f / 120f
        var elapsed = 0f
        var reads = 0
        var before = silk.strand.frequencies.copyOf()
        for (step in 0 until 240) {
            elapsed += delta
            val state = VizRenderState(player.next(delta), elapsed, delta, VizPalette.Prism, elapsed)
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(64f, 36f)) {
                with(silk) { draw(state) }
            }
            val now = silk.strand.frequencies
            if (step >= 120 && !now.contentEquals(before)) reads++
            before = now.copyOf()
        }
        // One second at 120 Hz: the page read once per 60 Hz frame, so about sixty reads.
        assertTrue(reads in 55..61, "the analyser should be read about sixty times a second, was $reads")
    }

    @Test
    fun theNoiseTablesMatchTheShaderLineByLine() {
        val random = Random(21)
        var worst = 0f
        repeat(20_000) {
            val x = random.nextFloat() * 20f - 10f
            val y = random.nextFloat() * 2_000f - 1_000f
            val z = random.nextFloat() * 400f - 200f
            val fast = AshimaNoise.simplex3(x, y, z)
            val literal = LiteralSimplex.snoise(x, y, z)
            worst = maxOf(worst, abs(fast - literal))
            assertTrue(abs(fast) <= 1.01f, "noise out of range at $x, $y, $z: $fast")
        }
        assertTrue(worst < 1e-5f, "the table noise should match the shader's arithmetic, differed by $worst")
    }

    @Test
    fun ribbonColoursFollowTheShadersHsl() {
        // hsl(0.5, 0.7, 0.6) is (0.32, 0.88, 0.88); hue 0.7 and 0.9 are the violet and the magenta.
        assertColour(0x52E0E0, hslRgb(0.5f, 0.7f, 0.6f))
        assertColour(0x6E52E0, hslRgb(0.7f, 0.7f, 0.6f))
        assertColour(0xE052A7, hslRgb(0.9f, 0.7f, 0.6f))
    }

    private fun assertColour(expected: Int, actual: Int) {
        for (shift in listOf(16, 8, 0)) {
            val a = expected shr shift and 0xFF
            val b = actual shr shift and 0xFF
            assertTrue(abs(a - b) <= 1, "colour ${actual.toString(16)} should be ${expected.toString(16)}")
        }
    }

    /** The mean luma change between consecutive frames, after the analyser and the strand settle. */
    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var sum = 0f
        var count = 0
        RenderHarness.forEachFrame(Silk(), 160, 90, 150, VizPalette.Prism, song) { bitmap, step ->
            if (step < 60) return@forEachFrame
            val pixels = pixels(bitmap)
            previous?.let { before ->
                var change = 0f
                for (index in pixels.indices) change += abs(luma(pixels[index]) - luma(before[index]))
                sum += change / pixels.size
                count++
            }
            previous = pixels
        }
        return sum / count.coerceAtLeast(1)
    }

    private fun pixels(bitmap: ImageBitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }

    /** The share of pixels coloured by a ribbon. The vignette is grey, with grain of a few levels. */
    private fun ribbonShare(pixels: IntArray): Float = pixels.count { chroma(it) > RIBBON_CHROMA }.toFloat() / pixels.size

    private fun blownShare(pixels: IntArray): Float = pixels.count {
        (it shr 16 and 0xFF) > 245 && (it shr 8 and 0xFF) > 245 && (it and 0xFF) > 245
    }.toFloat() / pixels.size

    private fun chroma(pixel: Int): Int {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    private fun luma(pixel: Int): Float =
        0.2126f * (pixel shr 16 and 0xFF) / 255f + 0.7152f * (pixel shr 8 and 0xFF) / 255f +
            0.0722f * (pixel and 0xFF) / 255f

    private companion object {
        const val RIBBON_CHROMA = 30
    }
}

/** glsl-noise's `simplex/3d` written out as the shader has it, vector by vector, to check the tables against. */
private object LiteralSimplex {
    private fun mod289(x: Float): Float = x - floor(x * (1f / 289f)) * 289f
    private fun permute(x: Float): Float = mod289(((x * 34f) + 1f) * x)
    private fun taylorInvSqrt(r: Float): Float = 1.79284291400159f - 0.85373472095314f * r
    private fun step(edge: Float, x: Float): Float = if (x < edge) 0f else 1f

    fun snoise(vx: Float, vy: Float, vz: Float): Float {
        val cx = 1f / 6f
        val cy = 1f / 3f
        val dot = vx * cy + vy * cy + vz * cy
        var ix = floor(vx + dot)
        var iy = floor(vy + dot)
        var iz = floor(vz + dot)
        val t = ix * cx + iy * cx + iz * cx
        val x0 = floatArrayOf(vx - ix + t, vy - iy + t, vz - iz + t)
        val g = floatArrayOf(step(x0[1], x0[0]), step(x0[2], x0[1]), step(x0[0], x0[2]))
        val l = floatArrayOf(1f - g[0], 1f - g[1], 1f - g[2])
        val i1 = floatArrayOf(minOf(g[0], l[2]), minOf(g[1], l[0]), minOf(g[2], l[1]))
        val i2 = floatArrayOf(maxOf(g[0], l[2]), maxOf(g[1], l[0]), maxOf(g[2], l[1]))
        val x1 = FloatArray(3) { x0[it] - i1[it] + cx }
        val x2 = FloatArray(3) { x0[it] - i2[it] + cy }
        val x3 = FloatArray(3) { x0[it] - 0.5f }
        ix = mod289(ix)
        iy = mod289(iy)
        iz = mod289(iz)
        val offsets = arrayOf(floatArrayOf(0f, 0f, 0f), i1, i2, floatArrayOf(1f, 1f, 1f))
        val p = FloatArray(4) { k ->
            permute(permute(permute(iz + offsets[k][2]) + iy + offsets[k][1]) + ix + offsets[k][0])
        }
        val n = 0.142857142857f
        val ns = floatArrayOf(n * 2f - 0f, n * 0.5f - 1f, n * 1f - 0f)
        val corners = arrayOf(x0, x1, x2, x3)
        var total = 0f
        for (k in 0 until 4) {
            val j = p[k] - 49f * floor(p[k] * ns[2] * ns[2])
            val xi = floor(j * ns[2])
            val yi = floor(j - 7f * xi)
            val x = xi * ns[0] + ns[1]
            val y = yi * ns[0] + ns[1]
            val h = 1f - abs(x) - abs(y)
            val sh = -step(h, 0f)
            var gx = x + (floor(x) * 2f + 1f) * sh
            var gy = y + (floor(y) * 2f + 1f) * sh
            var gz = h
            val norm = taylorInvSqrt(gx * gx + gy * gy + gz * gz)
            gx *= norm
            gy *= norm
            gz *= norm
            val c = corners[k]
            var m = maxOf(0.6f - (c[0] * c[0] + c[1] * c[1] + c[2] * c[2]), 0f)
            m *= m
            total += m * m * (gx * c[0] + gy * c[1] + gz * c[2])
        }
        return 42f * total
    }
}

package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.AshimaNoise
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Silk
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.SilkStrand
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.hslRgb
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

        val clothQuiet = meanChange(RenderHarness.Song.Silence, Silk.CLOTH)
        val clothLively = meanChange(RenderHarness.Song.Lively, Silk.CLOTH)
        println("Silk cloth frame-to-frame change: silence $clothQuiet, lively $clothLively")
        assertTrue(clothLively > 0.002f, "the cloth should move under music, changed $clothLively a frame")
        assertTrue(clothQuiet <= 0.05f * clothLively, "silence should hold the cloth still: $clothQuiet against $clothLively")
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
        val silk = Silk()
        RenderHarness.forEachFrameOf(silk, 64, 36, 90, VizPalette.Prism,
            source = { step -> InjectedFrames.toneFrame(step, lowBand = true) }) { _, _ -> }
        val frequencies = silk.strand.frequencies
        val sixth = SilkStrand.STEPS / 6
        val left = (0 until sixth).map { frequencies[it] }.average()
        val right = (SilkStrand.STEPS / 2 until SilkStrand.STEPS).map { frequencies[it] }.average()
        println("silk: a low tone reads $left in the strand's first sixth and $right in its last half")
        // The base frame keeps about 0.11 in the upper bands, read as its square root.
        assertTrue(left > 0.8f, "the bass sits at the left of the strand, read $left")
        assertTrue(right < 0.4f, "the treble half stays near the base frame, read $right")
    }

    @Test
    fun aLowToneRaisesTheLeftRowsToFullHeight() {
        fun front(lowBand: Boolean): Pair<Double, Double> {
            val silk = Silk()
            var low = 0.0
            var tone = 0.0
            RenderHarness.forEachFrameOf(silk, 160, 90, 120, VizPalette.Prism,
                source = { step -> InjectedFrames.toneFrame(step, lowBand) },
                beforeDraw = { silk.jumpTo(Silk.CLOTH) }) { _, step ->
                if (step != 100) return@forEachFrameOf
                low = (0..16).map { silk.rowHeight(0, it / 100f).toDouble() }.average()
                tone = if (lowBand) (50..100).map { silk.rowHeight(0, it / 100f).toDouble() }.average()
                    else (64..78).map { silk.rowHeight(0, it / 100f).toDouble() }.average()
            }
            return low to tone
        }
        val (lowLeft, lowRest) = front(lowBand = true)
        val (highLeft, highPlace) = front(lowBand = false)
        println("silk: a low tone lifts the front row to $lowLeft at the left and $lowRest over the right half; " +
            "a high tone to $highLeft at the left and $highPlace at its own place")
        assertTrue(lowLeft > 3.0 * lowRest, "a low tone raises the left of the front row: $lowLeft against $lowRest")
        assertTrue(highPlace > 2.0 * highLeft, "a high tone raises its own place instead: $highPlace against $highLeft")
    }

    @Test
    fun aRowBehindTheFrontRowIsTheFrontRowThatLongAgo() {
        val silk = Silk()
        val heights = FloatArray(4)
        RenderHarness.forEachFrameOf(silk, 160, 90, 260, VizPalette.Prism,
            source = { step -> InjectedFrames.toneFrame(step, lowBand = step < 230) },
            beforeDraw = { silk.jumpTo(Silk.CLOTH) }) { _, step ->
            if (step != 250) return@forEachFrameOf
            heights[0] = silk.rowHeight(0, 0.08f)
            heights[1] = silk.rowHeight(40, 0.08f)
            heights[2] = silk.rowHeight(0, 0.7f)
            heights[3] = silk.rowHeight(40, 0.7f)
        }
        println("silk: at the left the front row is ${heights[0]} and row 40 ${heights[1]}; " +
            "at 0.7 the front row is ${heights[2]} and row 40 ${heights[3]}")
        assertTrue(heights[0] < heights[1], "row 40 still holds the low tone of 2.5 seconds ago: ${heights[0]} against ${heights[1]}")
        assertTrue(heights[2] > heights[3], "the front row holds the high tone now: ${heights[2]} against ${heights[3]}")
    }

    @Test
    fun theStageHasAHorizonAndAShadow() {
        val silk = Silk()
        var horizon = 0f
        var below = 0f
        var paper = 0f
        RenderHarness.forEachFrame(silk, 320, 180, 60, VizPalette.Prism, RenderHarness.Song.Lively,
            beforeDraw = { silk.jumpTo(Silk.CLOTH) }) { bitmap, step ->
            if (step != 59) return@forEachFrame
            horizon = silk.horizonY()
            val pixels = pixels(bitmap)
            val bottom = silk.clothBottomY().toInt().coerceIn(0, 179)
            var total = 0f
            var count = 0
            for (y in bottom + 1..minOf(bottom + 20, 179)) for (x in 0 until 320) {
                total += luma(pixels[y * 320 + x]); count++
            }
            below = total / count.coerceAtLeast(1)
            var corner = 0f
            for (y in 0 until 10) for (x in 0 until 10) corner += luma(pixels[y * 320 + x])
            paper = corner / 100f
        }
        println("silk: horizon at $horizon of 180; under the cloth $below, the paper at the corner $paper")
        // Fluctus's camera puts the horizon about a seventh of the way down.
        assertTrue(horizon in 0.05f * 180f..0.4f * 180f, "the horizon sits in the upper part, at $horizon")
        assertTrue(below < paper - 0.03f, "the shadow under the cloth is darker than the paper: $below against $paper")
    }

    @Test
    fun theFormsCycleOnTheDrumLoopAndADropUnrollsTheTube() {
        val silk = Silk()
        RenderHarness.forEach(silk, 3600)
        println("silk: ${silk.forms.morphs} morphs in a minute of drums, now ${silk.forms.form}")
        assertTrue(silk.forms.morphs >= 1, "the drums change the form")
        assertTrue(silk.forms.form in Silk.FORMS)

        val dropped = Silk()
        var started = false
        var at67 = -1f
        var at256 = ""
        var full = -1
        RenderHarness.forEachFrameOf(dropped, 64, 36, 260, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) },
            beforeDraw = { if (!started) { dropped.jumpTo(Silk.TUBE); started = true } }) { _, step ->
            if (step == 67) at67 = dropped.morphValue
            if (full < 0 && step > 67 && dropped.morphValue >= 1f) full = step
            if (step == 256) at256 = dropped.forms.form
        }
        println("silk: the drop's morph at $at67 a frame after it, whole at step $full; the form at step 256 is $at256")
        assertTrue(at67 < 0.1f, "the drop starts a morph, at $at67")
        assertTrue(full in 68..257, "the cloth lies out within one cycle, at step $full")
        assertEquals("Cloth", at256)
    }

    @Test
    fun aMorphBlendsThePositionsWithoutAJump() {
        val silk = Silk()
        var started = false
        val point = FloatArray(3)
        val last = FloatArray(3)
        var biggest = 0f
        RenderHarness.forEachFrameOf(silk, 64, 36, 821, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) },
            beforeDraw = { if (!started) { silk.jumpTo(Silk.CLOTH); started = true } }) { _, step ->
            if (step == 59) silk.morphTo(Silk.VEIL)
            silk.vertexWorld(25, 100, point)
            if (step > 0) biggest = maxOf(biggest, kotlin.math.sqrt((0..2).sumOf { ((point[it] - last[it]) * (point[it] - last[it])).toDouble() }).toFloat())
            point.copyInto(last)
        }
        println("silk: the morph moved a point at most $biggest units a frame, ending at ${point.toList()} as ${silk.forms.form}")
        assertTrue(biggest < 2f, "a morph blends without a jump, moved $biggest")
        assertEquals("Veil", silk.forms.form)
        assertEquals(1f, silk.morphValue)
    }

    @Test
    fun theTwistFollowsTheEnergyAndFlipsOnAMorph() {
        val calm = Silk()
        RenderHarness.forEach(calm, 200, RenderHarness.Song.Calm)
        val lively = Silk()
        RenderHarness.forEach(lively, 200, RenderHarness.Song.Lively)
        println("silk: the twist reached ${calm.twistValue} on the calm pad and ${lively.twistValue} on the drums")
        assertTrue(abs(lively.twistValue) > abs(calm.twistValue), "louder music twists faster")
        val sign = lively.twistSignValue
        lively.morph()
        assertEquals(-sign, lively.twistSignValue, "a morph turns the twist the other way")
    }

    @Test
    fun aBirthWeavesASecondStrandIn() {
        fun inkAt150(born: Boolean): Pair<Float, Int> {
            val silk = Silk()
            var ink = 0f
            var count = 0
            RenderHarness.forEachFrameOf(silk, 160, 90, 151, VizPalette.Prism,
                source = { step -> InjectedFrames.frame(null, step) }) { bitmap, step ->
                if (born && step == 70) silk.birth()
                if (step == 71) count = silk.strandCount
                // The total colour, so faint ribbons count by how much ink they carry.
                if (step == 150) ink = pixels(bitmap).sumOf { chroma(it).toLong() }.toFloat()
            }
            return ink to count
        }
        val (still, _) = inkAt150(born = false)
        val (born, count) = inkAt150(born = true)
        println("silk: ink $born with a second strand, $still without; $count strands after the birth")
        assertEquals(2, count)
        assertTrue(born > still * 1.2f, "the second strand adds ink: $born against $still")
    }

    @Test
    fun theCameraOrbitsOnTheSlowCycleAndNeverCuts() {
        val silk = Silk()
        var started = false
        var last = 0f
        var biggest = 0f
        var lowest = 0f
        var highest = 0f
        RenderHarness.forEach(silk, 1800, beforeDraw = { if (!started) { silk.jumpTo(Silk.CLOTH); started = true } }) { step ->
            if (step > 0) biggest = maxOf(biggest, abs(silk.orbitValue - last))
            last = silk.orbitValue
            lowest = minOf(lowest, last)
            highest = maxOf(highest, last)
        }
        println("silk: the orbit ran from $lowest to $highest, at most $biggest a frame")
        assertTrue(lowest < -0.05f && highest > 0.05f, "the orbit swings both ways: $lowest to $highest")
        assertTrue(biggest <= 0.01f, "the orbit never cuts, moved $biggest in a frame")

        val quiet = Silk()
        var at60 = Float.NaN
        RenderHarness.forEach(quiet, 300, RenderHarness.Song.Silence) { step -> if (step == 60) at60 = quiet.orbitValue }
        assertEquals(at60, quiet.orbitValue, "a silence holds the camera")
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

    /** The mean luma change between consecutive frames, after the strand or the [form] settles. */
    private fun meanChange(song: RenderHarness.Song, form: Int = Silk.STRAND): Float {
        var previous: IntArray? = null
        var sum = 0f
        var count = 0
        val silk = Silk()
        RenderHarness.forEachFrame(silk, 160, 90, 150, VizPalette.Prism, song,
            beforeDraw = { silk.jumpTo(form) }) { bitmap, step ->
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

    /** Renders [frames] of [song] at 64 by 36 and hands each step to [onStep]. */
    private fun RenderHarness.forEach(
        silk: Silk, frames: Int, song: RenderHarness.Song = RenderHarness.Song.Lively,
        beforeDraw: (io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState) -> Unit = {},
        onStep: (Int) -> Unit = {},
    ) = forEachFrame(silk, 64, 36, frames, VizPalette.Prism, song, beforeDraw = beforeDraw) { _, step -> onStep(step) }

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

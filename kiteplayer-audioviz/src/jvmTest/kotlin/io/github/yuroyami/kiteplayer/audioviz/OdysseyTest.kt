package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Odyssey
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Odyssey: a ringed giant planet whose clouds and rings are the spectrum. */
class OdysseyTest {

    init { useSkiaGraphics() }

    @Test
    fun theLivelySongDrawsTheRingedPlanetWithoutBlowingOut() {
        var ink = 0f
        var blown = 0f
        RenderHarness.forEachFrame(Odyssey(), 320, 180, 150, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 90) return@forEachFrame
            val pixels = IntArray(320 * 180)
            bitmap.readPixels(pixels)
            ink = maxOf(ink, pixels.count { luma(it) > 0.08f } / pixels.size.toFloat())
            blown = maxOf(blown, pixels.count { luma(it) > 0.97f } / pixels.size.toFloat())
        }
        println("odyssey: ink $ink, blown $blown")
        assertTrue(ink > 0.2f, "the planet and its rings should fill much of the frame, had $ink")
        assertTrue(blown < 0.05f, "only the rim and the sun glow white, had $blown blown out")
    }

    @Test
    fun silenceHoldsThePlanetStillAndHalfLit() {
        var previous: IntArray? = null
        var worst = 0f
        var firstInk = 0f
        RenderHarness.forEachFrame(Odyssey(), 160, 90, 120, VizPalette.Prism, RenderHarness.Song.Silence) { bitmap, step ->
            val pixels = IntArray(160 * 90)
            bitmap.readPixels(pixels)
            if (step == 0) firstInk = pixels.count { luma(it) > 0.03f } / pixels.size.toFloat()
            if (step >= 30) previous?.let { old ->
                worst = maxOf(worst, old.indices.sumOf { abs(luma(old[it]) - luma(pixels[it])).toDouble() }.toFloat() / pixels.size)
            }
            previous = pixels
        }
        println("odyssey: silence changed at most $worst a frame, first frame ink $firstInk")
        assertTrue(worst < 0.0002f, "the planet hangs still in a silence, changed $worst")
        assertTrue(firstInk > 0.1f, "the first frame already shows the lit planet, had $firstInk")
    }

    @Test
    fun everyViewCutsAboutAThirdOfThePlanetOffAtTheFrameEdge() {
        for (aspect in listOf(16f / 9f, 20f / 9f, 4f / 3f, 9f / 16f, 9f / 20f)) for ((index, view) in Odyssey.VIEWS.withIndex()) {
            if (index == Odyssey.BREAKDOWN_VIEW) continue
            val cx = view[0] * aspect
            val cy = view[1]
            val radius = view[2] * minOf(1f, aspect)
            var inside = 0
            var all = 0
            for (i in 0 until 200) for (j in 0 until 200) {
                val x = cx - radius + 2f * radius * (i + 0.5f) / 200f
                val y = cy - radius + 2f * radius * (j + 0.5f) / 200f
                if (hypot(x - cx, y - cy) > radius) continue
                all++
                if (abs(x) <= aspect && abs(y) <= 1f) inside++
            }
            val share = inside.toFloat() / all
            println("odyssey: view $index at aspect $aspect shows $share of the disc")
            assertTrue(share in 0.5f..0.8f, "view $index at aspect $aspect should cut about a third off, shows $share")
        }
    }

    @Test
    fun aSingleToneBrightensOneRingletAndOneStripe() {
        val odyssey = Odyssey()
        RenderHarness.forEachFrameOf(odyssey, 64, 36, 90, VizPalette.Prism,
            source = { step ->
                val frame = InjectedFrames.frame(VizDriver.Bands, step.coerceAtLeast(InjectedFrames.STEP_AT))
                for (band in frame.bands.indices) frame.bands[band] = if (band == 20) 0.85f else 0.02f
                frame
            }) { _, _ -> }
        val ringlets = odyssey.ringletLevels()
        val stripes = odyssey.newestStripes()
        val brightRinglets = ringlets.count { it > 0.5f }
        val brightStripes = stripes.count { it > 0.5f }
        println("odyssey: a single tone lit $brightRinglets ringlets and $brightStripes stripes")
        assertTrue(brightRinglets in 1..2, "one ringlet lights, with its neighbour at most, had $brightRinglets")
        assertEquals(1, brightStripes, "one stripe lights")
    }

    @Test
    fun aSectionMovesToANewViewAndADropBringsTheSunUp() {
        val sectioned = Odyssey()
        var before = -1
        var after = -1
        RenderHarness.forEachFrameOf(sectioned, 64, 36, 90, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Section, step) }) { _, step ->
            if (step == InjectedFrames.STRUCTURE_STEP - 1) before = sectioned.view
            if (step == 89) after = sectioned.view
        }
        assertTrue(after != before, "a section moves to another view: $before then $after")

        val dropped = Odyssey()
        var atDrop = -1f
        var risen = -1f
        RenderHarness.forEachFrameOf(dropped, 64, 36, 140, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) }) { _, step ->
            if (step == InjectedFrames.STRUCTURE_STEP) atDrop = dropped.risen
            if (step == 139) risen = dropped.risen
        }
        println("odyssey: view $before then $after; the sun at $atDrop on the drop and $risen later")
        assertTrue(atDrop < 0.2f && risen >= 1f, "the sun rises over one beat after a drop: $atDrop then $risen")
    }

    private fun luma(pixel: Int): Float =
        (0.2126f * (pixel shr 16 and 0xFF) + 0.7152f * (pixel shr 8 and 0xFF) + 0.0722f * (pixel and 0xFF)) / 255f
}

package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Alchemy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Alchemy's core stays in the middle third, a snare strikes one arc, and a drop turns the lace gold and back. */
class AlchemyTest {

    init { useSkiaGraphics() }

    @Test
    fun theCoreStaysInTheMiddleThird() {
        val alchemy = Alchemy()
        var farthestX = 0f
        var farthestY = 0f
        RenderHarness.forEachFrame(alchemy, 160, 90, 900, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ ->
            farthestX = maxOf(farthestX, kotlin.math.abs(alchemy.centre.x - 0.5f))
            farthestY = maxOf(farthestY, kotlin.math.abs(alchemy.centre.y - 0.5f))
        }
        assertTrue(farthestX < 1f / 6f && farthestY < 1f / 6f,
            "the core must stay in the middle third, reached $farthestX across and $farthestY down from the middle")
    }

    @Test
    fun aSnareStrikesAtMostTwoArcsASecond() {
        val alchemy = Alchemy()
        val struck = ArrayList<Int>()
        var last = 0
        RenderHarness.forEachFrame(alchemy, 160, 90, 600, VizPalette.Prism, RenderHarness.Song.Lively) { _, step ->
            if (alchemy.arcsStruck > last) struck += step
            last = alchemy.arcsStruck
            assertTrue(alchemy.arcFrames <= 3, "an arc lasts three frames, had ${alchemy.arcFrames} left")
        }
        println("alchemy: ${struck.size} arcs in ten seconds, at steps $struck")
        assertTrue(struck.isNotEmpty(), "the drum loop's snares should strike at least one arc")
        for (index in struck.indices) {
            val inOneSecond = struck.count { it in struck[index] until struck[index] + 60 }
            assertTrue(inOneSecond <= 2, "no second may hold more than two arcs, found $inOneSecond from step ${struck[index]}")
        }
    }

    @Test
    fun aDropTurnsTheLaceGoldAndCoolsItBackToBlue() {
        val alchemy = Alchemy()
        var goldBefore = 0
        var goldAfterABeat = 0
        var spreadAfterABeat = 0f
        var spreadAtTheEnd = 1f
        var cooledAt = -1
        var peaked = false
        // The injected drop lands at the structure step. Two seconds later is past one beat at any tempo.
        val afterABeat = InjectedFrames.STRUCTURE_STEP + 120
        RenderHarness.forEachFrameOf(alchemy, 160, 90, 2_400, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) }) { bitmap, step ->
            val pixels = IntArray(160 * 90)
            bitmap.readPixels(pixels)
            if (step == InjectedFrames.STRUCTURE_STEP - 2) goldBefore = goldPixels(pixels)
            if (step == afterABeat) {
                goldAfterABeat = goldPixels(pixels)
                spreadAfterABeat = alchemy.goldSpread
            }
            if (alchemy.goldSpread >= Alchemy.GOLD_REACH) peaked = true
            if (peaked && cooledAt < 0 && alchemy.goldSpread == 0f) cooledAt = step
            if (step == 2_399) spreadAtTheEnd = alchemy.goldSpread
        }
        println("alchemy: gold pixels $goldBefore before the drop, $goldAfterABeat a beat after it; blue again at step $cooledAt")
        assertEquals(0, goldBefore, "the lace is blue before the drop")
        assertEquals(Alchemy.GOLD_REACH, spreadAfterABeat, 1e-3f, "a beat after the drop the gold has reached past the corona")
        assertTrue(goldAfterABeat > 160 * 90 / 50, "a beat after the drop the lace shows gold, had $goldAfterABeat pixels")
        assertEquals(0f, spreadAtTheEnd, "after the hold and the cooling the lace is blue again")
    }

    @Test
    fun outsideADropTheLaceStaysBlueWithAnyPalette() {
        for (palette in listOf(VizPalette.Fire, VizPalette.Sunset, VizPalette.Acid)) {
            var warm = 0
            RenderHarness.forEachFrame(Alchemy(), 160, 90, 240, palette, RenderHarness.Song.Lively) { bitmap, step ->
                if (step < 120) return@forEachFrame
                val pixels = IntArray(160 * 90)
                bitmap.readPixels(pixels)
                warm = maxOf(warm, goldPixels(pixels))
            }
            assertEquals(0, warm, "${palette.name} must not turn the lace or its sparks warm")
        }
    }

    /** Pixels whose red leads their blue by a clear margin: gold, amber, orange. */
    private fun goldPixels(pixels: IntArray): Int = pixels.count {
        val r = it shr 16 and 0xFF
        val b = it and 0xFF
        r > 120 && r > b + 60
    }
}

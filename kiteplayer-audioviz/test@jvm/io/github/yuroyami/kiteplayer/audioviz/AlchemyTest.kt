package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Alchemy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Alchemy keeps the signal in its field, changes drawer and flow with the music, and turns gold on a drop. */
class AlchemyTest {

    init { useSkiaGraphics() }

    @Test
    fun itPublishesItsFormAndChangesItOnTheDrumLoop() {
        val alchemy = Alchemy()
        val seen = LinkedHashSet<String>()
        RenderHarness.forEachFrame(alchemy, 96, 54, 7_200, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ ->
            seen += alchemy.forms.form
        }
        println("alchemy: forms seen $seen, ${alchemy.forms.morphs} morphs, ${alchemy.forms.births} births")
        assertTrue(alchemy.forms.form.contains(" + "), "the form names its drawer and flow, had ${alchemy.forms.form}")
        assertTrue(seen.size >= 3, "two minutes of drums must show at least three forms, showed $seen")
        assertTrue(alchemy.forms.births >= 2, "two minutes of drums must birth at least twice")
    }

    @Test
    fun theFieldHoldsTheWaveformAMomentLater() {
        val playing = Alchemy()
        val silent = Alchemy()
        var inkAfterSilence = 1f
        var inkWhilePlaying = 0f
        RenderHarness.forEachFrame(playing, 96, 54, 600, VizPalette.Prism, RenderHarness.Song.Lively) { _, step ->
            if (step == 599) inkWhilePlaying = playing.fieldInkShare()
        }
        RenderHarness.forEachFrame(silent, 96, 54, 600, VizPalette.Prism, RenderHarness.Song.Silence) { _, step ->
            if (step == 599) inkAfterSilence = silent.fieldInkShare()
        }
        println("alchemy: ink share $inkWhilePlaying playing, $inkAfterSilence silent")
        assertTrue(inkWhilePlaying > 0.05f, "the field holds ink while music plays")
        assertTrue(inkAfterSilence < inkWhilePlaying, "silence writes nothing new")
    }

    @Test
    fun aKickPushesTheFieldOutward() {
        val plain = Alchemy()
        val kicked = Alchemy()
        var plainInk = 0f
        var kickedInk = 0f
        // The ring itself is drawn anew every frame in both runs, so the push shows in the ink it carries
        // outward: the ink's mean distance from the middle grows.
        RenderHarness.forEachFrameOf(plain, 96, 54, 70, VizPalette.Prism, source = { InjectedFrames.frame(null, it) }) { _, step ->
            if (step == 69) plainInk = plain.fieldMeanRadius()
        }
        RenderHarness.forEachFrameOf(kicked, 96, 54, 70, VizPalette.Prism, source = { InjectedFrames.frame(VizDriver.LowHit, it) }) { _, step ->
            if (step == 69) kickedInk = kicked.fieldMeanRadius()
        }
        println("alchemy: mean ink radius $plainInk plain, $kickedInk after a kick")
        assertTrue(kickedInk > plainInk, "the kick on step 66 must have pushed ink outward by step 69: $plainInk against $kickedInk")
    }

    @Test
    fun aDropTurnsTheInkGoldAndCoolsItBack() {
        val alchemy = Alchemy()
        var goldBefore = 0
        var goldAfterABeat = 0
        var spreadAtTheEnd = 1f
        val afterABeat = InjectedFrames.STRUCTURE_STEP + 120
        RenderHarness.forEachFrameOf(alchemy, 160, 90, 2_400, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) }) { bitmap, step ->
            val pixels = IntArray(160 * 90)
            bitmap.readPixels(pixels)
            if (step == InjectedFrames.STRUCTURE_STEP - 2) goldBefore = goldPixels(pixels)
            if (step == afterABeat) goldAfterABeat = goldPixels(pixels)
            if (step == 2_399) spreadAtTheEnd = alchemy.goldSpread
        }
        println("alchemy: gold pixels $goldBefore before the drop, $goldAfterABeat a beat after it")
        assertEquals(0, goldBefore, "the ink is blue before the drop")
        assertTrue(goldAfterABeat > 160 * 90 / 50, "a beat after the drop the ink shows gold, had $goldAfterABeat pixels")
        assertEquals(0f, spreadAtTheEnd, "after the hold and the cooling the ink is blue again")
    }

    @Test
    fun aSnareStrikesAtMostTwoArcsASecond() {
        val alchemy = Alchemy()
        val struck = ArrayList<Int>()
        var last = 0
        RenderHarness.forEachFrame(alchemy, 160, 90, 600, VizPalette.Prism, RenderHarness.Song.Lively) { _, step ->
            if (alchemy.arcsStruck > last) struck += step
            last = alchemy.arcsStruck
        }
        assertTrue(struck.isNotEmpty(), "the drum loop's snares should strike at least one arc")
        for (index in struck.indices) {
            val inOneSecond = struck.count { it in struck[index] until struck[index] + 60 }
            assertTrue(inOneSecond <= 2, "no second may hold more than two arcs, found $inOneSecond from step ${struck[index]}")
        }
    }

    /** Pixels whose red leads their blue by a clear margin: gold, amber, orange. */
    private fun goldPixels(pixels: IntArray): Int = pixels.count {
        val r = it shr 16 and 0xFF
        val b = it and 0xFF
        r > 120 && r > b + 60
    }
}

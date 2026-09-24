package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Marble
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Marble: marbled ink on black water, dropped by the drums, combed by vortices, laced by a reaction. */
class MarbleTest {

    init { useSkiaGraphics() }

    @Test
    fun theLivelySongDrawsMarbledInkWithoutBlowingOut() {
        var ink = 0f
        var blown = 0f
        RenderHarness.forEachFrame(Marble(), 320, 180, 150, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 90) return@forEachFrame
            val pixels = IntArray(320 * 180)
            bitmap.readPixels(pixels)
            ink = maxOf(ink, pixels.count { luma(it) > 0.1f } / pixels.size.toFloat())
            blown = maxOf(blown, pixels.count { luma(it) > 0.97f } / pixels.size.toFloat())
        }
        println("marble: ink $ink, blown $blown")
        assertTrue(ink > 0.2f, "the tray should be marbled with ink, had $ink")
        assertTrue(blown < 0.05f, "only rims and gloss are white, had $blown blown out")
    }

    @Test
    fun silenceChangesNothingAndShowsTheMarbledTray() {
        val marble = Marble()
        var previous: IntArray? = null
        var worst = 0f
        var firstInk = 0f
        RenderHarness.forEachFrame(marble, 160, 90, 120, VizPalette.Prism, RenderHarness.Song.Silence) { bitmap, step ->
            val pixels = IntArray(160 * 90)
            bitmap.readPixels(pixels)
            if (step == 0) firstInk = pixels.count { luma(it) > 0.05f } / pixels.size.toFloat()
            if (step >= 30) previous?.let { old ->
                worst = maxOf(worst, old.indices.sumOf { abs(luma(old[it]) - luma(pixels[it])).toDouble() }.toFloat() / pixels.size)
            }
            previous = pixels
        }
        println("marble: silence changed at most $worst a frame, first frame ink $firstInk")
        assertTrue(worst < 0.0002f, "nothing flows and nothing grows in a silence, changed $worst")
        assertTrue(firstInk > 0.2f, "the first frame already shows marbled ink, had $firstInk")
        assertEquals(0L, marble.grown, "no lace grows in a silence")
    }

    @Test
    fun aKickDropsOrangeInkAndPushesTheOldInkOutward() {
        val marble = Marble()
        var before = 0f
        var after = 0f
        var drops = 0
        RenderHarness.forEachFrameOf(marble, 64, 36, 70, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.LowHit, step) }) { _, step ->
            if (step == InjectedFrames.HIT_STEPS.first() - 1) before = marble.inkedShare()
            if (step == InjectedFrames.HIT_STEPS.first()) {
                after = marble.inkedShare()
                drops = marble.drops
            }
        }
        val orange = marble.inkAt(marble.lastDropX, marble.lastDropY, Marble.ORANGE)
        println("marble: a kick dropped $drops, orange $orange at the drop, inked share $before to $after")
        assertEquals(1, drops, "one kick drops one drop")
        assertTrue(orange > 0.9f, "the drop is orange ink, read $orange")
        // The old ink is pushed outward rather than covered, so the tray holds more ink than before.
        assertTrue(after > before, "the drop adds ink and pushes the old ink out: $before to $after")
    }

    @Test
    fun laceGrowsInsideTheInkAsTheMusicPlays() {
        val marble = Marble()
        RenderHarness.forEachFrame(marble, 64, 36, 420, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ -> }
        val share = marble.laceShare()
        println("marble: lace covers $share of the ink after seven seconds, ${marble.grown} reaction steps")
        assertTrue(share in 0.05f..0.8f, "the ink carries lace, neither flat nor filled, had $share")
    }

    @Test
    fun aBreakdownStopsTheVorticesWhileTheLaceKeepsGrowing() {
        val marble = Marble()
        var stirBefore = 0f
        var stirAfter = 0f
        var grownAtBreakdown = 0L
        RenderHarness.forEachFrameOf(marble, 64, 36, 300, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Breakdown, step) }) { _, step ->
            if (step == InjectedFrames.STRUCTURE_STEP - 1) stirBefore = marble.stirring
            if (step == InjectedFrames.STRUCTURE_STEP) grownAtBreakdown = marble.grown
            if (step == 299) stirAfter = marble.stirring
        }
        println("marble: stirring $stirBefore before the breakdown and $stirAfter after, lace steps ${marble.grown - grownAtBreakdown} since")
        assertTrue(stirBefore > 1f && stirAfter < 0.05f * stirBefore, "the vortices stop: $stirBefore to $stirAfter")
        assertTrue(marble.grown > grownAtBreakdown, "the lace keeps growing in a breakdown")
    }

    @Test
    fun aDropLandsGoldInTheMiddle() {
        val marble = Marble()
        var gold = 0f
        RenderHarness.forEachFrameOf(marble, 64, 36, 70, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) }) { _, step ->
            if (step == InjectedFrames.STRUCTURE_STEP) gold = marble.inkAt(0.5f, 0.5f, Marble.GOLD)
        }
        println("marble: gold $gold in the middle on the drop")
        assertTrue(gold > 0.9f, "the drop lands gold ink in the middle, read $gold")
    }

    private fun luma(pixel: Int): Float =
        (0.2126f * (pixel shr 16 and 0xFF) + 0.7152f * (pixel shr 8 and 0xFF) + 0.0722f * (pixel and 0xFF)) / 255f
}

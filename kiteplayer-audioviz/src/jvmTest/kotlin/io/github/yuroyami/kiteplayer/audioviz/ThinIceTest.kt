package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.ThinIce
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/** Thin Ice: ripples of two wells under black ice, white where they cross, and the ice breaking on a drop. */
class ThinIceTest {

    init { useSkiaGraphics() }

    @Test
    fun theLivelySongDrawsTwoSetsOfRipplesWithoutBlowingOut() {
        var ink = 0f
        var blown = 0f
        RenderHarness.forEachFrame(ThinIce(), 320, 180, 120, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 60) return@forEachFrame
            val pixels = IntArray(320 * 180)
            bitmap.readPixels(pixels)
            ink = maxOf(ink, pixels.count { luma(it) > 0.1f } / pixels.size.toFloat())
            blown = maxOf(blown, pixels.count { luma(it) > 0.97f } / pixels.size.toFloat())
        }
        println("thin ice: ink $ink, blown $blown")
        assertTrue(ink > 0.004f, "the rings should put ink down, had $ink")
        assertTrue(blown < 0.05f, "only the crossings are white, had $blown blown out")
    }

    @Test
    fun silenceHoldsTheRingsStillAndLit() {
        var previous: IntArray? = null
        var change = 0f
        var counted = 0
        var firstInk = 0f
        val ice = ThinIce()
        val before = ice.radiiOf(0)
        RenderHarness.forEachFrame(ice, 160, 90, 180, VizPalette.Prism, RenderHarness.Song.Silence) { bitmap, step ->
            val pixels = IntArray(160 * 90)
            bitmap.readPixels(pixels)
            if (step == 0) firstInk = pixels.count { luma(it) > 0.05f } / pixels.size.toFloat()
            if (step >= 60) {
                previous?.let { old ->
                    change += old.indices.sumOf { abs(luma(old[it]) - luma(pixels[it])).toDouble() }.toFloat() / pixels.size
                    counted++
                }
            }
            previous = pixels
        }
        val mean = change / counted.coerceAtLeast(1)
        println("thin ice: silence moved $mean a frame, first frame ink $firstInk")
        assertTrue(mean < 0.0005f, "the rings hold still in silence, changed $mean")
        assertTrue(firstInk > 0.01f, "the first silent frame already shows the rings, had $firstInk")
        val after = ice.radiiOf(0)
        assertTrue(after.size == before.size && after.indices.all { abs(after[it] - before[it]) < 1e-5f },
            "no ring spreads in a silence: $before became $after")
    }

    @Test
    fun ringsBornABeatApartSitTheSameDistanceApart() {
        val ice = ThinIce()
        var radii = emptyList<Float>()
        RenderHarness.forEachFrameOf(ice, 64, 36, 150, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.LowHit, step) }) { _, step ->
            if (step == 149) radii = ice.radiiOf(0)
        }
        // The three injected kicks are half a second apart, so their rings are the three smallest.
        val newest = radii.take(3)
        println("thin ice: newest rings of well A at $newest")
        assertTrue(newest.size == 3, "three kicks draw three rings, had $radii")
        val first = newest[1] - newest[0]
        val second = newest[2] - newest[1]
        assertTrue(first > 0.01f && abs(first - second) < 0.02f * first + 1e-4f, "even rings: gaps $first and $second")
    }

    @Test
    fun theCrossingPointsAreWhite() {
        val ice = ThinIce()
        val width = 640
        val height = 360
        var pixels = IntArray(0)
        var points = emptyList<Pair<Float, Float>>()
        RenderHarness.forEachFrame(ice, width, height, 90, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step == 89) {
                pixels = IntArray(width * height)
                bitmap.readPixels(pixels)
                points = ice.crossingPoints()
            }
        }
        val unit = min(width, height).toFloat()
        var checked = 0
        var white = 0
        for ((x, y) in points) {
            val px = (x * unit).toInt()
            val py = (y * unit).toInt()
            if (px !in 2 until width - 2 || py !in 2 until height - 2) continue
            checked++
            var best = 0
            for (oy in -1..1) for (ox in -1..1) {
                val pixel = pixels[(py + oy) * width + px + ox]
                val least = minOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
                best = maxOf(best, least)
            }
            if (best > 170) white++
        }
        println("thin ice: $white of $checked crossing points are white")
        assertTrue(checked >= 4, "the two wells' rings should cross on screen, found $checked crossings")
        assertTrue(white >= checked * 0.8f, "crossings should be white, $white of $checked were")
    }

    @Test
    fun accentedKicksAddAtMostSixCracksASection() {
        val ice = ThinIce()
        var most = 0
        var seen = 0
        RenderHarness.forEachFrame(ice, 64, 36, 900, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ ->
            most = maxOf(most, ice.kickCracksThisSection)
            seen = maxOf(seen, ice.cracks)
        }
        println("thin ice: at most $most kick cracks in a section, $seen cracks at once")
        assertTrue(most in 1..6, "accented kicks crack the ice, at most six times a section, had $most")
    }

    @Test
    fun cracksCreepOutOfTheWellsInTheLastBeatBeforeADrop() {
        val ice = ThinIce()
        var step = 0
        val dropAt = 150
        val counts = IntArray(dropAt)
        RenderHarness.forEachFrameOf(ice, 64, 36, dropAt, VizPalette.Prism,
            source = { at -> step = at; InjectedFrames.frame(null, at) }, future = { dropAhead(dropAt) { step } }) { _, at ->
            counts[at] = ice.cracks
        }
        // With no pulse the free cycle here runs about three seconds, so its beat is about 47 steps.
        println("thin ice: cracks before the drop ${counts.slice(0 until dropAt step 10)}")
        assertTrue(counts[dropAt - 60] == 0, "no crack before the last beat, had ${counts[dropAt - 60]}")
        assertTrue(counts[dropAt - 1] > 0, "cracks creep out of the wells before the drop")
    }

    @Test
    fun aBreakdownHealsTheCracks() {
        val ice = ThinIce()
        var step = 0
        // A drop that the audio ahead promised and that never came leaves its creeping cracks behind.
        val dropAt = 40
        val counts = IntArray(600)
        RenderHarness.forEachFrameOf(ice, 64, 36, 600, VizPalette.Prism,
            source = { at -> step = at; InjectedFrames.frame(VizDriver.Breakdown, at) }, future = { dropAhead(dropAt) { step } }) { _, at ->
            counts[at] = ice.cracks
        }
        println("thin ice: cracks ${counts.slice(0 until 600 step 50)}")
        assertTrue(counts[InjectedFrames.STRUCTURE_STEP] > 0, "the cracks stay until the breakdown")
        assertTrue(counts[599] == 0, "a breakdown heals every crack, ${counts[599]} left")
    }

    /** Audio ahead that holds a drop at step [dropAt] while the render is before it. */
    private fun dropAhead(dropAt: Int, now: () -> Int): VizFuture = object : VizFuture {
        override fun at(secondsAhead: Float): SpectrumFrame? = null
        override val nextOnsetSeconds: Float = -1f
        override fun nextEvent(kind: AudioEventKind): UpcomingAudioEvent? {
            val step = now()
            if (kind != AudioEventKind.Drop || step >= dropAt) return null
            val pts = dropAt * 1_000_000L / 60L
            val event = AudioEvent(Generation.Initial, 0L, 1L,
                AudioDetection(AudioEventKind.Drop, pts, pts, 0.8f, 0.9f, 0.5f), AudioEventSource.LiveStructure)
            return UpcomingAudioEvent(event, (dropAt - step) / 60f)
        }
    }

    @Test
    fun aDropBreaksTheIceAndThePiecesAreGoneWithinABar() {
        val ice = ThinIce()
        val sinking = IntArray(600)
        RenderHarness.forEachFrameOf(ice, 64, 36, 600, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) }) { _, step ->
            sinking[step] = ice.sinking
        }
        val landed = InjectedFrames.STRUCTURE_STEP
        val broke = (landed until 600).firstOrNull { sinking[it] > 0 } ?: -1
        val gone = (broke until 600).firstOrNull { sinking[it] == 0 } ?: -1
        println("thin ice: broke at $broke into ${sinking.getOrElse(broke) { 0 }} pieces, gone at $gone")
        assertTrue(broke in landed..landed + 2, "the ice breaks on the drop, broke at $broke")
        assertTrue(sinking[broke] >= 9, "the break cuts many pieces, had ${sinking[broke]}")
        // The free cycle here runs about three seconds, and the pieces sink within one bar of it.
        assertTrue(gone > broke && gone - broke < 4 * 60 + 30, "the pieces are gone within a bar, took ${gone - broke} steps")
    }

    private fun luma(pixel: Int): Float =
        (0.2126f * (pixel shr 16 and 0xFF) + 0.7152f * (pixel shr 8 and 0xFF) + 0.0722f * (pixel and 0xFF)) / 255f
}

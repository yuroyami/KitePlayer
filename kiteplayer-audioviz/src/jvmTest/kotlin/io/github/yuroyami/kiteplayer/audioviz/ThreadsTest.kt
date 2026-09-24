package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.drawVisualizationFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Threads
import io.github.yuroyami.kiteplayer.audioviz.viz.restart
import io.github.yuroyami.kiteplayer.audioviz.viz.retentionOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The port of Vissonance's Silk: it draws, it stands still in silence, and it keeps the page's gate, climb and fade. */
class ThreadsTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsSixtyFramesOfTheLivelySongWithInkAndNoBlowOut() {
        val threads = Threads()
        assertEquals(PostSpec.Off, threads.post)
        var ink = 0f
        var blown = 0f
        var groundOff = 0
        RenderHarness.forEachFrame(threads, 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val pixels = pixels(bitmap)
            ink = maxOf(ink, pixels.count { distance(it, GROUND) > 18 }.toFloat() / pixels.size)
            blown = maxOf(blown, pixels.count { red(it) > 253 && green(it) > 253 && blue(it) > 253 }.toFloat() / pixels.size)
            // A hexagon reaches at most 32.3 of the view's 35 units from the centre line, so the top row is ground.
            for (x in 0 until 320) if (distance(pixels[x], GROUND) > 3) groundOff++
        }
        assertTrue(ink > 0.004f, "the threads should put ink down, had ${ink * 100} percent")
        assertTrue(blown < 0.01f, "the fade should settle on #fdfdfd, not climb to white, had ${blown * 100} percent at white")
        assertEquals(0, groundOff, "the top row should stay the page's #fdfdfd")
        assertTrue(threads.loudness > 1f, "the drum loop should be loud enough to climb, loudness ${threads.loudness}")
    }

    @Test
    fun standsStillInSilenceAndMovesUnderMusic() {
        val silence = meanChange(RenderHarness.Song.Silence)
        val music = meanChange(RenderHarness.Song.Lively)
        println("Threads frame-to-frame change: silence $silence, music $music")
        assertTrue(silence < 0.0005f, "silence should hold the page still, changed $silence")
        assertTrue(music > 0.002f, "music should move the threads, changed $music")
        assertTrue(silence <= 0.2f * music, "silence $silence against music $music")
    }

    @Test
    fun theGateKeepsQuietBandsOffThePage() {
        val threads = Threads()
        // GetVisualBins(dataArray, 512, 6, 1300): the first bars take one bin each from bin 6.
        assertEquals(512, threads.bins.count)
        assertEquals(listOf(6, 7, 8), threads.bins.firstBins.take(3))
        assertTrue(threads.bins.firstBins.last() < 1300)
        // The gate's power runs from 5 at the bass bar to about 3 at the treble bar.
        assertEquals(5.0, threads.bins.exponent(0), 1e-12)
        assertEquals(3.0039, threads.bins.exponent(511), 1e-4)

        // Every bin at half of the byte range: the bass hexagons stay under a tenth of a unit, about a pixel at 1080p.
        threads.listen(IntArray(2048) { 128 })
        assertEquals(8.12f, threads.visual[0], 0.01f)
        assertTrue(Threads.RADIUS * threads.scale[0] < 0.08f, "a half-level bass band is a dot, radius ${Threads.RADIUS * threads.scale[0]}")
        val largest = threads.scale.max() * Threads.RADIUS
        assertTrue(largest < 0.35f, "no half-level band grows past a third of a unit, had $largest")
        // Without the gate the same bytes would draw hexagons over a unit across, which fills the page with lint.
        assertTrue(Threads.RADIUS * Threads.scaleOf(128f) > 1.1f)

        // Every bin at full scale: the bass hexagon reaches 2.32 units, in hsl(20.5, 90%, 60%).
        threads.listen(IntArray(2048) { 255 })
        assertEquals(255f, threads.visual[0])
        assertEquals(2.3197f, Threads.RADIUS * threads.scale[0], 1e-3f)
        assertEquals(0xFFF57C3D.toInt(), threads.packed(0), "the loudest band is orange-red, was ${hex(threads.packed(0))}")
    }

    @Test
    fun aThreadClimbsThirtyUnitsThenSnapsBackToTheCentreLine() {
        val threads = Threads()
        threads.restart()
        threads.listen(IntArray(2048) { 255 })
        assertEquals(127.5f, threads.loudness)
        repeat(129) { threads.climbBy(1f) }
        assertEquals(129f * threads.scale[0], threads.climb[0], 1e-3f)
        assertTrue(threads.climb[0] in 29.8f..30f)
        threads.climbBy(1f)
        assertEquals(0f, threads.climb[0], "past 30 units the hexagon snaps back to the centre line")

        // The smoothed loudness halves each silent read; every hexagon is sent back once it is 1 or less.
        repeat(10) { threads.climbBy(1f) }
        threads.listen(IntArray(2048), count = 6)
        threads.climbBy(0f)
        assertTrue(threads.loudness > 1f && threads.climb[0] > 2f, "loudness ${threads.loudness} still holds the climb")
        threads.listen(IntArray(2048), count = 1)
        threads.climbBy(0f)
        assertTrue(threads.loudness <= 1f)
        assertTrue(threads.climb.all { it == 0f }, "silence puts every hexagon on the centre line")
    }

    @Test
    fun theTrailFadesTwentyPercentTowardTheGroundEverySixtiethOfASecond() {
        val threads = Threads()
        assertEquals(0.8f, threads.trail)
        assertEquals(0.05177f, threads.trailHalfLifeAt(0.5f), 1e-4f)
        assertEquals(0.8f, retentionOf(threads.trail, 1f / 60f), 1e-5f)
        // Black under a silent frame: the page's plane leaves 80 percent of black and 20 percent of white.
        for ((rate, frames) in listOf(60 to 1, 120 to 2)) {
            threads.restart()
            val grey = fadeBlack(threads, rate, frames)
            assertTrue(abs(grey - 51) <= 2, "one sixtieth of a second at $rate Hz should leave 51 of 255, left $grey")
        }
    }

    @Test
    fun theAnalyserIsReadSixtyTimesASecondAtAnyScreenRate() {
        for (rate in listOf(30, 60, 90, 120, 144)) {
            val threads = Threads()
            threads.restart()
            val player = RenderHarness.player(RenderHarness.Song.Lively, 4f)
            val bitmap = ImageBitmap(64, 40)
            val scope = CanvasDrawScope()
            val delta = 1f / rate
            var elapsed = 0f
            repeat(2 * rate) {
                elapsed += delta
                val state = VizRenderState(player.next(delta), elapsed, delta, VizPalette.Prism, elapsed)
                scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(64f, 40f)) {
                    with(threads) { draw(state) }
                }
            }
            assertTrue(threads.reads in 119L..121L, "$rate Hz gave ${threads.reads} reads in two seconds")
        }
    }

    @Test
    fun theFourQuartersMirrorEachOtherAtThePagesScale() {
        // A 1080-pixel-high view is 70.02 units high at the hexagons' depth: 15.42 pixels a unit.
        assertEquals(15.424f, Threads.pixelsPerUnit(1080f), 1e-3f)
        assertEquals(3f, Threads.BAR_X[0])
        assertEquals(64.32f, Threads.BAR_X[511], 1e-3f)

        val width = 320
        val height = 200
        var last: IntArray? = null
        RenderHarness.forEachFrame(Threads(), width, height, 90, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step == 89) last = pixels(bitmap)
        }
        val pixels = checkNotNull(last)
        val quarters = IntArray(4)
        for (y in 0 until height) for (x in 0 until width) {
            if (distance(pixels[y * width + x], GROUND) <= 18) continue
            quarters[(if (x < width / 2) 0 else 1) + (if (y < height / 2) 0 else 2)]++
        }
        val mean = quarters.average()
        assertTrue(mean > 50, "the threads should cover the quarters, had ${quarters.toList()}")
        for (count in quarters) assertTrue(abs(count - mean) <= 0.1 * mean, "the quarters should mirror each other: ${quarters.toList()}")
    }

    /** Lays a silent frame over black for [frames] frames at [rate] Hz and answers the red channel left in the middle. */
    private fun fadeBlack(threads: Threads, rate: Int, frames: Int): Int {
        val size = Size(32f, 20f)
        val scope = CanvasDrawScope()
        var previous = ImageBitmap(32, 20)
        scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(previous), size) { drawRect(Color.Black) }
        val player = RenderHarness.player(RenderHarness.Song.Silence, 1f)
        val delta = 1f / rate
        var elapsed = 0f
        repeat(frames) {
            elapsed += delta
            val state = VizRenderState(player.next(delta), elapsed, delta, VizPalette.Prism, 0f)
            val target = ImageBitmap(32, 20)
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(target), size) {
                drawVisualizationFrame(threads, state, previous)
            }
            previous = target
        }
        val middle = pixels(previous)[10 * 32 + 16]
        assertEquals(0xFF, middle ushr 24, "the echo layer should be opaque after the fade")
        return red(middle)
    }

    /** The mean luma change between consecutive frames, after the analyser and the trails settle. */
    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var sum = 0f
        var count = 0
        RenderHarness.forEachFrame(Threads(), 160, 100, 150, VizPalette.Prism, song) { bitmap, step ->
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

    private fun red(pixel: Int): Int = pixel shr 16 and 0xFF
    private fun green(pixel: Int): Int = pixel shr 8 and 0xFF
    private fun blue(pixel: Int): Int = pixel and 0xFF

    private fun distance(left: Int, right: Int): Int =
        abs(red(left) - red(right)) + abs(green(left) - green(right)) + abs(blue(left) - blue(right))

    private fun luma(pixel: Int): Float =
        0.2126f * red(pixel) / 255f + 0.7152f * green(pixel) / 255f + 0.0722f * blue(pixel) / 255f

    private fun hex(pixel: Int): String = (pixel.toLong() and 0xFFFFFFFFL).toString(16)

    private companion object {
        /** The page's clear colour, `0xfdfdfd`. */
        val GROUND = 0xFFFDFDFD.toInt()
    }
}

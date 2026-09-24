package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Honeycomb
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The port of Soundcloud Visualizer by Michael Bromley: it draws, it stands still in silence, and it keeps the page's volume and its pole. */
class HoneycombTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsSixtyFramesOfTheLivelySongWithInkAndNoBlowOut() {
        var peakInk = 0f
        var peakBlown = 0f
        RenderHarness.forEachFrame(Honeycomb(), 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val image = with(RenderHarness) { bitmap.toBufferedImage() }
            peakInk = maxOf(peakInk, inkFraction(image))
            peakBlown = maxOf(peakBlown, blownFraction(image))
        }
        assertTrue(peakInk > 0.004f, "the honeycomb should put ink down, had $peakInk")
        assertTrue(peakBlown <= 0.3f, "the picture should not saturate to white, had $peakBlown")
    }

    @Test
    fun standsStillInSilenceAndMovesUnderMusic() {
        val silence = meanChange(RenderHarness.Song.Silence)
        val music = meanChange(RenderHarness.Song.Lively)
        assertTrue(silence < 0.0005f, "silence should hold the picture still, changed $silence")
        assertTrue(music > 0.002f, "music should move the picture, changed $music")
        assertTrue(silence <= 0.2f * music, "silence $silence against music $music")
    }

    @Test
    fun theVolumeIsTheSumOfBinsZeroToSeventyNineOfTheBrowsersRow() {
        val honeycomb = Honeycomb()
        val analyser = honeycomb.analyser
        assertEquals(256, analyser.fftSize)
        assertEquals(128, analyser.binCount)
        assertEquals(0.8f, analyser.smoothing)
        assertEquals(-100f, analyser.minDecibels)
        assertEquals(-30f, analyser.maxDecibels)
        run(honeycomb, RenderHarness.Song.Lively, rate = 60, seconds = 2f)
        assertEquals(analyser.frequencyBytes.take(80).sum(), honeycomb.volume)
        assertTrue(honeycomb.volume > 4_000, "a loud drum loop gives a large volume, had ${honeycomb.volume}")
    }

    @Test
    fun theReadsRunEveryTwentyMillisecondsOfHeardTimeAtAnyScreenRate() {
        for (rate in listOf(30, 60, 90, 120, 144)) {
            val honeycomb = Honeycomb()
            run(honeycomb, RenderHarness.Song.Lively, rate = rate, seconds = 2f)
            assertTrue(honeycomb.ticks in 98L..100L, "$rate Hz gave ${honeycomb.ticks} reads in two seconds")
        }
        val silent = Honeycomb()
        run(silent, RenderHarness.Song.Silence, rate = 60, seconds = 2f)
        assertEquals(0L, silent.ticks, "silence is not heard, so nothing is read and nothing turns")
    }

    @Test
    fun theTangentPoleSitsBetweenAVolumeOf9424And9425() {
        assertEquals(2.0, Honeycomb.mentalFactor(9_424))
        assertEquals(-20.0, Honeycomb.mentalFactor(9_425))
        // The push is held at 2 from 7,955 up to the pole, and at -20 for 150 past it.
        assertEquals(2.0, Honeycomb.mentalFactor(7_955))
        assertTrue(Honeycomb.mentalFactor(7_954) < 2.0)
        assertEquals(-20.0, Honeycomb.mentalFactor(9_574))
        assertTrue(Honeycomb.mentalFactor(9_575) > -20.0)
        assertTrue(Honeycomb.mentalFactor(18_849) < 0.0 && Honeycomb.mentalFactor(18_851) > 0.0)
        // One step of volume across the pole turns a corner's push from outward to ten times as far inward.
        val out = Honeycomb.offsetFactor(distance = 400.0, volume = 9_424, high = 200.0)
        val back = Honeycomb.offsetFactor(distance = 400.0, volume = 9_425, high = 200.0)
        assertTrue(out > 0.0 && back < 0.0, "outward below the pole, inward above it: $out and $back")
        assertEquals(-10.0, back / out, 0.01)
        assertTrue(abs(back) > 400.0, "past the pole a corner 400 dp out is thrown through the middle, moved $back")
        assertTrue(Honeycomb.offsetFactor(400.0, 9_425, -1.0).isNaN(), "a peak below zero has no push, as on the page")
    }

    @Test
    fun theTurnReversesAboveAVolumeOf10000() {
        assertEquals(0.001, Honeycomb.rotationStep(0))
        assertEquals(0.001, Honeycomb.rotationStep(10_000))
        assertTrue(Honeycomb.rotationStep(10_001) < 0.0)
        assertEquals(0.001 - kotlin.math.sin(14_000 / 800_000.0), Honeycomb.rotationStep(14_000))
    }

    @Test
    fun theHoneycombHas127TilesSizedByTheLongerSideInDp() {
        for ((density, scale) in listOf(1f to 1, 2f to 2, 3f to 3)) {
            val honeycomb = Honeycomb()
            run(honeycomb, RenderHarness.Song.Silence, rate = 60, seconds = 0.05f, width = 960 * scale, height = 540 * scale, density = density)
            assertEquals(38.4, honeycomb.tileSize, 1e-9, "max(960, 540) / 25 in dp at density $density")
            // The centre tile's first corner points straight down, one tile size from the middle.
            assertEquals(0.0, honeycomb.vertexX[0], 1e-9)
            assertEquals(38.4, honeycomb.vertexY[0], 1e-9)
            var farthest = 0.0
            for (corner in 0 until Honeycomb.TILES * Honeycomb.SIDES) {
                farthest = maxOf(farthest, sqrt(honeycomb.vertexX[corner] * honeycomb.vertexX[corner] + honeycomb.vertexY[corner] * honeycomb.vertexY[corner]))
            }
            // Six rings of tiles 67 dp apart, plus a tile size.
            assertTrue(farthest in 400.0..450.0, "the outer ring's corners reach $farthest dp")
        }
        val buckets = (0 until Honeycomb.TILES).map { Honeycomb.bucketOf(it) }
        assertEquals(0, buckets.first())
        assertEquals(2, buckets[1])
        assertEquals(127, buckets.last())
        assertFalse(1 in buckets, "Math.ceil(128 / 127 * num) skips bin 1")
    }

    @Test
    fun thePaletteStopsAreThePagesColours() {
        val stops = mapOf(
            0.0 to 0xFF4040, 32.0 to 0xDB04B4, 64.0 to 0x8011FD, 96.0 to 0x255FDF, 128.0 to 0x00C073,
            160.0 to 0x40FCA5, 200.0 to 0x90E0FF, 230.0 to 0xCC8FFF, 255.0 to 0xFE43FF,
        )
        for ((value, rgb) in stops) {
            assertEquals(rgb.toString(16), Honeycomb.rgbOf(value).toString(16), "the fill at $value")
        }
        assertEquals(0.024, Honeycomb.alphaOf(0.0), 0.001)
        assertEquals(0.34, Honeycomb.alphaOf(32.0), 0.01)
        assertEquals(0.68, Honeycomb.alphaOf(64.0), 0.01)
        assertEquals(0.88, Honeycomb.alphaOf(96.0), 0.01)
        assertTrue(Honeycomb.alphaOf(160.0) > 0.99)
    }

    /** Draws [honeycomb] for [seconds] at [rate] frames a second, the way a window does. */
    private fun run(
        honeycomb: Honeycomb,
        song: RenderHarness.Song,
        rate: Int,
        seconds: Float,
        width: Int = 320,
        height: Int = 200,
        density: Float = 1f,
    ) {
        val player = RenderHarness.player(song, seconds + 4f)
        val bitmap = ImageBitmap(width, height)
        val scope = CanvasDrawScope()
        val size = Size(width.toFloat(), height.toFloat())
        val delta = 1f / rate
        var elapsed = 0f
        var music = 0f
        honeycomb.reset()
        repeat((seconds * rate).roundToInt().coerceAtLeast(1)) {
            elapsed += delta
            val frame = player.next(delta)
            music += delta * frame.motionRate
            val state = VizRenderState(frame, elapsed, delta, VizPalette.Prism, music, player.future)
            scope.draw(Density(density), LayoutDirection.Ltr, Canvas(bitmap), size) {
                with(honeycomb) {
                    draw(state)
                    drawFront(state)
                }
            }
        }
    }

    /** The mean frame-to-frame change over the last second of three. */
    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var change = 0f
        var counted = 0
        RenderHarness.forEachFrame(Honeycomb(), 320, 200, 180, VizPalette.Prism, song) { bitmap, step ->
            val pixels = IntArray(320 * 200)
            bitmap.readPixels(pixels)
            if (step >= 120) {
                previous?.let {
                    change += difference(it, pixels)
                    counted++
                }
            }
            previous = pixels
        }
        return change / counted.coerceAtLeast(1)
    }

    private fun difference(a: IntArray, b: IntArray): Float {
        var sum = 0L
        for (index in a.indices) {
            sum += abs((a[index] shr 16 and 0xFF) - (b[index] shr 16 and 0xFF)) +
                abs((a[index] shr 8 and 0xFF) - (b[index] shr 8 and 0xFF)) +
                abs((a[index] and 0xFF) - (b[index] and 0xFF))
        }
        return sum / (a.size * 3f * 255f)
    }

    /** How much of the image stopped being the corner colour, as the contact sheet measures it. */
    private fun inkFraction(image: BufferedImage): Float {
        val background = image.getRGB(0, 0)
        var different = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                val pixel = image.getRGB(x, y)
                val distance = abs((pixel shr 16 and 0xFF) - (background shr 16 and 0xFF)) +
                    abs((pixel shr 8 and 0xFF) - (background shr 8 and 0xFF)) +
                    abs((pixel and 0xFF) - (background and 0xFF))
                if (distance > 18) different++
            }
        }
        return different.toFloat() / ((image.width / 2) * (image.height / 2))
    }

    /** How much of the image is at or near full brightness on every channel, as the contact sheet measures it. */
    private fun blownFraction(image: BufferedImage): Float {
        var blown = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                val pixel = image.getRGB(x, y)
                if ((pixel shr 16 and 0xFF) > 245 && (pixel shr 8 and 0xFF) > 245 && (pixel and 0xFF) > 245) blown++
            }
        }
        return blown.toFloat() / ((image.width / 2) * (image.height / 2))
    }
}

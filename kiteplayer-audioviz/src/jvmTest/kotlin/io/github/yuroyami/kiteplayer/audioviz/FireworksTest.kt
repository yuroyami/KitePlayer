package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Fireworks
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.SPARK_TEXTURE_SIZE
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.sparkTextureAlpha
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The port of Fireworks with WebGL by Ondřej Žára: it draws, it stands still in silence, and its trigger is the page's. */
class FireworksTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsSixtyFramesOfTheLivelySongWithInkAndNoBlowOut() {
        var peakInk = 0f
        var peakBlown = 0f
        RenderHarness.forEachFrame(Fireworks(), 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val image = with(RenderHarness) { bitmap.toBufferedImage() }
            peakInk = maxOf(peakInk, inkFraction(image))
            peakBlown = maxOf(peakBlown, blownFraction(image))
        }
        assertTrue(peakInk > 0.004f, "the bursts and stars should put ink down, had $peakInk")
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
    fun theTriggerReadsBinZeroOfA1024PointAnalyserEvery30HeardMilliseconds() {
        val fireworks = Fireworks()
        val analyser = fireworks.analyser
        assertEquals(1024, analyser.fftSize)
        assertEquals(512, analyser.binCount)
        assertEquals(0.5f, analyser.smoothing)
        assertEquals(-100f, analyser.minDecibels)
        assertEquals(-20f, analyser.maxDecibels)
        assertEquals(44_100, analyser.sampleRate)
        for (rate in listOf(30, 60, 90, 120, 144)) {
            val drawing = Fireworks()
            run(drawing, RenderHarness.Song.Lively, rate = rate, seconds = 3f)
            // The first frames are not heard yet while the analysis window fills.
            assertTrue(drawing.ticks in 97L..100L, "$rate Hz gave ${drawing.ticks} reads in three seconds")
        }
        val silent = Fireworks()
        run(silent, RenderHarness.Song.Silence, rate = 60, seconds = 2f)
        assertEquals(0L, silent.ticks, "silence is not heard, so nothing is read")
        assertEquals(0, silent.fired)
    }

    @Test
    fun aBurstFiresOnARiseAboveFifteenAtMostOncePer300Milliseconds() {
        val fireworks = Fireworks()
        fireworks.reset()
        fireworks.listen(50, 0.0)
        assertEquals(1, fireworks.fired, "a rise of 50 fires one burst of force 1")
        // Within 300 ms nothing fires, however big the rise, but the last reading still follows.
        fireworks.listen(10, 30.0)
        fireworks.listen(200, 60.0)
        fireworks.listen(40, 270.0)
        assertEquals(1, fireworks.fired)
        // At 300 ms the hold is over: a rise of exactly 15 is not enough, 16 is.
        fireworks.listen(55, 300.0)
        assertEquals(1, fireworks.fired, "a rise of 15 does not fire")
        fireworks.listen(71, 330.0)
        assertEquals(2, fireworks.fired, "a rise of 16 fires")
        // A fall never fires.
        fireworks.listen(0, 660.0)
        assertEquals(2, fireworks.fired)
        // A rise of 55 is a force of 1.1, which is not above 1.1: one burst. A rise of 56 fires two.
        fireworks.listen(55, 690.0)
        assertEquals(3, fireworks.fired)
        fireworks.listen(0, 990.0)
        fireworks.listen(56, 1020.0)
        assertEquals(5, fireworks.fired, "a rise above 55 adds a second, standard burst")
    }

    @Test
    fun theLivelySongsKicksFireBurstsFromTheLowestBin() {
        val fireworks = Fireworks()
        run(fireworks, RenderHarness.Song.Lively, rate = 60, seconds = 4f)
        // 130 beats a minute is a kick every 462 ms, so about eight kicks in four seconds.
        assertTrue(fireworks.fired in 4..16, "the kicks fired ${fireworks.fired} bursts in four seconds")
        assertTrue(fireworks.bursts.isNotEmpty())
    }

    @Test
    fun everyBurstKeepsThePagesCountsColoursAndLifetime() {
        assertEquals(listOf(500, 500), Fireworks.recipeFor(0.8).map { Fireworks.sparkCount(it.sphere, it.amount) })
        assertEquals(listOf(1000), Fireworks.recipeFor(0.5).map { Fireworks.sparkCount(it.sphere, it.amount) })
        assertEquals(listOf(100, 100), Fireworks.recipeFor(0.37).map { Fireworks.sparkCount(it.sphere, it.amount) })
        assertEquals(listOf(200), Fireworks.recipeFor(0.32).map { Fireworks.sparkCount(it.sphere, it.amount) })
        assertEquals(listOf(0.7, 1.2), Fireworks.recipeFor(0.25).map { it.forceScale })
        assertEquals(listOf(0.7, 1.0, 1.3), Fireworks.recipeFor(0.15).map { it.forceScale })
        assertEquals(listOf(true, false, false), Fireworks.recipeFor(0.07).map { it.sphere })
        assertEquals(listOf(1000, 200), Fireworks.recipeFor(0.01).map { Fireworks.sparkCount(it.sphere, it.amount) })
        val fireworks = Fireworks()
        fireworks.reset()
        repeat(40) { fireworks.listen(if (it % 2 == 0) 0 else 30, it * 300.0) }
        assertEquals(20, fireworks.fired)
        for (burst in fireworks.bursts) {
            // force 30 / 50 = 0.6, then 0.1 + 0.6 + up to 0.3.
            assertTrue(burst.lifetimeMs in 2_200.0..4_700.0, "lifetime ${burst.lifetimeMs}")
            assertTrue(abs(burst.centreX) <= 5.0 && abs(burst.centreY) <= 5.0 && abs(burst.centreZ) <= 5.0)
            for (set in burst.sets) {
                assertTrue(set.count in listOf(100, 200, 500, 1000))
                assertTrue(set.red in 0.4f..1.0f && set.green in 0.3f..0.9f && set.blue in 0.2f..0.8f)
            }
        }
    }

    @Test
    fun sparksSlowDownLogarithmicallySagAndShrinkWithDistance() {
        assertEquals(0.0, Fireworks.spreadAt(0.0))
        assertEquals(ln(21.0), Fireworks.spreadAt(1000.0), 1e-12)
        assertEquals(ln(101.0), Fireworks.spreadAt(5000.0), 1e-12)
        assertEquals(-1.25, Fireworks.sagAt(5000.0), 1e-12)
        assertEquals(1007f, Fireworks.pointSize(0.5f))
        assertEquals(9.5f, Fireworks.pointSize(400f))
        assertEquals(7.1f, Fireworks.pointSize(50_000f), 1e-5f)
        // The sprite: (1 - d)^3 from the middle, nothing past the inscribed circle.
        val alpha = sparkTextureAlpha()
        val size = SPARK_TEXTURE_SIZE
        assertTrue(alpha[(size / 2) * size + size / 2] >= 245, "the middle texel is nearly opaque")
        assertEquals(0, alpha[0])
        val quarter = alpha[(size / 2) * size + size / 2 + size / 4]
        assertTrue(abs(quarter - (0.5 * 0.5 * 0.5 * 255).roundToInt()) <= 3, "halfway out reads an eighth, had $quarter")
    }

    @Test
    fun theCameraCirclesOnceIn188HeardSecondsScaledByReducedMotion() {
        assertEquals(188.5, 2.0 * PI * Fireworks.ORBIT_SECONDS_PER_RADIAN, 0.1)
        val full = Fireworks()
        run(full, RenderHarness.Song.Lively, rate = 60, seconds = 3f)
        val heard = full.nowMs / 1000.0
        assertTrue(heard > 2.9, "the drum loop is heard throughout, heard $heard s")
        assertEquals(heard / 30.0, full.orbit, 1e-6)
        val reduced = Fireworks()
        run(reduced, RenderHarness.Song.Lively, rate = 60, seconds = 3f, motionScale = 0.25f)
        assertEquals(full.orbit * 0.25, reduced.orbit, 1e-6)
        val silent = Fireworks()
        run(silent, RenderHarness.Song.Silence, rate = 60, seconds = 3f)
        assertEquals(0.0, silent.orbit)
    }

    /** Draws [fireworks] for [seconds] at [rate] frames a second, the way a window does. */
    private fun run(
        fireworks: Fireworks,
        song: RenderHarness.Song,
        rate: Int,
        seconds: Float,
        motionScale: Float = 1f,
    ) {
        val player = RenderHarness.player(song, seconds + 4f)
        val bitmap = ImageBitmap(320, 200)
        val scope = CanvasDrawScope()
        val size = Size(320f, 200f)
        val delta = 1f / rate
        var elapsed = 0f
        var music = 0f
        fireworks.reset()
        repeat((seconds * rate).roundToInt().coerceAtLeast(1)) {
            elapsed += delta
            val frame = player.next(delta)
            music += delta * frame.motionRate
            val state = VizRenderState(frame, elapsed, delta, VizPalette.Prism, music, player.future)
            state.motionScale = motionScale
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) {
                with(fireworks) {
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
        RenderHarness.forEachFrame(Fireworks(), 320, 200, 180, VizPalette.Prism, song) { bitmap, step ->
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

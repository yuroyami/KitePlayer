package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.RenderHarness.toBufferedImage
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.TwinBloom
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.TwinBloomOutline
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.TwinBloomShape
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Twin Bloom on its own, before it joins the catalogue: the one outline its elements share, the
 * picture it draws, how still it keeps in silence, the cascade and the throw.
 */
class TwinBloomTest {

    init { useSkiaGraphics() }

    @Test
    fun oneOutlineGivesTheSpokeThePetalAndTheDisc() {
        val spoke = profile(TwinBloomShape.Spoke)
        assertTrue(spoke.max() <= 0.1f, "a spoke is thin everywhere, but is ${spoke.max()} of its length wide")

        val petal = profile(TwinBloomShape.Petal)
        val widestAt = petal.indices.maxBy { petal[it] } / SAMPLES.toFloat()
        assertTrue(widestAt > 0.55f, "a petal is widest past the middle, not at $widestAt")
        // A pointed tip narrows at least in step with the distance left to it. A round end narrows as
        // the square root of that distance, so its width falls far less over the last step.
        val petalClosing = petal[SAMPLES - 1] / petal[SAMPLES - 2]
        assertTrue(petalClosing < 0.6f, "a petal is pointed at the tip, but its last step keeps $petalClosing of the width")
        val disc = profile(TwinBloomShape.Disc)
        val discClosing = disc[SAMPLES - 1] / disc[SAMPLES - 2]
        assertTrue(discClosing > 0.65f, "a disc has round ends, but its last step keeps $discClosing of the width")

        val xs = FloatArray(TwinBloomOutline.POINTS)
        val ys = FloatArray(TwinBloomOutline.POINTS)
        TwinBloomOutline.trace(1f, TwinBloomShape.Disc.width, TwinBloomShape.Disc.widest, xs, ys)
        for (index in xs.indices) {
            val distance = hypot(xs[index] - 0.5f, ys[index])
            assertTrue(abs(distance - 0.5f) < 0.002f, "a disc is round, but point $index lies $distance from its middle")
        }
    }

    @Test
    fun drawsTheTwinsOnTheLivelySong() {
        var peakInk = 0f
        var peakBlown = 0f
        RenderHarness.forEachFrame(TwinBloom(), 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30 || step % 5 != 0) return@forEachFrame
            val image = bitmap.toBufferedImage()
            peakInk = max(peakInk, inkFraction(image))
            peakBlown = max(peakBlown, blownFraction(image))
        }
        assertTrue(peakInk > 0.004f, "the twins put down ink on only ${peakInk * 100}% of the picture")
        assertTrue(peakBlown <= 0.3f, "${peakBlown * 100}% of the picture saturated to white")
    }

    @Test
    fun theFirstFrameShowsBothTwinsAcrossMostOfTheShorterSide() {
        for ((width, height) in listOf(320 to 200, 200 to 320)) {
            val image = RenderHarness.render(TwinBloom(), width, height, 1, VizPalette.Prism, RenderHarness.Song.Silence)
            val ink = inkFraction(image)
            assertTrue(ink > 0.1f, "the first frame at ${width}x$height is nearly black: ${ink * 100}% ink")
            val (across, down) = litSpan(image)
            val shorter = minOf(width, height)
            assertTrue(minOf(across, down) >= 0.6f * shorter, "the twins span only ${across}x$down of ${width}x$height")
        }
    }

    @Test
    fun silenceMovesTheTwinsFarLessThanMusic() {
        val music = meanChange(RenderHarness.Song.Lively)
        val quiet = meanChange(RenderHarness.Song.Silence)
        assertTrue(music > 0.004f, "the music render barely moves: $music")
        assertTrue(quiet <= 0.2f * music, "silence moves ${quiet / music} as much as the music does")
    }

    @Test
    fun aTurnStartsACascadeThatShowsTwoShapesAtOnce() {
        // The drum loop has no accepted boundary, so its first turn is the eighth bar's fallback.
        val drawing = TwinBloom()
        var changes = 0
        var turnAt = -1
        var mixedAt = -1
        var settledAt = -1
        RenderHarness.forEachFrame(drawing, 96, 60, 17 * 60, VizPalette.Prism, RenderHarness.Song.Lively) { _, step ->
            val now = drawing.changes
            if (step > 5 * 60 && now > changes && turnAt < 0) turnAt = step
            changes = now
            if (turnAt < 0) return@forEachFrame
            val shapes = (0 until 3).map { drawing.shapeOf(0, it) }.toSet()
            if (mixedAt < 0 && shapes.size > 1) mixedAt = step
            if (mixedAt >= 0 && settledAt < 0 && shapes.size == 1) settledAt = step
        }
        assertTrue(turnAt > 0, "no cascade started after the first five seconds")
        assertTrue(mixedAt >= turnAt, "the cascade never showed two shapes at once")
        assertTrue(settledAt > mixedAt, "the cascade never settled on one shape")
    }

    @Test
    fun aDropThrowsTheDiscsToTheFrameEdgesWithinABar() {
        val drawing = TwinBloom()
        val width = 320
        val height = 200
        val drop = InjectedFrames.STRUCTURE_STEP
        var bar = 0f
        var edgeBefore = 0f
        var edgeAfter = 0f
        var reachedAt = -1
        var apartBefore = 0f
        var apartMost = 0f
        RenderHarness.forEachFrameOf(
            drawing, width, height, drop + 300, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) },
        ) { bitmap, step ->
            if (step == drop) bar = drawing.barSeconds
            val anchors = drawing.anchors
            val apart = hypot((anchors[0].x - anchors[1].x) * width, (anchors[0].y - anchors[1].y) * height)
            if (step == drop - 1) apartBefore = apart
            val image = bitmap.toBufferedImage()
            val edge = edgeInk(image)
            if (step in drop - 20 until drop) edgeBefore = max(edgeBefore, edge)
            if (step >= drop) {
                apartMost = max(apartMost, apart)
                if (step - drop <= bar * 60f) edgeAfter = max(edgeAfter, edge)
                if (reachedAt < 0 && edge > 0.03f) reachedAt = step
            }
        }
        assertTrue(bar > 0f, "the drawing read no bar length at the drop")
        assertTrue(edgeBefore < 0.01f, "the frame edges were lit before the drop: $edgeBefore")
        assertTrue(edgeAfter > 0.03f, "the thrown discs never reached the frame edges within a bar: $edgeAfter")
        assertTrue(reachedAt - drop <= bar * 60f, "the discs reached the edges ${reachedAt - drop} frames after the drop")
        assertTrue(apartMost > 1.5f * apartBefore, "the twins did not fling apart: $apartBefore to at most $apartMost")
    }

    /** The full width along the length, for a length of one. */
    private fun profile(shape: TwinBloomShape): FloatArray =
        FloatArray(SAMPLES + 1) { 2f * TwinBloomOutline.halfWidth(it / SAMPLES.toFloat(), shape.width, shape.widest) }

    /** The mean change between frames over the settled part of a run: two seconds in, four seconds long. */
    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var total = 0f
        var count = 0
        RenderHarness.forEachFrame(TwinBloom(), 128, 80, 360, VizPalette.Prism, song) { bitmap, step ->
            if (step < 120) return@forEachFrame
            val pixels = IntArray(128 * 80)
            bitmap.readPixels(pixels)
            previous?.let {
                total += difference(it, pixels)
                count++
            }
            previous = pixels
        }
        return if (count == 0) 0f else total / count
    }

    private fun difference(a: IntArray, b: IntArray): Float {
        var sum = 0f
        for (index in a.indices) sum += abs(luma(a[index]) - luma(b[index]))
        return sum / a.size
    }

    private fun luma(pixel: Int): Float =
        0.2126f * (pixel shr 16 and 0xFF) / 255f + 0.7152f * (pixel shr 8 and 0xFF) / 255f + 0.0722f * (pixel and 0xFF) / 255f

    /** How much of the picture stopped being the corner colour, the way the contact sheet measures it. */
    private fun inkFraction(image: BufferedImage): Float {
        val background = image.getRGB(0, 0)
        var different = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) if (distance(image.getRGB(x, y), background) > 18) different++
        }
        return different.toFloat() / ((image.width / 2) * (image.height / 2))
    }

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

    /** The width and height of the box round every lit pixel. */
    private fun litSpan(image: BufferedImage): Pair<Int, Int> {
        val background = GROUND
        var left = image.width
        var right = -1
        var top = image.height
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (distance(image.getRGB(x, y), background) <= 18) continue
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        return (right - left + 1).coerceAtLeast(0) to (bottom - top + 1).coerceAtLeast(0)
    }

    /** How much of the outer band of the picture, a thirtieth of each side deep, is lit. */
    private fun edgeInk(image: BufferedImage): Float {
        val background = GROUND
        val bandX = image.width / 30
        val bandY = image.height / 30
        var lit = 0
        var seen = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val inBand = x < bandX || x >= image.width - bandX || y < bandY || y >= image.height - bandY
                if (!inBand) continue
                seen++
                if (distance(image.getRGB(x, y), background) > 40) lit++
            }
        }
        return lit.toFloat() / seen
    }

    private fun distance(left: Int, right: Int): Int =
        abs((left shr 16 and 0xFF) - (right shr 16 and 0xFF)) + abs((left shr 8 and 0xFF) - (right shr 8 and 0xFF)) +
            abs((left and 0xFF) - (right and 0xFF))

    private companion object {
        const val SAMPLES = 200
        val GROUND: Int = VizPalette.Prism.background.toArgb()
    }
}

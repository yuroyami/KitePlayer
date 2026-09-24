package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Iris
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The port of Vissonance's Iris: it draws, it holds still in silence, and it keeps the page's geometry and laws. */
class IrisTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsSixtyFramesOfTheLivelySongWithInkAndNoBlowOut() {
        val iris = Iris()
        assertEquals(PostSpec.Off, iris.post)
        var ink = 0f
        var blown = 0f
        RenderHarness.forEachFrame(iris, 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val image = with(RenderHarness) { bitmap.toBufferedImage() }
            ink = maxOf(ink, inkFraction(image))
            blown = maxOf(blown, blownFraction(image))
        }
        assertTrue(ink > 0.004f, "the iris should put ink down, had $ink")
        assertTrue(blown < 0.05f, "a saturated hue never turns every channel white, had $blown")
        assertTrue(iris.loudness > 1f, "the drum loop should be loud, loudness ${iris.loudness}")
    }

    @Test
    fun standsStillInSilenceAndMovesUnderMusic() {
        val silence = meanChange(RenderHarness.Song.Silence)
        val music = meanChange(RenderHarness.Song.Lively)
        println("Iris frame-to-frame change: silence $silence, music $music")
        assertTrue(silence < 0.0005f, "silence should hold the iris still, changed $silence")
        assertTrue(music > 0.001f, "music should move the iris, changed $music")
        assertTrue(silence <= 0.2f * music, "silence $silence against music $music")
    }

    @Test
    fun theGeometryIsThePages() {
        // rotateX(PI / 1.8) of a 500-unit plane, lifted 60, seen from z = 250.
        assertEquals(496.2f, Iris.FAR_DEPTH, 0.05f)
        assertEquals(3.8f, Iris.NEAR_DEPTH, 0.05f)
        assertEquals(16.59f, Iris.NEAR_Y, 0.05f)
        // A point on the pupil's rim is the start of the spoke, and the far corner of a 16:9 frame lies almost at
        // its near end.
        val focal = 270f * Iris.FOCAL
        assertEquals(0f, Iris.along(focal * 100f / Iris.FAR_DEPTH, 100f, focal), 1e-4f)
        val edge = Iris.along(hypot(480f, 270f), 100f, focal)
        assertTrue(edge in 0.95f..0.99f, "the frame's corner should lie near the spoke's near end, was $edge")
        // The pupil: 65 units plus half the bar plus two thirds of the loudness.
        assertEquals(65f + 50f + 20f, 100f / 2f + Iris.INNER_BASE + 30f / Iris.LOUDNESS_DIVISOR, 1e-4f)
    }

    @Test
    fun theColourIsTheHueTimesTheDepthOverOneHundredEighty() {
        // hsl(250, 100%, 50%) is (1/6, 0, 1): at the far edge, 2.76 times that clamps the blue and lifts the red.
        assertEquals(0xFF7500FF.toInt(), Iris.colourAt(Iris.FAR_DEPTH, 1f / 6f, 0f, 1f, 1f))
        assertEquals(0xFF2B00FF.toInt(), Iris.colourAt(180f, 1f / 6f, 0f, 1f, 1f))
        assertEquals(0xFF010005.toInt(), Iris.colourAt(Iris.NEAR_DEPTH, 1f / 6f, 0f, 1f, 1f))
        assertEquals(250.0, Iris.hueFor(0f), 1e-9)
        assertEquals(140.0, Iris.hueFor(50f), 1e-4)
        assertEquals(30.0, Iris.hueFor(100f), 1e-4)
        val analyser = Iris().analyser
        assertEquals(2048, analyser.binCount)
        assertEquals(0.8f, analyser.smoothing)
        assertEquals(44_100, analyser.sampleRate)
    }

    @Test
    fun theSpokesAreBrightAtThePupilAndDarkAtTheEdgeWithTheBassAtTheTop() {
        var last: BufferedImage? = null
        RenderHarness.forEachFrame(Iris(), 640, 360, 90, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step == 89) last = with(RenderHarness) { bitmap.toBufferedImage() }
        }
        val image = checkNotNull(last)
        // The brightest pixel of each ring round the middle: bright near the pupil, fading toward the frame's edge.
        val nearRing = brightestOnRing(image, 110f)
        val farRing = brightestOnRing(image, 175f)
        assertTrue(nearRing > farRing, "spokes should fade outward: $nearRing near the pupil, $farRing further out")
        // The middle is the page's near-black background: the pupil.
        val middle = image.getRGB(320, 180)
        assertTrue(red(middle) < 12 && green(middle) < 12 && blue(middle) < 12, "the pupil should be dark, was ${hex(middle)}")
        // Mirrored left and right about the vertical axis.
        var asymmetry = 0L
        var total = 0L
        for (y in 0 until 360 step 3) for (x in 0 until 320 step 3) {
            val left = image.getRGB(x, y)
            val right = image.getRGB(639 - x, y)
            asymmetry += abs(luma(left) - luma(right))
            total += luma(left) + luma(right)
        }
        assertTrue(asymmetry < total * 0.1, "the iris should be mirrored left and right: $asymmetry against $total")
    }

    private fun brightestOnRing(image: BufferedImage, radius: Float): Int {
        var best = 0
        for (step in 0 until 720) {
            val angle = step * Math.PI / 360.0
            val x = (320 + radius * kotlin.math.cos(angle)).toInt().coerceIn(0, 639)
            val y = (180 + radius * kotlin.math.sin(angle)).toInt().coerceIn(0, 359)
            best = maxOf(best, luma(image.getRGB(x, y)))
        }
        return best
    }

    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var change = 0f
        var counted = 0
        RenderHarness.forEachFrame(Iris(), 320, 200, 180, VizPalette.Prism, song) { bitmap, step ->
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
            sum += abs(red(a[index]) - red(b[index])) + abs(green(a[index]) - green(b[index])) + abs(blue(a[index]) - blue(b[index]))
        }
        return sum / (a.size * 3f * 255f)
    }

    private fun red(pixel: Int): Int = pixel shr 16 and 0xFF
    private fun green(pixel: Int): Int = pixel shr 8 and 0xFF
    private fun blue(pixel: Int): Int = pixel and 0xFF
    private fun luma(pixel: Int): Int = (red(pixel) * 3 + green(pixel) * 6 + blue(pixel)) / 10
    private fun hex(pixel: Int): String = (pixel and 0xFFFFFF).toString(16).padStart(6, '0')

    private fun inkFraction(image: BufferedImage): Float {
        var lit = 0
        var all = 0
        for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
            all++
            if (luma(image.getRGB(x, y)) > 24) lit++
        }
        return lit.toFloat() / all
    }

    private fun blownFraction(image: BufferedImage): Float {
        var blown = 0
        var all = 0
        for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
            all++
            val pixel = image.getRGB(x, y)
            if (red(pixel) > 245 && green(pixel) > 245 && blue(pixel) > 245) blown++
        }
        return blown.toFloat() / all
    }
}

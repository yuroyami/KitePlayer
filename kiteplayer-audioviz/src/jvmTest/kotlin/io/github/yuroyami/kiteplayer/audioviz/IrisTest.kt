package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Iris
import androidx.compose.ui.graphics.ImageBitmap
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Iris: the page's fibres round a pupil, Kaleidoscope's petals, rings of past waveforms, lids that
 * close in a silence, and forms that change with the music.
 */
class IrisTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsTheLivelySongWithInkAndNoBlowOut() {
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
        assertTrue(blown < 0.05f, "the shared light keeps every channel under white, had $blown")
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
        val focal = 270f * Iris.FOCAL
        assertEquals(0f, Iris.along(focal * 100f / Iris.FAR_DEPTH, 100f, focal), 1e-4f)
        val edge = Iris.along(hypot(480f, 270f), 100f, focal)
        assertTrue(edge in 0.95f..0.99f, "the frame's corner should lie near the spoke's near end, was $edge")
        // The Eye form's pupil at rest is the page's: 65 units plus half the bar plus two thirds of the loudness.
        assertEquals(65f + 50f + 20f, 100f / 2f + Iris.INNER_BASE + 30f / Iris.LOUDNESS_DIVISOR, 1e-4f)
    }

    @Test
    fun theDepthShadingIsThePages() {
        // (1/6, 0, 1) at the far edge: 2.76 times that clamps the blue and lifts the red.
        assertEquals(0xFF7500FF.toInt(), Iris.colourAt(Iris.FAR_DEPTH, 1f / 6f, 0f, 1f, 1f))
        assertEquals(0xFF2B00FF.toInt(), Iris.colourAt(180f, 1f / 6f, 0f, 1f, 1f))
        assertEquals(0xFF010005.toInt(), Iris.colourAt(Iris.NEAR_DEPTH, 1f / 6f, 0f, 1f, 1f))
        val analyser = Iris().analyser
        assertEquals(2048, analyser.binCount)
        assertEquals(0.8f, analyser.smoothing)
        assertEquals(44_100, analyser.sampleRate)
    }

    @Test
    fun theFibresAreBrightAtThePupilAndDarkAtTheEdgeMirroredLeftAndRight() {
        var last: BufferedImage? = null
        val iris = Iris().apply { fibresOnly = true }
        RenderHarness.forEachFrame(iris, 640, 360, 90, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step == 89) last = with(RenderHarness) { bitmap.toBufferedImage() }
        }
        val image = checkNotNull(last)
        // A kick's dilation can push the pupil past 110 pixels, so the near ring is the brightest of a band of radii.
        val nearRing = (100..150 step 10).maxOf { brightestOnRing(image, it.toFloat()) }
        val farRing = brightestOnRing(image, 210f)
        assertTrue(nearRing > farRing, "fibres should fade outward: $nearRing near the pupil, $farRing further out")
        val middle = image.getRGB(320, 180)
        assertTrue(red(middle) < 12 && green(middle) < 12 && blue(middle) < 12, "the pupil should be dark, was ${hex(middle)}")
        var asymmetry = 0L
        var total = 0L
        for (y in 0 until 360 step 3) for (x in 0 until 320 step 3) {
            val left = image.getRGB(x, y)
            val right = image.getRGB(639 - x, y)
            asymmetry += abs(luma(left) - luma(right))
            total += luma(left) + luma(right)
        }
        assertTrue(asymmetry < total * 0.1, "the fibres should be mirrored left and right: $asymmetry against $total")
    }

    @Test
    fun petalsGrowWithLoudness() {
        val quiet = Iris()
        val loud = Iris()
        RenderHarness.forEachFrameOf(quiet, 160, 90, 70, VizPalette.Prism, source = { InjectedFrames.frame(null, it) }) { _, _ -> }
        RenderHarness.forEachFrameOf(loud, 160, 90, 70, VizPalette.Prism,
            source = { InjectedFrames.frame(VizDriver.Waveform, it) }) { _, _ -> }
        println("iris: petals reach ${quiet.petalReach} on a 0.3 trace, ${loud.petalReach} on a 0.9 trace")
        assertTrue(loud.petalReach > quiet.petalReach * 1.5f, "a louder trace grows the petals")
    }

    @Test
    fun aRingBornOnAKickIsStillVisibleOneSecondLater() {
        val kick = InjectedFrames.HIT_STEPS.first()
        val later = kick + 60
        val plain = Iris()
        val kicked = Iris()
        var plainPicture = IntArray(0)
        var kickedPicture = IntArray(0)
        var bornBefore = -1L
        var bornAt = -1L
        RenderHarness.forEachFrameOf(plain, 320, 180, later + 1, VizPalette.Prism,
            source = { InjectedFrames.frame(null, it) }) { bitmap, step ->
            if (step == later) plainPicture = pixels(bitmap)
        }
        RenderHarness.forEachFrameOf(kicked, 320, 180, later + 1, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(if (step == kick) VizDriver.LowHit else null, step) }) { bitmap, step ->
            if (step == kick - 1) bornBefore = kicked.ringsBorn
            if (step == kick) bornAt = kicked.ringsBorn
            if (step == later) kickedPicture = pixels(bitmap)
        }
        assertEquals(0L, bornBefore, "no ring before the kick")
        assertEquals(1L, bornAt, "the kick gives birth to one ring")
        assertEquals(1, kicked.ringsAlive, "the ring is alive a second later")
        // Beyond the petals, the kicked picture holds light the plain one does not.
        var brighter = 0
        for (y in 0 until 180) for (x in 0 until 320) {
            if (hypot(x - 160f, y - 90f) < 50f) continue
            val index = y * 320 + x
            if (luma(kickedPicture[index]) > luma(plainPicture[index]) + 12) brighter++
        }
        println("iris: $brighter pixels lit by the kick's ring a second later")
        assertTrue(brighter > 40, "the ring should still show a second later, lit $brighter pixels")
    }

    @Test
    fun theFormChangesOnTheDrumLoop() {
        val iris = Iris()
        val seen = LinkedHashSet<String>()
        RenderHarness.forEachFrame(iris, 96, 54, 7_200, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ ->
            seen += iris.formName
        }
        println("iris: forms seen $seen, ${iris.forms.morphs} morphs, ${iris.forms.births} births")
        assertTrue(seen.size >= 3, "two minutes of drums must show at least three forms, showed $seen")
        assertTrue(iris.forms.morphs >= 6, "two minutes of drums must morph at least six times")
    }

    @Test
    fun aBreakdownClosesTheEyeToASlit() {
        val iris = Iris()
        var before = 0f
        RenderHarness.forEachFrameOf(iris, 160, 90, InjectedFrames.STRUCTURE_STEP + 180, VizPalette.Prism,
            source = { InjectedFrames.frame(VizDriver.Breakdown, it) }) { _, step ->
            if (step == InjectedFrames.STRUCTURE_STEP - 1) before = iris.openness
        }
        println("iris: lids open ${"%.2f".format(before)} before the breakdown, ${"%.2f".format(iris.openness)} three seconds after")
        assertTrue(before > 0.8f, "before the breakdown the eye is open, was $before")
        assertTrue(iris.openness < 0.15f, "a breakdown closes the eye to a slit, was ${iris.openness}")
    }

    @Test
    fun aSilenceShowsAClosedSlit() {
        val iris = Iris()
        var lit = 1f
        RenderHarness.forEachFrame(iris, 320, 180, 60, VizPalette.Prism, RenderHarness.Song.Silence) { bitmap, step ->
            if (step == 59) lit = inkFraction(with(RenderHarness) { bitmap.toBufferedImage() })
        }
        assertTrue(iris.openness < 0.1f, "a silent eye is closed, open ${iris.openness}")
        assertTrue(lit < 0.05f, "a closed eye shows little more than a slit, lit $lit")
    }

    private fun pixels(bitmap: ImageBitmap): IntArray = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }

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
            val pixels = pixels(bitmap)
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

package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Fracture
import io.github.yuroyami.kiteplayer.audioviz.viz.restart
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The port of Vissonance's Fracture: it draws, it holds still in silence, and it keeps the page's light and laws. */
class FractureTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsSixtyFramesOfTheLivelySongWithInkAndNoBlowOut() {
        val fracture = Fracture()
        assertEquals(PostSpec.Off, fracture.post)
        var ink = 0f
        var blown = 0f
        var dark = 0f
        RenderHarness.forEachFrame(fracture, 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val image = with(RenderHarness) { bitmap.toBufferedImage() }
            ink = maxOf(ink, inkFraction(image))
            blown = maxOf(blown, blownFraction(image))
            dark = maxOf(dark, darkFraction(image))
        }
        assertTrue(ink > 0.004f, "the corridor should put ink down, had $ink")
        // The white is the page's clear colour, seen beside and beyond the strips; the strips must fill most of the frame.
        assertTrue(blown <= 0.3f, "at most the white void may sit at full brightness, had $blown")
        assertTrue(dark > 0.1f, "the strips near the camera should be black, had $dark")
        assertTrue(fracture.loudness > 1f, "the drum loop should be loud enough to fly, loudness ${fracture.loudness}")
    }

    @Test
    fun standsStillInSilenceAndMovesUnderMusic() {
        val silence = meanChange(RenderHarness.Song.Silence)
        val music = meanChange(RenderHarness.Song.Lively)
        println("Fracture frame-to-frame change: silence $silence, music $music")
        assertTrue(silence < 0.0005f, "silence should hold the corridor still, changed $silence")
        assertTrue(music > 0.001f, "music should move the corridor, changed $music")
        assertTrue(silence <= 0.2f * music, "silence $silence against music $music")
    }

    @Test
    fun theClearIsADimSpaceAndStripsBrightenWithDistance() {
        // In silence the corridor rests: the floor 20 units below the eye, the hue 250, no roll.
        var last: BufferedImage? = null
        RenderHarness.forEachFrame(Fracture(), 640, 360, 3, VizPalette.Prism, RenderHarness.Song.Silence) { bitmap, step ->
            if (step == 2) last = with(RenderHarness) { bitmap.toBufferedImage() }
        }
        val image = checkNotNull(last)
        // A dim space beyond the far end of the corridor and beside the strips, at eye level.
        for (pixel in intArrayOf(image.getRGB(320, 180), image.getRGB(1, 180))) {
            val luma = (0.2126f * red(pixel) + 0.7152f * green(pixel) + 0.0722f * blue(pixel)) / 255f
            assertTrue(luma in 0.01f..0.15f, "the clear is a dim space, was ${hex(pixel)} at luma $luma")
        }
        // Straight down the middle of the floor: black at the bottom of the frame, the hue near the far end.
        val near = image.getRGB(320, 359)
        assertTrue(blue(near) < 30 && red(near) < 10, "the floor 29 units ahead should be black, was ${hex(near)}")
        val far = image.getRGB(320, 190)
        assertTrue(blue(far) > blue(near) + 20 && blue(far) > red(far) && green(far) < 5, "the floor 514 units ahead should glow violet-blue, was ${hex(far)}")
        var before = -1
        for (y in 359 downTo 190) {
            val value = blue(image.getRGB(320, y))
            assertTrue(value >= before - 2, "brightness should grow with distance: row $y has $value after $before")
            before = maxOf(before, value)
        }
    }

    @Test
    fun theColourIsTheHueTimesTheDepthOverFiveHundred() {
        // hsl(250, 100%, 50%) through three.js setHSL is (1/6, 0, 1).
        val h = 250.0 / 360.0
        val red = Fracture.hueToRgb(0.0, 1.0, h + 1.0 / 3.0).toFloat()
        val green = Fracture.hueToRgb(0.0, 1.0, h).toFloat()
        val blue = Fracture.hueToRgb(0.0, 1.0, h - 1.0 / 3.0).toFloat()
        assertEquals(1f / 6f, red, 1e-6f)
        assertEquals(0f, green)
        assertEquals(1f, blue)
        assertEquals(0xFF2B00FF.toInt(), Fracture.colourAt(500f, red, green, blue, 1f))
        assertEquals(0xFF150080.toInt(), Fracture.colourAt(250f, red, green, blue, 1f))
        assertEquals(0xFF000003.toInt(), Fracture.colourAt(5f, red, green, blue, 1f))
        // Past 500 units the brightest channel clamps, as the frame buffer does, and the others keep growing.
        assertEquals(0xFF3600FF.toInt(), Fracture.colourAt(630f, red, green, blue, 1f))
        // The flash guard's light scale is the only thing that dims it.
        assertEquals(0xFF150080.toInt(), Fracture.colourAt(500f, red, green, blue, 0.5f))
    }

    @Test
    fun theLoudnessLawsAreThePages() {
        assertEquals(0.0, Fracture.rate(1f))
        assertEquals(0.0, Fracture.rate(0.5f))
        assertEquals(0.0196266, Fracture.rate(80f), 1e-6)
        // At a loudness of 80 the strips fly 2.67 units and the camera turns 0.0098 radians per page frame.
        assertEquals(2.66922, Fracture.flightStep(80f), 1e-4)
        assertEquals(0.0098133, Fracture.rollStep(80f), 1e-6)
        assertEquals(0f, Fracture.squeezeFor(1f))
        assertEquals(6.2745f, Fracture.squeezeFor(80f), 1e-3f)
        assertEquals(9.8f, Fracture.squeezeFor(200f))
        assertEquals(250.0, Fracture.hueFor(0f), 1e-9)
        assertEquals(140.0, Fracture.hueFor(50f), 1e-4)
        assertEquals(30.0, Fracture.hueFor(100f), 1e-4)
        assertEquals(280.0, Fracture.hueFor(150f), 1e-4)
        val analyser = Fracture().analyser
        assertEquals(2048, analyser.binCount)
        assertEquals(0.8f, analyser.smoothing)
        assertEquals(-100f, analyser.minDecibels)
        assertEquals(-30f, analyser.maxDecibels)
        assertEquals(44_100, analyser.sampleRate)
    }

    @Test
    fun theAnalyserIsReadSixtyTimesPerHeardSecondOnAFastDisplay() {
        val fracture = Fracture()
        fracture.restart()
        val player = RenderHarness.player(RenderHarness.Song.Lively, 6f)
        val bitmap = ImageBitmap(64, 36)
        val scope = CanvasDrawScope()
        val delta = 1f / 120f
        var elapsed = 0f
        var reads = 0
        var before = fracture.visual.copyOf()
        for (step in 0 until 240) {
            elapsed += delta
            val state = VizRenderState(player.next(delta), elapsed, delta, VizPalette.Prism, elapsed)
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(64f, 36f)) {
                with(fracture) { draw(state) }
            }
            if (step >= 120 && !fracture.visual.contentEquals(before)) reads++
            before = fracture.visual.copyOf()
        }
        // One second at 120 Hz: the page read once per 60 Hz frame, so about sixty reads.
        assertTrue(reads in 55..61, "the analyser should be read about sixty times a second, was $reads")
    }

    @Test
    fun reducedMotionHoldsTheRollAndTheFlight() {
        val held = Fracture()
        RenderHarness.forEachFrame(held, 160, 90, 90, VizPalette.Prism, RenderHarness.Song.Lively,
            beforeDraw = { it.motionScale = 0f }) { _, _ -> }
        assertTrue(held.loudness > 1f, "the music should be loud enough to fly, loudness ${held.loudness}")
        assertEquals(0.0, held.travel)
        assertEquals(0.0, held.roll)
        val free = Fracture()
        RenderHarness.forEachFrame(free, 160, 90, 90, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ -> }
        assertTrue(free.travel > 0.0, "full motion should fly the corridor")
        assertTrue(free.roll < 0.0, "full motion should roll the camera the page's way")
    }

    @Test
    fun eachShardShowsItsOwnTimeSlice() {
        val fracture = Fracture()
        // The low tone plays from step 60 to 239 and the high one from 240.
        RenderHarness.forEachFrameOf(fracture, 160, 90, 300, VizPalette.Prism,
            source = { step -> InjectedFrames.toneFrame(step, lowBand = step < 240) }) { _, _ -> }
        val lowAt = 4f / 47f
        val highAt = 34f / 47f
        val back = 3 * Fracture.NUM_BANDS / 4
        val nowLow = fracture.sliceLevel(0, lowAt)
        val nowHigh = fracture.sliceLevel(0, highAt)
        val thenLow = fracture.sliceLevel(back, lowAt)
        val thenHigh = fracture.sliceLevel(back, highAt)
        println("fracture: the nearest shard low $nowLow high $nowHigh; three seconds back low $thenLow high $thenHigh")
        assertTrue(nowHigh > nowLow, "the nearest shard shows the high tone that plays now")
        assertTrue(thenLow > thenHigh, "a shard three seconds away shows the low tone that played then")
    }

    @Test
    fun theRollAlternatesAndFlipsOnAMorph() {
        val fracture = Fracture()
        var last = 0f
        var rose = false
        var fell = false
        var steepest = 0f
        RenderHarness.forEachFrameOf(fracture, 64, 36, 400, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) }) { _, step ->
            val roll = fracture.rollValue
            if (step > 0) {
                val change = roll - last
                if (change > 1e-6f) rose = true
                if (change < -1e-6f) fell = true
                steepest = maxOf(steepest, abs(change))
            }
            last = roll
        }
        println("fracture: the roll rose $rose fell $fell, steepest $steepest a frame")
        assertTrue(rose && fell, "the roll turns both ways")
        assertTrue(steepest <= 0.02f, "the roll never jumps, moved $steepest in a frame")
        val sign = fracture.rollSignValue
        fracture.morph()
        assertEquals(-sign, fracture.rollSignValue)
    }

    @Test
    fun theFormIsPublishedAndMorphsOnTheDrumLoop() {
        val fracture = Fracture()
        assertEquals("Corridor", assertNotNull(fracture.forms).form)
        RenderHarness.forEachFrame(fracture, 64, 36, 3600, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ -> }
        val forms = assertNotNull(fracture.forms)
        println("fracture: ${forms.morphs} morphs, ${forms.births} births, form ${forms.form}")
        assertTrue(forms.morphs >= 1, "the drum loop should morph the corridor at least once")
        assertTrue(forms.form in Fracture.FORMS)
    }

    @Test
    fun theLightFollowsTheLevelAndKeepsAQuarterInSilence() {
        val playing = Fracture()
        var heard = 0f
        RenderHarness.forEachFrameOf(playing, 64, 36, 100, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) }, beforeDraw = { it.lightScale = 1f }) { _, step ->
            if (step == 90) heard = playing.lightValue
        }
        val silent = Fracture()
        var quiet = 0f
        RenderHarness.forEachFrame(silent, 64, 36, 120, VizPalette.Prism, RenderHarness.Song.Silence) { _, step ->
            if (step == 119) quiet = silent.lightValue
        }
        println("fracture: light $heard while heard, $quiet in silence")
        assertEquals(lightFor(0.45f), heard, 0.02f)
        assertEquals(Fracture.IDLE_LIGHT, quiet, 0.02f)
    }

    /** The mean colour change between consecutive frames over the last second of three. */
    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var change = 0f
        var counted = 0
        RenderHarness.forEachFrame(Fracture(), 320, 200, 180, VizPalette.Prism, song) { bitmap, step ->
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
    private fun hex(pixel: Int): String = (pixel and 0xFFFFFF).toString(16).padStart(6, '0')

    /** How much of the image stopped being the corner colour, as the contact sheet measures it. */
    private fun inkFraction(image: BufferedImage): Float {
        val background = image.getRGB(0, 0)
        var different = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                val pixel = image.getRGB(x, y)
                val distance = abs(red(pixel) - red(background)) + abs(green(pixel) - green(background)) +
                    abs(blue(pixel) - blue(background))
                if (distance > 18) different++
            }
        }
        return different.toFloat() / ((image.width / 2) * (image.height / 2))
    }

    /** How much of the image is at or near full brightness on every channel, as the contact sheet measures it. */
    private fun blownFraction(image: BufferedImage): Float = share(image) { red(it) > 245 && green(it) > 245 && blue(it) > 245 }

    private fun darkFraction(image: BufferedImage): Float = share(image) { red(it) + green(it) + blue(it) < 60 }

    private fun share(image: BufferedImage, test: (Int) -> Boolean): Float {
        var count = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) if (test(image.getRGB(x, y))) count++
        }
        return count.toFloat() / ((image.width / 2) * (image.height / 2))
    }
}

package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Lines
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The port of Lines by Silvio Paganini: it draws, it holds still in silence, and it keeps the page's knee. */
class LinesTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsSixtyFramesOfTheLivelySongWithInkAndNoBlowOut() {
        var peakInk = 0f
        var peakBlown = 0f
        RenderHarness.forEachFrame(Lines(), 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val image = with(RenderHarness) { bitmap.toBufferedImage() }
            peakInk = maxOf(peakInk, inkFraction(image))
            peakBlown = maxOf(peakBlown, blownFraction(image))
        }
        assertTrue(peakInk > 0.004f, "the lines should put ink down, had $peakInk")
        assertTrue(peakBlown <= 0.3f, "the picture should not saturate to white, had $peakBlown")
    }

    @Test
    fun standsStillInSilenceAndMovesUnderMusic() {
        val silence = changeAndInk(RenderHarness.Song.Silence)
        val music = changeAndInk(RenderHarness.Song.Lively)
        assertTrue(silence.first < 0.0005f, "silence should hold the lines still, changed ${silence.first}")
        assertTrue(silence.second > 0.004f, "the flat lines should stay on screen in silence, ink ${silence.second}")
        assertTrue(music.first > 0.001f, "music should move the lines, changed ${music.first}")
        assertTrue(silence.first <= 0.2f * music.first, "silence ${silence.first} against music ${music.first}")
    }

    @Test
    fun aHeightIsTheFloorOrAPeakWithNothingBetween() {
        for (byte in 0..Lines.KNEE) assertEquals(5f, Lines.heightOf(byte), "byte $byte stays on the floor")
        for (byte in Lines.KNEE + 1..255) {
            val height = Lines.heightOf(byte)
            assertTrue(height >= 30f && height <= 76.5f, "byte $byte gives $height")
        }
        assertEquals(30.3f, Lines.heightOf(101), 1e-4f)
        assertEquals(76.5f, Lines.heightOf(255), 1e-4f)
        assertTrue((0..255).map { Lines.heightOf(it) }.none { it > 5f && it < 30f })
    }

    @Test
    fun theMiddleLinesReadTheBassFromTheBrowserAnalyserLayout() {
        val starts = (0 until Lines.LINES).map { Lines.bandStart(it) }
        assertEquals(0, starts[10])
        assertEquals(0, starts[11])
        assertEquals(640, starts[0])
        assertEquals(640, starts[21])
        for (index in starts.indices) assertEquals(starts[index], starts[Lines.LINES - 1 - index], "line $index")
        val analyser = Lines().analyser
        assertEquals(1024, analyser.binCount)
        assertEquals(0.8f, analyser.smoothing)
        assertEquals(-100f, analyser.minDecibels)
        assertEquals(-30f, analyser.maxDecibels)
        assertTrue(starts.all { it + Lines.RANGE < analyser.binCount }, "every band fits in the 1024 bins")
    }

    @Test
    fun thePageFramesRunSixtyTimesPerHeardSecondAtAnyScreenRate() {
        for (rate in listOf(30, 60, 90, 120, 144)) {
            val frames = Lines.PageFrames()
            var total = 0
            repeat(rate * 2) { total += frames.advance(1f / rate) }
            assertTrue(total in 119..121, "$rate Hz gave $total page frames in two seconds")
        }
        val silent = Lines.PageFrames()
        repeat(120) { assertEquals(0, silent.advance(0f)) }
        val jittery = Lines.PageFrames()
        repeat(600) { assertEquals(1, jittery.advance(if (it % 2 == 0) 1.1f / 60f else 0.9f / 60f)) }
    }

    @Test
    fun theLinesBehindShowThroughATallFrontRidge() {
        // A loud 14.5 kHz tone lands in bins 640 to 704, which only the back and the front line read.
        val tone = FloatArray(48_000 * 7) { 0.5f * sin(2.0 * PI * 14_500.0 * it / 48_000.0).toFloat() }
        val player = SongPlayer(tone, bandCount = 48)
        var last: BufferedImage? = null
        RenderHarness.forEachFrameOf(Lines(), 960, 540, 180, VizPalette.Prism, source = { player.next(1f / 60f) }) { bitmap, step ->
            if (step == 179) last = with(RenderHarness) { bitmap.toBufferedImage() }
        }
        val image = checkNotNull(last)
        // The highest ink is the tip of the front line's ridge: no other line can reach that high.
        var ridgeX = -1
        var ridgeY = image.height
        for (x in 0 until image.width) {
            for (y in 0 until ridgeY) {
                if (lit(image.getRGB(x, y))) {
                    ridgeX = x
                    ridgeY = y
                    break
                }
            }
        }
        assertTrue(ridgeY < image.height / 5, "the front line should rise into a tall ridge, its top is at $ridgeY")
        // Straight down from that tip, the flat lines behind the ridge still show: nothing is filled.
        var runs = 0
        var inRun = false
        for (y in (image.height * 0.4f).toInt() until (image.height * 0.8f).toInt()) {
            val on = lit(image.getRGB(ridgeX, y))
            if (on && !inRun) runs++
            inRun = on
        }
        assertTrue(runs >= 10, "only $runs lines show below the front ridge at x = $ridgeX")
    }

    @Test
    fun theFlatFormAtRestHasTodaysCamera() {
        val lines = Lines()
        RenderHarness.forEachFrame(lines, 320, 180, 2, VizPalette.Prism, RenderHarness.Song.Silence) { _, _ -> }
        assertTrue(lines.eyeX == 0f, "eyeX is ${lines.eyeX}")
        assertTrue(lines.eyeY == 45f, "eyeY is ${lines.eyeY}")
        assertTrue(lines.eyeZ == 240f, "eyeZ is ${lines.eyeZ}")
        assertTrue(lines.bendValue == 0f, "bend is ${lines.bendValue}")
        assertTrue(lines.dipValue == 0f, "dip is ${lines.dipValue}")
        assertNull(lines.forms)
    }

    @Test
    fun aRightHeavyTraceIsAPositiveBalance() {
        val quiet = FloatArray(64) { 0.25f }
        val loud = FloatArray(64) { 0.5f }
        assertTrue(Lines.balanceOf(quiet, loud) > 0f, "right heavy gave ${Lines.balanceOf(quiet, loud)}")
        assertTrue(Lines.balanceOf(loud, quiet) < 0f, "left heavy gave ${Lines.balanceOf(loud, quiet)}")
        assertEquals(0f, Lines.balanceOf(loud, loud.copyOf()), 1e-4f)
    }

    @Test
    fun aLeftHeavyTraceBendsTheRoadLeft() {
        val lines = Lines()
        RenderHarness.forEachFrameOf(
            lines, 160, 90, 240, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Waveform, step) },
        ) { _, _ -> }
        val middle = lines.screenXOf(0, 256)
        println("left heavy trace: bend ${lines.bendValue}, farthest middle vertex at x $middle of 160")
        assertTrue(lines.bendValue < -0.1f, "the road should bend left, bend is ${lines.bendValue}")
        assertTrue(middle < 80f - 2f, "the farthest line should sit left of the middle column, x is $middle")
    }

    @Test
    fun theCameraSwaysOnTheSlowCycleAndRestsInSilence() {
        // One run, loud then silent: a second harness run would restart the drawing and prove nothing.
        val lines = Lines()
        val lively = RenderHarness.player(RenderHarness.Song.Lively, 14f)
        val silent = RenderHarness.player(RenderHarness.Song.Silence, 8f)
        var sway = 0f
        RenderHarness.forEachFrameOf(
            lines, 96, 54, 840, VizPalette.Prism,
            source = { step -> if (step < 600) lively.next(1f / 60f) else silent.next(1f / 60f) },
        ) { _, step ->
            if (step < 600) sway = max(sway, abs(lines.eyeX))
        }
        val rest = abs(lines.eyeX)
        println("camera sway in music $sway, eyeX after silence $rest")
        assertTrue(sway > 2f, "the camera should glide under music, the widest eyeX was $sway")
        assertTrue(rest < 0.5f, "the camera should come home in silence, eyeX is $rest")
    }

    @Test
    fun aQuietFarLineTakesTheFogTintAndAPeakedLineStaysWhite() {
        val calm = Lines()
        RenderHarness.forEachFrame(calm, 160, 90, 120, VizPalette.Prism, RenderHarness.Song.Calm) { _, _ -> }
        val far = calm.lineColourOf(0)
        println("far quiet line: red ${far.red}, green ${far.green}, blue ${far.blue}")
        assertTrue(abs(far.red - far.blue) > 0.01f, "the far line should carry the tint, red ${far.red} blue ${far.blue}")

        val tone = FloatArray(48_000 * 7) { 0.5f * sin(2.0 * PI * 14_500.0 * it / 48_000.0).toFloat() }
        val player = SongPlayer(tone, bandCount = 48)
        val peaked = Lines()
        RenderHarness.forEachFrameOf(peaked, 160, 90, 120, VizPalette.Prism, source = { player.next(1f / 60f) }) { _, _ -> }
        val front = peaked.lineColourOf(21)
        println("peaked front line: red ${front.red}, green ${front.green}, blue ${front.blue}")
        assertTrue(abs(front.red - front.green) < 0.005f, "the front line should stay white, red ${front.red} green ${front.green}")
        assertTrue(abs(front.green - front.blue) < 0.005f, "the front line should stay white, green ${front.green} blue ${front.blue}")
    }

    /** The mean frame-to-frame change over the last second of three, and the ink of the last frame. */
    private fun changeAndInk(song: RenderHarness.Song): Pair<Float, Float> {
        var previous: IntArray? = null
        var change = 0f
        var counted = 0
        var ink = 0f
        RenderHarness.forEachFrame(Lines(), 320, 200, 180, VizPalette.Prism, song) { bitmap, step ->
            val pixels = IntArray(320 * 200)
            bitmap.readPixels(pixels)
            if (step >= 120) {
                previous?.let {
                    change += difference(it, pixels)
                    counted++
                }
            }
            previous = pixels
            if (step == 179) ink = inkFraction(with(RenderHarness) { bitmap.toBufferedImage() })
        }
        return change / counted.coerceAtLeast(1) to ink
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

    private fun lit(pixel: Int): Boolean = (pixel shr 16 and 0xFF) > 10

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

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
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.WavySpiral
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The port of Audible Visuals by Sonia Boller: it draws, it stands still in silence, and it keeps the page's dots, camera and drift. */
class WavySpiralTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsSixtyFramesOfTheLivelySongWithInkAndNoBlowOut() {
        val spiral = WavySpiral()
        assertEquals(PostSpec.Off, spiral.post)
        var peakInk = 0f
        var peakBlown = 0f
        RenderHarness.forEachFrame(spiral, 320, 180, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val pixels = pixels(bitmap)
            peakInk = maxOf(peakInk, inkFraction(pixels, 320, 180))
            peakBlown = maxOf(peakBlown, blownFraction(pixels, 320, 180))
        }
        assertTrue(peakInk > 0.004f, "the dots should put ink down, had $peakInk")
        assertTrue(peakBlown <= 0.3f, "the picture should not saturate to white, had $peakBlown")
    }

    @Test
    fun standsStillInSilenceAndMovesUnderMusic() {
        val silence = meanChange(RenderHarness.Song.Silence)
        val music = meanChange(RenderHarness.Song.Lively)
        println("Wavy Spiral frame-to-frame change: silence $silence, music $music")
        assertTrue(silence < 0.0005f, "silence should hold the picture still, changed $silence")
        assertTrue(music > 0.002f, "music should move the picture, changed $music")
        assertTrue(silence <= 0.2f * music, "silence $silence against music $music")
    }

    @Test
    fun thereAre1025DotsAtTheOddIndicesOneTo2049() {
        assertEquals(1025, WavySpiral.DOTS)
        val indices = (0 until WavySpiral.DOTS).map { WavySpiral.sampleIndex(it) }
        assertEquals((1..2049 step 2).toList(), indices)

        // Odd samples loud and positive, even samples loud and negative: only the odd ones are read.
        val spiral = WavySpiral()
        for (index in spiral.timeFloatData.indices) {
            val sample = if (index % 2 == 1) 0.5f else -0.5f
            spiral.timeFloatData[index] = sample
            spiral.timeFrequencyData[index] = WavySpiral.timeDomainByte(sample)
        }
        spiral.arrange(1920f, 1080f)
        // Index 2049 is past the 2048-sample row, so the last dot is dropped, as on the page.
        assertEquals(1024, spiral.drawn)
        val drawnDots = (0 until spiral.drawn).map { spiral.drawOrder[it] }.toSet()
        assertEquals((0 until 1024).toSet(), drawnDots)
        for (dot in drawnDots) assertEquals(0xFF0032, spiral.dotRgb[dot], "dot $dot should read an odd, positive sample")
    }

    @Test
    fun theCameraSitsAt175WithA35DegreeViewAndTheDotsAreThirdUnitCircles() {
        val spiral = WavySpiral()
        spiral.arrange(1920f, 1080f)
        val focal = 1.0 / tan(17.5 * PI / 180.0)
        // In silence every dot is purple and at depth 0, so a unit spans focal * 540 / 175 pixels.
        val perUnit = focal * 540.0 / 175.0
        assertEquals(0.33 * perUnit, spiral.dotRadius[0].toDouble(), 1e-4)
        assertEquals(3.23, spiral.dotRadius[0].toDouble(), 0.01)
        assertEquals(0xB200B2, spiral.dotRgb[0], "the resting colour is rgb(178, 0, 178)")
        // Dot 0 is placed by j = 1 with the page's own expression.
        val angle = 2.44
        val x = (1.20 + 0.76 * ((angle / 100) * 1)) * sin((angle / 100) * 1) + sin(1 / (angle / 100))
        val y = (1.20 + 0.76 * ((angle / 100) * 1)) * cos((angle / 100) * 1) + cos(1 / (angle / 100))
        assertEquals(960.0 + x * perUnit, spiral.dotX[0].toDouble(), 1e-3)
        assertEquals(540.0 - y * perUnit, spiral.dotY[0].toDouble(), 1e-3)
        // The last drawn dot, j = 2047, sits near the outer edge: 1.2 + 0.76 * 49.9, plus or minus one.
        val outer = hypot(spiral.dotX[1023] - 960.0, spiral.dotY[1023] - 540.0) / perUnit
        assertTrue(outer in 37.1..40.3, "the outer ring should be about 39 units out, was $outer")

        // A portrait frame fits the view to its width, so the dots keep their size in the shorter side.
        spiral.arrange(1080f, 2400f)
        assertEquals(0.33 * perUnit, spiral.dotRadius[0].toDouble(), 1e-4)
    }

    @Test
    fun matchesTheProjectorOfThreeJsR81OnThePagesScene() {
        // Screen x, y and radius from three.js r81's own Projector and canvas sprite scale, run in
        // node on the page's scene at 960 x 540: dots 0, 511 and 1023, silent and at s = 0.5.
        data class Reference(val layout: Float, val sample: Float, val dot: Int, val x: Double, val y: Double, val radius: Double)
        val references = listOf(
            Reference(0f, 0f, 0, 479.4485883486086, 268.88249138203594, 1.6147948677880426),
            Reference(0f, 0f, 511, 458.2851745328941, 172.23628226947162, 1.6147948677880426),
            Reference(0f, 0f, 1023, 421.65133364890687, 83.43544631069409, 1.6147948677880426),
            Reference(0f, 0.5f, 1023, 415.2585806752745, 62.99520011560071, 1.7917138162134143),
            Reference(1f, 0f, 0, 569.3136700137327, 342.87587666668526, 1.614794867788042),
            Reference(1f, 0f, 511, 344.8765943461825, 322.9846026057325, 1.614794867788042),
            Reference(1f, 0f, 1023, 512.4240609855128, 166.17427196448574, 1.614794867788042),
        )
        for (reference in references) {
            val spiral = WavySpiral()
            spiral.layout.value = reference.layout
            spiral.timeFloatData.fill(reference.sample)
            spiral.timeFrequencyData.fill(WavySpiral.timeDomainByte(reference.sample))
            spiral.arrange(960f, 540f)
            assertEquals(1024, spiral.drawn)
            val dot = reference.dot
            assertEquals(reference.x, spiral.dotX[dot].toDouble(), 1e-3, "x of $reference")
            assertEquals(reference.y, spiral.dotY[dot].toDouble(), 1e-3, "y of $reference")
            assertEquals(reference.radius, spiral.dotRadius[dot].toDouble(), 1e-4, "radius of $reference")
        }
    }

    @Test
    fun aSampleTurnsADotRedOrAzureAndPushesItInDepth() {
        assertEquals(192, WavySpiral.timeDomainByte(0.5f))
        assertEquals(64, WavySpiral.timeDomainByte(-0.5f))
        assertEquals(128, WavySpiral.timeDomainByte(0f))
        assertEquals(255, WavySpiral.timeDomainByte(1f))
        // 0.7 - 0.5 is 0.19999999999999996 in doubles, so `| 0` paints 50, not 51, as three.js r81 does.
        assertEquals(0xFF0032, WavySpiral.colourOf(0.5))
        assertEquals(0x327FFF, WavySpiral.colourOf(-0.5))

        val focal = 1.0 / tan(17.5 * PI / 180.0)
        for ((sample, depth) in listOf(0.5f to 17.28, -0.5f to -5.76)) {
            val spiral = WavySpiral()
            spiral.timeFloatData.fill(sample)
            spiral.timeFrequencyData.fill(WavySpiral.timeDomainByte(sample))
            spiral.arrange(1920f, 1080f)
            val expected = 0.33 * focal * 540.0 / (175.0 - depth)
            assertEquals(expected, spiral.dotRadius[0].toDouble(), 1e-4, "a sample of $sample should sit at z = $depth")
        }
    }

    @Test
    fun theFlowerIsACircleOf25WithAnEpicycleOf17() {
        val spiral = WavySpiral()
        spiral.layout.value = 1f
        spiral.arrange(1920f, 1080f)
        val perUnit = 1.0 / tan(17.5 * PI / 180.0) * 540.0 / 175.0
        for (index in 0 until spiral.drawn) {
            val dot = spiral.drawOrder[index]
            val reach = hypot(spiral.dotX[dot] - 960.0, spiral.dotY[dot] - 540.0) / perUnit
            assertTrue(reach in 7.99..42.01, "a flower dot should lie within 25 plus or minus 17, was $reach")
        }
    }

    @Test
    fun theWavyAngleClimbsTo248AndFallsFasterTo243() {
        val spiral = WavySpiral()
        spiral.drift(1.0)
        assertEquals(2.440004, spiral.wavyAngle, 1e-12)
        spiral.wavyAngle = 2.479999
        spiral.drift(1.0)
        spiral.drift(1.0)
        assertEquals(2.479999 + 0.000004 - 0.000006, spiral.wavyAngle, 1e-12, "past 2.48 it turns and falls 0.000006 a frame")
        spiral.wavyAngle = 2.430001
        spiral.drift(1.0)
        spiral.drift(1.0)
        assertEquals(2.430001 - 0.000006 + 0.000004, spiral.wavyAngle, 1e-12, "past 2.43 it climbs again")

        // The Flower's angle starts out falling, by 0.0000004 a frame, and the Wavy Spiral's holds meanwhile.
        spiral.layout.value = 1f
        val held = spiral.wavyAngle
        spiral.drift(1.0)
        assertEquals(2.86 - 0.0000004, spiral.flowerAngle, 1e-12)
        assertEquals(held, spiral.wavyAngle)
    }

    @Test
    fun theDriftKeepsThePagesSpeedAtAnyScreenRateAndStopsInSilence() {
        val angles = listOf(60, 120, 144).map { rate ->
            val spiral = WavySpiral()
            run(spiral, RenderHarness.Song.Lively, rate, seconds = 2f)
            spiral.wavyAngle - 2.44
        }
        println("Wavy Spiral drift over two seconds at 60, 120 and 144 Hz: $angles")
        // Two seconds of a 60 Hz page is 120 frames of 0.000004.
        for (moved in angles) assertEquals(0.00048, moved, 0.00003)
        val silent = WavySpiral()
        run(silent, RenderHarness.Song.Silence, 60, seconds = 2f)
        assertEquals(2.44, silent.wavyAngle, "silence is not heard, so the angle holds")
    }

    @Test
    fun theWaveformIsReadSixtyTimesASecondOnAFastScreen() {
        for (rate in listOf(60, 120, 144)) {
            val spiral = WavySpiral()
            run(spiral, RenderHarness.Song.Lively, rate, seconds = 1f)
            assertTrue(spiral.reads in 59L..61L, "$rate Hz gave ${spiral.reads} reads in one second")
        }
    }

    /** Draws [spiral] for [seconds] at [rate] frames a second, the way a window does. */
    private fun run(spiral: WavySpiral, song: RenderHarness.Song, rate: Int, seconds: Float) {
        val player = RenderHarness.player(song, seconds + 4f)
        val bitmap = ImageBitmap(160, 90)
        val scope = CanvasDrawScope()
        val size = Size(160f, 90f)
        val delta = 1f / rate
        var elapsed = 0f
        var music = 0f
        spiral.reset()
        repeat((seconds * rate).roundToInt()) {
            elapsed += delta
            val frame = player.next(delta)
            music += delta * frame.motionRate
            val state = VizRenderState(frame, elapsed, delta, VizPalette.Prism, music, player.future)
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) {
                with(spiral) {
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
        RenderHarness.forEachFrame(WavySpiral(), 320, 180, 180, VizPalette.Prism, song) { bitmap, step ->
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

    private fun pixels(bitmap: ImageBitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }

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
    private fun inkFraction(pixels: IntArray, width: Int, height: Int): Float {
        val background = pixels[0]
        var different = 0
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val pixel = pixels[y * width + x]
                val distance = abs((pixel shr 16 and 0xFF) - (background shr 16 and 0xFF)) +
                    abs((pixel shr 8 and 0xFF) - (background shr 8 and 0xFF)) +
                    abs((pixel and 0xFF) - (background and 0xFF))
                if (distance > 18) different++
            }
        }
        return different.toFloat() / ((width / 2) * (height / 2))
    }

    /** How much of the image is at or near full brightness on every channel, as the contact sheet measures it. */
    private fun blownFraction(pixels: IntArray, width: Int, height: Int): Float {
        var blown = 0
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val pixel = pixels[y * width + x]
                if ((pixel shr 16 and 0xFF) > 245 && (pixel shr 8 and 0xFF) > 245 && (pixel and 0xFF) > 245) blown++
            }
        }
        return blown.toFloat() / ((width / 2) * (height / 2))
    }
}

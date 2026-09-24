package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.StereoHistory
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.BassCutStream
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ConstantQBars
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.MusicalSpectrum
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The port of ShowCQTBar by Muhammad Faiz: it draws, it stands still in silence, and it keeps the page's bass cut and colours. */
class MusicalSpectrumTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsSixtyFramesOfTheLivelySongWithInkAndNoBlowOut() {
        var peakInk = 0f
        var peakBlown = 0f
        RenderHarness.forEachFrame(MusicalSpectrum(), 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val image = with(RenderHarness) { bitmap.toBufferedImage() }
            peakInk = maxOf(peakInk, inkFraction(image))
            peakBlown = maxOf(peakBlown, blownFraction(image))
        }
        assertTrue(peakInk > 0.004f, "the bars should put ink down, had $peakInk")
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
    fun theTransformRunsSixtyTimesAHeardSecondAtAnyScreenRate() {
        for (rate in listOf(60, 90, 120, 144)) {
            val drawing = MusicalSpectrum()
            run(drawing, song(tones = listOf(440.0 to 0.1f)), rate = rate, seconds = 2f)
            assertTrue(drawing.reads in 118L..121L, "$rate Hz gave ${drawing.reads} transforms in two seconds")
        }
        // A slower screen transforms once a frame, as the page did on one.
        val slow = MusicalSpectrum()
        run(slow, song(tones = listOf(440.0 to 0.1f)), rate = 30, seconds = 2f)
        assertTrue(slow.reads in 59L..61L, "30 Hz gave ${slow.reads} transforms in two seconds")
        val silent = MusicalSpectrum()
        run(silent, song(tones = emptyList()), rate = 60, seconds = 2f)
        assertTrue(silent.reads <= 1L, "silence is not heard, so the picture is not transformed again, had ${silent.reads}")
    }

    @Test
    fun theBassCutSitsBeforeTheTransform() {
        // Equal tones at 30 Hz and 1 kHz. Through the page's graph the 30 Hz one loses 16 dB first.
        val drawing = MusicalSpectrum()
        val tones = listOf(30.0 to 0.1f, 1_000.0 to 0.1f)
        run(drawing, song(tones), rate = 60, seconds = 3f, width = 320, height = 60)
        assertEquals(320, drawing.columns)
        val cut = drawing.row
        // The same steady tones straight into the transform, with the mono panner's -3 dB and no cut.
        val bars = ConstantQBars(48_000, 320)
        val window = FloatArray(bars.span) { n -> tones.sumOf { (f, a) -> a * sin(2.0 * PI * f * n / 48_000) }.toFloat() }
        val uncut = FloatArray(320 * 4)
        bars.transform(window, window, 0.70710677f, BAR, COLOUR, uncut)
        val lowCut = peakHeight(cut, 30.0)
        val lowUncut = peakHeight(uncut, 30.0)
        val highCut = peakHeight(cut, 1_000.0)
        val highUncut = peakHeight(uncut, 1_000.0)
        assertEquals(0.986f, highCut / highUncut, 0.03f, "1 kHz passes the cut: $highCut against $highUncut")
        assertEquals(0.158f, lowCut / lowUncut, 0.02f, "30 Hz loses 16 dB: $lowCut against $lowUncut")
    }

    @Test
    fun aToneOnTheLeftIsAmberAndOnTheRightIsAzure() {
        for (side in listOf(1f, -1f)) {
            val drawing = MusicalSpectrum()
            run(drawing, song(listOf(440.0 to 0.02f), panned = side), rate = 60, seconds = 2f, width = 1_200, height = 12)
            val row = drawing.row
            // Ten columns a semitone at 1200: A4 is columns 530 to 539.
            val column = (530..539).maxBy { row[it * 4 + 3] }
            val red = row[column * 4]
            val green = row[column * 4 + 1]
            val blue = row[column * 4 + 2]
            if (side > 0f) {
                assertTrue(red > 0.2f && blue < 0.05f, "left is amber: $red $green $blue")
                assertEquals(0.8409f, green / red, 0.02f)
            } else {
                assertTrue(blue > 0.2f && red < 0.05f, "right is azure: $red $green $blue")
                assertEquals(0.8409f, green / blue, 0.02f)
            }
        }
    }

    @Test
    fun theTransformCostIsReported() {
        val rate = 48_000
        val random = Random(7)
        val block = rate / 60
        val noise = FloatArray(block * 2)
        for (columns in listOf(1_920, 914, 480)) {
            val history = StereoHistory(rate)
            history.indexAt(1L, 0L)
            var head = 0L
            fun write(frames: Int) {
                repeat(frames / block) {
                    for (index in noise.indices) noise[index] = random.nextFloat() - 0.5f
                    history.write(noise, block, 2, 0L, 1L)
                    head += block
                }
            }
            write(rate * 2)
            val bars = ConstantQBars(rate, columns)
            val stream = BassCutStream(rate, bars.span)
            val left = FloatArray(bars.span)
            val right = FloatArray(bars.span)
            val out = FloatArray(columns * 4)
            val times = ArrayList<Long>()
            repeat(400) { read ->
                write(block)
                // A shown frame trails the analyser by about 200 ms.
                val started = System.nanoTime()
                stream.advanceTo(history, 1L, head - rate / 5)
                stream.latest(left, right)
                bars.transform(left, right, 1f, BAR, COLOUR, out)
                if (read >= 100) times += System.nanoTime() - started
            }
            times.sort()
            val mean = times.average() / 1e6
            val p95 = times[times.size * 95 / 100] / 1e6
            println("Musical Spectrum transform at $columns columns: mean %.2f ms, p95 %.2f ms, %d kernel coefficients"
                .format(mean, p95, bars.kernelSize))
            assertTrue(mean < 40.0, "one transform should cost milliseconds, took $mean ms")
        }
    }

    /** A 48 kHz song of steady [tones] (frequency to amplitude), panned fully to one side when [panned] is not 0. */
    private fun song(tones: List<Pair<Double, Float>>, panned: Float = 0f): Pair<FloatArray, FloatArray?> {
        val frames = 48_000 * 8
        val mono = FloatArray(frames) { n -> tones.sumOf { (f, a) -> a * sin(2.0 * PI * f * n / 48_000) }.toFloat() }
        if (panned == 0f) return mono to null
        // Left is mono plus side and right is mono minus side, so half of each is one side alone.
        val half = FloatArray(frames) { mono[it] * 0.5f }
        return half to FloatArray(frames) { half[it] * panned }
    }

    /** Draws [drawing] for [seconds] at [rate] frames a second, the way a window does. */
    private fun run(
        drawing: MusicalSpectrum,
        song: Pair<FloatArray, FloatArray?>,
        rate: Int,
        seconds: Float,
        width: Int = 160,
        height: Int = 60,
    ) {
        val player = SongPlayer(song.first, bandCount = 48, side = song.second)
        val bitmap = ImageBitmap(width, height)
        val scope = CanvasDrawScope()
        val size = Size(width.toFloat(), height.toFloat())
        val delta = 1f / rate
        var elapsed = 0f
        var music = 0f
        drawing.reset()
        repeat((seconds * rate).roundToInt()) {
            elapsed += delta
            val frame = player.next(delta)
            music += delta * frame.motionRate
            val state = VizRenderState(frame, elapsed, delta, VizPalette.Prism, music, player.future)
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) {
                with(drawing) {
                    draw(state)
                    drawFront(state)
                }
            }
        }
    }

    /** The tallest bar within a semitone of [frequency] in a row of [columns]. */
    private fun peakHeight(row: FloatArray, frequency: Double): Float {
        val columns = row.size / 4
        val at = columns * ln(frequency / ConstantQBars.BASE_HZ) / ln(1024.0) - 0.5
        val reach = columns / 120.0
        var best = 0f
        for (column in (at - reach).roundToInt()..(at + reach).roundToInt()) {
            if (column in 0 until columns) best = maxOf(best, row[column * 4 + 3])
        }
        return best
    }

    /** The mean frame-to-frame change over the last second of three. */
    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var change = 0f
        var counted = 0
        RenderHarness.forEachFrame(MusicalSpectrum(), 320, 200, 180, VizPalette.Prism, song) { bitmap, step ->
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

    private fun inkFraction(image: BufferedImage): Float {
        val background = image.getRGB(0, 0)
        var different = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                val pixel = image.getRGB(x, y)
                val distance = abs((pixel shr 16 and 0xFF) - (background shr 16 and 0xFF)) +
                    abs((pixel shr 8 and 0xFF) - (background shr 8 and 0xFF)) + abs((pixel and 0xFF) - (background and 0xFF))
                if (distance > 18) different++
            }
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

    private companion object {
        const val BAR = 8.912509f
        const val COLOUR = 17.782795f
    }
}

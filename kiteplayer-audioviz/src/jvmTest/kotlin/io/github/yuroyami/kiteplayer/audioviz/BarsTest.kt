package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Bars
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bars: the meter as the front row of a city of its own past, with a rail of the live waveform. */
class BarsTest {

    init { useSkiaGraphics() }

    @Test
    fun theRowsBehindTheFrontRowAreThePastSpectrum() {
        val bars = Bars()
        val lowAt = indexOfMax(InjectedFrames.toneFrame(60, true).bandsRel) / 47f
        val highAt = indexOfMax(InjectedFrames.toneFrame(60, false).bandsRel) / 47f
        val read = FloatArray(6)
        // The tone starts at step 60: low until step 119, high from step 120.
        RenderHarness.forEachFrameOf(bars, 64, 36, 200, VizPalette.Prism,
            source = { step -> InjectedFrames.toneFrame(step, lowBand = step < 120) }) { _, step ->
            if (step != 160) return@forEachFrameOf
            read[0] = bars.rowLevel(0, highAt)
            read[1] = bars.rowLevel(0, lowAt)
            read[2] = bars.rowLevel(5, highAt)
            read[3] = bars.rowLevel(5, lowAt)
            read[4] = bars.rowLevel(12, lowAt)
            read[5] = bars.rowLevel(12, highAt)
        }
        println("bars: row 0 high ${read[0]} low ${read[1]}, row 5 high ${read[2]} low ${read[3]}, row 12 low ${read[4]} high ${read[5]}")
        assertTrue(read[0] > read[1], "the front row is the present, the high tone")
        assertTrue(read[2] > read[3], "a third of a second back is after the switch, the high tone")
        assertTrue(read[4] > read[5], "0.8 s back is before the switch, the low tone")
    }

    @Test
    fun theRailIsTheLiveWaveform() {
        val loud = railRun(VizDriver.Waveform)
        val still = railRun(null)
        println("bars: rail ${loud.first} against ${still.first}, bottom third luma ${loud.second} against ${still.second}")
        assertTrue(loud.first > 2f * still.first, "the rail swings with the waveform: ${loud.first} against ${still.first}")
        assertTrue(abs(loud.second - still.second) > 0.002f, "the rail shows in the bottom third: ${loud.second} against ${still.second}")
    }

    private fun railRun(driver: VizDriver?): Pair<Float, Float> {
        val bars = Bars()
        var amplitude = 0f
        var luma = 0f
        RenderHarness.forEachFrameOf(bars, 160, 90, 100, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(driver, step) }) { bitmap, step ->
            if (step != 90) return@forEachFrameOf
            amplitude = bars.railAmplitude()
            val pixels = IntArray(160 * 90)
            bitmap.readPixels(pixels)
            var total = 0f
            for (y in 60 until 90) for (x in 0 until 160) total += luma(pixels[y * 160 + x])
            luma = total / (160 * 30)
        }
        return amplitude to luma
    }

    @Test
    fun theBarsWearThePaletteByBand() {
        val prism = RenderHarness.render(Bars(), 160, 90, 120, VizPalette.Prism, RenderHarness.Song.Lively)
        val fire = RenderHarness.render(Bars(), 160, 90, 120, VizPalette.Fire, RenderHarness.Song.Lively)
        val difference = meanDifference(prism, fire)
        println("bars: Prism against Fire $difference")
        assertTrue(difference > 0.03f, "the palette colours the city, had $difference")
    }

    @Test
    fun silenceLeavesAHallWithAGridAndARail() {
        var lit = 0f
        var mean = 1f
        RenderHarness.forEachFrame(Bars(), 160, 90, 120, VizPalette.Prism, RenderHarness.Song.Silence) { bitmap, step ->
            if (step != 119) return@forEachFrame
            val pixels = IntArray(160 * 90)
            bitmap.readPixels(pixels)
            lit = pixels.count { luma(it) > 0.03f } / pixels.size.toFloat()
            mean = pixels.sumOf { luma(it).toDouble() }.toFloat() / pixels.size
        }
        println("bars: silence lit share $lit, mean luma $mean")
        assertTrue(lit > 0.005f, "the empty hall still shows its grid and rail, had $lit")
        assertTrue(mean < 0.08f, "the empty hall is dim, had $mean")
    }

    @Test
    fun theDeadControlsAreGone() {
        assertEquals(
            listOf("Background grid", "Grid opacity", "Opposing bars", "Opposing opacity", "Opposing gap",
                "Segmented bars", "Cell rows", "Cell fill", "Bar width", "Sensitivity (dB)", "Frequency balance",
                "Height curve", "Activity heat", "Embers", "Reflection depth", "Floor and ceiling"),
            Bars().params.map { it.name },
        )
    }

    @Test
    fun theLightFollowsTheLevelAndKeepsAFloorInSilence() {
        val playing = Bars()
        var heard = 0f
        RenderHarness.forEachFrameOf(playing, 64, 36, 100, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) }, beforeDraw = { it.lightScale = 1f }) { _, step ->
            if (step == 90) heard = playing.glowValue
        }
        val silent = Bars()
        var quiet = 0f
        RenderHarness.forEachFrame(silent, 64, 36, 120, VizPalette.Prism, RenderHarness.Song.Silence) { _, step ->
            if (step == 119) quiet = silent.glowValue
        }
        println("bars: light $heard while heard, $quiet in silence")
        assertEquals(lightFor(0.45f), heard, 0.02f)
        assertEquals(Bars.IDLE_LIGHT, quiet, 0.02f)
    }

    private fun indexOfMax(values: FloatArray): Int = values.indices.maxBy { values[it] }

    /** The mean difference of the three channels between two renders, 0 for equal and 1 for black against white. */
    private fun meanDifference(a: BufferedImage, b: BufferedImage): Float {
        var total = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            total += abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) +
                abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))
        }
        return total / (a.width * a.height * 3f * 255f)
    }

    private fun luma(pixel: Int): Float =
        (0.2126f * (pixel shr 16 and 0xFF) + 0.7152f * (pixel shr 8 and 0xFF) + 0.0722f * (pixel and 0xFF)) / 255f
}

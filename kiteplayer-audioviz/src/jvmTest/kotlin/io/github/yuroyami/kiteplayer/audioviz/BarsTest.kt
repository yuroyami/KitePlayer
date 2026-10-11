package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Bars
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test
    fun theFormIsPublishedAndMorphsOnTheDrumLoop() {
        val bars = Bars()
        assertEquals("Hall", assertNotNull(bars.forms).form)
        RenderHarness.forEachFrame(bars, 64, 36, 3600, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ -> }
        val forms = assertNotNull(bars.forms)
        println("bars: ${forms.morphs} morphs, ${forms.births} births, form ${forms.form}")
        assertTrue(forms.morphs >= 1, "the drum loop should morph the city at least once")
        assertTrue(forms.form in Bars.FORMS, "the form is one of the four, had ${forms.form}")
    }

    @Test
    fun aMorphBendsTheGridOverTwoToFourCycles() {
        val bars = Bars()
        val read = FloatArray(3)
        var started = false
        // The harness restarts the drawing before frame 0, so the morph starts on the first draw.
        RenderHarness.forEachFrameOf(bars, 64, 36, 900, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) },
            beforeDraw = { if (!started) { bars.morphTo(Bars.CANYON); started = true } }) { _, step ->
            when (step) {
                60 -> read[0] = bars.morphValue
                120 -> read[1] = bars.morphValue
                899 -> read[2] = bars.morphValue
            }
        }
        println("bars: morph ${read[0]} at step 60, ${read[1]} at 120, ${read[2]} at 899")
        assertTrue(read[0] < 0.5f, "the morph is slow, had ${read[0]} after a second")
        assertTrue(read[1] > 0f, "the morph moves, had ${read[1]}")
        assertEquals(1f, read[2], 1e-4f)
    }

    @Test
    fun theFourFormsPlaceTheBarsDifferently() {
        val bars = Bars()
        val heads = Array(4) { FloatArray(3) }
        for (form in 0 until 4) {
            bars.jumpTo(form)
            bars.barHead(row = 10, band = 20, into = heads[form])
        }
        for (a in 0 until 4) for (b in a + 1 until 4) {
            val dx = heads[a][0] - heads[b][0]
            val dy = heads[a][1] - heads[b][1]
            val dz = heads[a][2] - heads[b][2]
            val apart = sqrt(dx * dx + dy * dy + dz * dz)
            println("bars: ${Bars.FORMS[a]} and ${Bars.FORMS[b]} are $apart apart")
            assertTrue(apart > 2f, "${Bars.FORMS[a]} and ${Bars.FORMS[b]} place the bar only $apart apart")
        }
    }

    @Test
    fun aBirthBurstsTheFrontRowIntoEmbers() {
        val bars = Bars()
        var before = 0
        var after = 0
        RenderHarness.forEachFrameOf(bars, 64, 36, 71, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) }) { _, step ->
            if (step == 69) {
                before = bars.emberCount()
                bars.birth()
            }
            if (step == 70) after = bars.emberCount()
        }
        println("bars: $before embers before the birth, $after after")
        assertTrue(after - before >= 40, "a birth bursts the front row into embers, had $before then $after")
    }

    @Test
    fun theCameraMovesOnTheSlowCycleAndNeverCuts() {
        val bars = Bars()
        var lowest = 0f
        var highest = 0f
        var largestStep = 0f
        var last = Float.NaN
        RenderHarness.forEachFrame(bars, 96, 54, 1800, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ ->
            val now = bars.cameraYaw
            lowest = minOf(lowest, now)
            highest = maxOf(highest, now)
            if (!last.isNaN()) largestStep = maxOf(largestStep, abs(now - last))
            last = now
        }
        val silent = Bars()
        var atSixty = 0f
        var atEnd = 0f
        RenderHarness.forEachFrame(silent, 96, 54, 300, VizPalette.Prism, RenderHarness.Song.Silence) { _, step ->
            if (step == 60) atSixty = silent.cameraYaw
            if (step == 299) atEnd = silent.cameraYaw
        }
        println("bars: yaw from $lowest to $highest, largest step $largestStep, silence moved ${abs(atEnd - atSixty)}")
        assertTrue(lowest < 0f && highest > 0f, "the camera swings both ways, had $lowest to $highest")
        assertTrue(largestStep <= 0.25f / 60f + 1e-4f, "the camera never cuts, had a step of $largestStep")
        assertTrue(abs(atEnd - atSixty) < 0.001f, "a silence keeps the camera home")
    }

    @Test
    fun noHitDriverDeclaresTheCamera() {
        val hits = setOf(VizDriver.LowHit, VizDriver.BodyHit, VizDriver.HighHit, VizDriver.Onset)
        val offending = assertNotNull(Bars().mapping).drives.filter { it.driver in hits && it.property == VizProperty.Camera }
        assertTrue(offending.isEmpty(), "no hit may move the camera, had $offending")
    }

    @Test
    fun aKickSendsAWaveBackThroughTheRows() {
        val bars = Bars()
        val read = FloatArray(4)
        // The driver hits at 66, 96 and 126; only the first lands, so a second kick cannot restart the wave.
        RenderHarness.forEachFrameOf(bars, 64, 36, 140, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(if (step < 90) VizDriver.LowHit else null, step) }) { _, step ->
            if (step == 76) {
                read[0] = bars.rowLift(10)
                read[1] = bars.rowLift(40)
            }
            if (step == 106) {
                read[2] = bars.rowLift(10)
                read[3] = bars.rowLift(40)
            }
        }
        println("bars: wave at step 76 row 10 ${read[0]} row 40 ${read[1]}, at step 106 row 10 ${read[2]} row 40 ${read[3]}")
        assertTrue(read[0] > 0.05f && read[0] > read[1], "the wave is near the front first")
        assertTrue(read[3] > read[2], "half a second later the wave is far back")
    }

    @Test
    fun aDropLiftsEveryRowAndTheRoomLight() {
        val bars = Bars()
        val lowAt = indexOfMax(InjectedFrames.toneFrame(60, true).bandsRel) / 47f
        var bloom = 0f
        var shown = 0f
        var level = 0f
        var lightBefore = 0f
        var lightAfter = 0f
        RenderHarness.forEachFrameOf(bars, 64, 36, 140, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) }) { _, step ->
            if (step == 60) lightBefore = bars.glowValue
            if (step == 76) {
                bloom = bars.bloomValue
                shown = bars.rowLevelShown(30, lowAt)
                level = bars.rowLevel(30, lowAt)
                lightAfter = bars.glowValue
            }
        }
        println("bars: drop bloom $bloom, row 30 shown $shown against $level, light $lightBefore then $lightAfter")
        assertTrue(bloom > 0.5f, "the drop lifts the city, had $bloom")
        assertTrue(shown >= level, "a lifted row is drawn at least as high, had $shown against $level")
        assertTrue(lightAfter > lightBefore, "the room light rises on the drop, had $lightBefore then $lightAfter")
    }

    @Test
    fun aBreakdownSinksTheRowsAndKeepsTheRail() {
        val bars = Bars()
        var collapse = 0f
        var scale = 1f
        var rail = 0f
        RenderHarness.forEachFrameOf(bars, 64, 36, 200, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Breakdown, step) }) { _, step ->
            if (step == 126) {
                collapse = bars.collapseValue
                scale = bars.rowScaleValue
                rail = bars.railAmplitude()
            }
        }
        val still = Bars()
        var stillRail = 0f
        RenderHarness.forEachFrameOf(still, 64, 36, 200, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) }) { _, step ->
            if (step == 126) stillRail = still.railAmplitude()
        }
        println("bars: breakdown collapse $collapse, row scale $scale, rail $rail against $stillRail")
        assertTrue(collapse > 0.5f, "the breakdown sinks the city, had $collapse")
        assertTrue(scale < 0.3f, "the rows keep under a third of their height, had $scale")
        assertEquals(stillRail, rail, 0.01f)
    }

    @Test
    fun theEmbersStayUnderTwoHundredASecond() {
        val bars = Bars()
        val spawned = IntArray(600)
        RenderHarness.forEachFrame(bars, 64, 36, 600, VizPalette.Prism, RenderHarness.Song.Lively) { _, step ->
            spawned[step] = bars.embersSpawned
        }
        var most = 0
        for (end in 59 until 600) most = maxOf(most, spawned[end] - (if (end >= 60) spawned[end - 60] else 0))
        println("bars: at most $most hat and trickle embers in one second, ${bars.embersSpawned} in all, ${bars.birthEmbers} from births")
        assertTrue(most <= 200, "at most two hundred embers a second, had $most")
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

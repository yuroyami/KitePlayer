package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Pipe
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Pipe: a lit tunnel of spectrum rings, one ring per sixteenth note, still in silence. */
class PipeTest {

    init { useSkiaGraphics() }

    @Test
    fun theLivelySongDrawsALitTunnelWithoutBlowingOut() {
        var ink = 0f
        var blown = 0f
        RenderHarness.forEachFrame(Pipe(), 320, 180, 180, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 120) return@forEachFrame
            val pixels = IntArray(320 * 180)
            bitmap.readPixels(pixels)
            ink = maxOf(ink, pixels.count { luma(it) > 0.1f } / pixels.size.toFloat())
            blown = maxOf(blown, pixels.count { luma(it) > 0.97f } / pixels.size.toFloat())
        }
        println("pipe: ink $ink, blown $blown")
        assertTrue(ink > 0.1f, "the tunnel should be lit, had $ink")
        assertTrue(blown < 0.05f, "only the peaks turn white, had $blown blown out")
    }

    @Test
    fun oneRingIsBornAtTheFarPointForEverySixteenthNote() {
        val pipe = Pipe()
        var atStart = 0L
        RenderHarness.forEachFrameOf(pipe, 64, 36, 600, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Pulse, step) }) { _, step ->
            if (step == 120) atStart = pipe.ringsPassed
        }
        // 120 beats a minute is eight sixteenth notes a second, over the last eight seconds.
        val passed = pipe.ringsPassed - atStart
        println("pipe: $passed rings born at the far point in eight seconds at 120 beats a minute")
        assertTrue(abs(passed - 64L) <= 4L, "one ring born at the far point per sixteenth note is 64 in eight seconds, had $passed")
    }

    @Test
    fun theNewestSoundIsAtTheFarPointAndReachesTheMouthLater() {
        val width = 64
        val height = 36
        val pipe = Pipe()
        var far59 = 0f
        var far300 = 0f
        var far899 = 0f
        var newestAt70 = 0f
        var middleAt70 = 1f
        var floorAt899 = 0f
        var ceilingAt899 = 1f
        RenderHarness.forEachFrameOf(pipe, width, height, 900, VizPalette.Prism,
            source = { step ->
                // The Bass driver moves only the scalar bass, which Pipe does not read, so the bass bands
                // are raised by hand. The frame is built at the step the sound starts or later, so its band
                // array is fresh and safe to change. Before that every band is quiet.
                val frame = InjectedFrames.frame(VizDriver.Bands, step.coerceAtLeast(InjectedFrames.STEP_AT))
                for (band in frame.bandsRel.indices) {
                    val bass = step >= InjectedFrames.STEP_AT && band < frame.bandsRel.size / 4
                    frame.bandsRel[band] = if (bass) 1f else 0.05f
                }
                frame
            }) { bitmap, step ->
            if (step == 70) {
                newestAt70 = pipe.ringLight(0, 0)
                middleAt70 = pipe.ringLight(Pipe.RINGS / 2, 0)
            }
            if (step == 899) {
                floorAt899 = pipe.ringLight(Pipe.RINGS / 2, 0)
                ceilingAt899 = pipe.ringLight(Pipe.RINGS / 2, Pipe.CELLS - 1)
            }
            if (step == 59 || step == 300 || step == 899) {
                val pixels = IntArray(width * height)
                bitmap.readPixels(pixels)
                val far = nearAndFar(pixels, width, height)[1]
                when (step) {
                    59 -> far59 = far
                    300 -> far300 = far
                    else -> far899 = far
                }
            }
        }
        println("pipe: bass at step 70 newest ring floor $newestAt70, middle ring floor $middleAt70")
        println("pipe: bass at step 899 middle ring floor $floorAt899, ceiling $ceilingAt899")
        println("pipe: far region at steps 59, 300 and 899 is $far59, $far300, $far899, rings born ${pipe.ringsPassed}")
        assertTrue(newestAt70 > 0.5f, "the newest ring carries the bass at once, had $newestAt70")
        assertTrue(middleAt70 < 0.2f, "the bass has not yet reached the middle ring, had $middleAt70")
        assertTrue(floorAt899 > 0.5f, "the bass has reached the middle ring's floor, had $floorAt899")
        assertTrue(ceilingAt899 < 0.2f, "the bass does not light the ceiling, had $ceilingAt899")
        assertTrue((far300 - far59) < 0.5f * (far899 - far59),
            "the far region brightens later: $far59, $far300, $far899")
    }

    @Test
    fun aHeldLowToneLightsTheFloorAndNotTheCeiling() {
        val width = 320
        val height = 180
        var last = IntArray(0)
        RenderHarness.forEachFrameOf(Pipe(), width, height, 300, VizPalette.Prism,
            source = { step ->
                // Only fresh band arrays are changed: before the step the baseline is shared.
                val frame = InjectedFrames.frame(VizDriver.Bands, step.coerceAtLeast(InjectedFrames.STEP_AT))
                for (band in frame.bandsRel.indices) frame.bandsRel[band] = if (band < frame.bandsRel.size / 4) 1f else 0.05f
                frame
            }) { bitmap, step ->
            if (step == 299) {
                last = IntArray(width * height)
                bitmap.readPixels(last)
            }
        }
        fun mean(fromY: Int, toY: Int): Float {
            var sum = 0f
            var count = 0
            for (y in fromY until toY) for (x in width / 2 - 30 until width / 2 + 30) {
                sum += luma(last[y * width + x])
                count++
            }
            return sum / count
        }
        val floor = mean(height - 30, height)
        val ceiling = mean(0, 30)
        println("pipe: floor $floor, ceiling $ceiling")
        assertTrue(floor > 0.35f && floor > 4f * ceiling, "a held low tone lights the floor: floor $floor, ceiling $ceiling")
    }

    @Test
    fun aSectionStartsTheLaneGlideAndTheNewShapeOnTheSameFrame() {
        val pipe = Pipe()
        var glideAt = -1
        var morphAt = -1
        var settledAt = -1
        var laneAfterOneFrame = 0f
        RenderHarness.forEachFrameOf(pipe, 64, 36, 400, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Section, step) }) { _, step ->
            if (glideAt < 0 && (pipe.laneTargetX != 0f || pipe.laneTargetY != 0f)) glideAt = step
            if (morphAt < 0 && pipe.shapeMorph < 1f) morphAt = step
            if (morphAt >= 0 && settledAt < 0 && pipe.shapeMorph >= 1f) settledAt = step
            if (glideAt >= 0 && step == glideAt + 1) laneAfterOneFrame = abs(pipe.laneX) + abs(pipe.laneY)
        }
        val target = abs(pipe.laneTargetX) + abs(pipe.laneTargetY)
        println("pipe: lane glide at $glideAt, shape change from $morphAt to $settledAt, " +
            "lane $laneAfterOneFrame one frame later, target $target, final lane ${abs(pipe.laneX) + abs(pipe.laneY)}")
        assertTrue(glideAt >= InjectedFrames.STRUCTURE_STEP, "the lane glide waits for the section, came at $glideAt")
        assertEquals(glideAt, morphAt, "the lane glide and the shape change start on the same frame")
        assertTrue(pipe.shapeTo != 0, "the tube changes to another shape")
        assertTrue(settledAt > morphAt, "the shape turns over a cycle, not at once")
        assertTrue(laneAfterOneFrame < 0.5f * target,
            "the lane glides and does not cut: $laneAfterOneFrame one frame after the start, target $target")
    }

    @Test
    fun aDropOpensAChamberForAboutACycle() {
        val pipe = Pipe()
        val at = InjectedFrames.STRUCTURE_STEP
        var factorAtTwenty = 0f
        var formAtTwenty = ""
        var backAt = -1
        var factorLate = 0f
        var formLate = ""
        RenderHarness.forEachFrameOf(pipe, 64, 36, 600, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) }) { _, step ->
            if (step == at + 20) {
                factorAtTwenty = pipe.radiusFactor
                formAtTwenty = pipe.forms.form
            }
            if (backAt < 0 && step > at + 60 && pipe.radiusFactor < 1.1f) backAt = step
            if (step == at + 470) {
                factorLate = pipe.radiusFactor
                formLate = pipe.forms.form
            }
        }
        println("pipe: chamber factor $factorAtTwenty as $formAtTwenty at +20, back under 1.1 at +${backAt - at}, " +
            "factor $factorLate as $formLate at +470")
        assertTrue(factorAtTwenty >= 1.6f, "a drop widens the tube, had $factorAtTwenty")
        assertEquals("Chamber", formAtTwenty, "the form while the tube is wide")
        assertTrue(factorLate < 1.1f, "the chamber closes again, had $factorLate")
        assertTrue(formLate in listOf("Round", "Square", "Hex"), "the form after the chamber is a cross section, was $formLate")
    }

    @Test
    fun aBreakdownNarrowsToAThroat() {
        val pipe = Pipe()
        var factor = 1f
        var form = ""
        RenderHarness.forEachFrameOf(pipe, 64, 36, 200, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Breakdown, step) }) { _, step ->
            if (step == InjectedFrames.STRUCTURE_STEP + 60) {
                factor = pipe.radiusFactor
                form = pipe.forms.form
            }
        }
        println("pipe: throat factor $factor as $form")
        assertTrue(factor <= 0.7f, "a breakdown narrows the tube, had $factor")
        assertEquals("Throat", form, "the form while the tube is narrow")
    }

    @Test
    fun theFormIsPublishedAndMorphsOnTheDrumLoop() {
        val pipe = Pipe()
        val published: Visualization = pipe
        assertTrue(published.forms != null, "Pipe publishes its form")
        assertEquals("Round", pipe.forms.form, "the first form is the round tube")
        RenderHarness.forEachFrame(pipe, 64, 36, 3600, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ -> }
        println("pipe: ${pipe.forms.morphs} morphs and ${pipe.forms.births} births in a minute of drums, now ${pipe.forms.form}")
        assertTrue(pipe.forms.morphs >= 1, "a minute of drums morphs the tube, had ${pipe.forms.morphs}")
        assertTrue(pipe.forms.form in Pipe.FORMS, "the form is one of the named forms, was ${pipe.forms.form}")
    }

    @Test
    fun aBirthForksTheFarPointForOneCycle() {
        class Fork(var peak: Float = 0f, var firstAt: Int = -1, var cycleFrames: Int = 0, var returned: Boolean = false)

        fun run(frames: Int, forkAt: Int): Fork {
            val pipe = Pipe()
            val seen = Fork()
            RenderHarness.forEachFrame(pipe, 64, 36, frames, VizPalette.Prism, RenderHarness.Song.Lively) { _, step ->
                seen.peak = maxOf(seen.peak, pipe.forkValue)
                if (seen.firstAt < 0 && pipe.forkValue > 0.9f) {
                    seen.firstAt = step
                    seen.cycleFrames = (pipe.cycleSecondsValue * 60).toInt()
                }
                if (seen.firstAt in 0 until step && step <= seen.firstAt + (1.5f * seen.cycleFrames).toInt() &&
                    pipe.forkValue == 0f) seen.returned = true
                // This block runs after the draw, so a fork made here shows from the next frame.
                if (step == forkAt) pipe.fork()
            }
            return seen
        }
        var how = "born from the music"
        var seen = run(3600, -1)
        if (seen.peak <= 0.9f) {
            how = "made by hand at step 100"
            seen = run(600, 100)
        }
        println("pipe: fork $how, peak ${seen.peak} first above 0.9 at step ${seen.firstAt}, " +
            "cycle ${seen.cycleFrames} frames, back to zero in 1.5 cycles ${seen.returned}")
        assertTrue(seen.peak > 0.9f, "a birth forks the far point, peak ${seen.peak}")
        assertTrue(seen.returned, "the fork is gone within one and a half cycles")
    }

    @Test
    fun aDropRunsAtLightSpeedForAboutACycle() {
        val pipe = Pipe()
        var startedAt = -1
        var endedAt = -1
        RenderHarness.forEachFrameOf(pipe, 64, 36, 600, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) }) { _, step ->
            if (startedAt < 0 && pipe.streak > 0f) startedAt = step
            if (startedAt >= 0 && endedAt < 0 && pipe.streak == 0f) endedAt = step
        }
        println("pipe: light speed from $startedAt to $endedAt")
        assertTrue(startedAt in InjectedFrames.STRUCTURE_STEP..InjectedFrames.STRUCTURE_STEP + 2, "light speed starts on the drop, at $startedAt")
        assertTrue(endedAt > startedAt, "the streaks snap back into cells, but not at once")
    }

    @Test
    fun reducedMotionKeepsTheLaneCutAndTheLightSpeedSmall() {
        fun widest(driver: VizDriver, motion: Float, read: (Pipe) -> Float): Float {
            val pipe = Pipe()
            var widest = 0f
            RenderHarness.forEachFrameOf(pipe, 64, 36, 600, VizPalette.Prism,
                source = { step -> InjectedFrames.frame(driver, step) },
                beforeDraw = { it.motionScale = motion }) { _, _ -> widest = maxOf(widest, read(pipe)) }
            return widest
        }
        val fullCut = widest(VizDriver.Section, 1f) { maxOf(abs(it.laneX), abs(it.laneY)) }
        val calmCut = widest(VizDriver.Section, 0.15f) { maxOf(abs(it.laneX), abs(it.laneY)) }
        val fullRush = widest(VizDriver.Drop, 1f) { it.streak }
        val calmRush = widest(VizDriver.Drop, 0.15f) { it.streak }
        println("pipe: lane cut $fullCut to $calmCut, streak $fullRush to $calmRush")
        assertTrue(fullCut > 0.05f, "the fixture must cut the lane, had $fullCut")
        assertTrue(calmCut <= fullCut * 0.2f, "the lane cut barely shrank: $calmCut against $fullCut")
        assertTrue(fullRush > 0.9f, "the fixture must reach light speed, had $fullRush")
        assertTrue(calmRush <= 0.2f, "light speed streaks stayed strong: $calmRush")
    }

    @Test
    fun aSnareRingRushesInWithoutLightingTheNearestRings() {
        // The nearest rings cover much of the screen. A snare's ring that lit them for a frame made a
        // drum loop flash five times in its busiest second, because hats and chord stabs are heard as
        // snares too (#298). The ring now starts at the far point and arrives at the mouth within a beat.
        val width = 160
        val height = 90
        val first = InjectedFrames.HIT_STEPS.first()
        // Only the first hit counts: the next hit would restart the ring before it reaches the mouth.
        fun run(driver: VizDriver?): List<FloatArray> {
            val out = ArrayList<FloatArray>()
            RenderHarness.forEachFrameOf(Pipe(), width, height, first + 70, VizPalette.Prism,
                source = { step ->
                    InjectedFrames.frame(if (step < InjectedFrames.HIT_STEPS[1]) driver else null, step)
                }) { bitmap, _ ->
                val pixels = IntArray(width * height)
                bitmap.readPixels(pixels)
                out += nearAndFar(pixels, width, height)
            }
            return out
        }
        val still = run(null)
        val hit = run(VizDriver.BodyHit)
        var near = 0f
        var far = 0f
        var farAt = -1
        for (step in first until first + 70) {
            near = maxOf(near, hit[step][0] - still[step][0])
            if (hit[step][1] - still[step][1] > far) {
                far = hit[step][1] - still[step][1]
                farAt = step
            }
        }
        println("pipe: a snare brightens the nearest rings by $near and the far rings by $far, most at step $farAt")
        assertTrue(far > 0.05f, "the snare's ring should still rush in from the far point, brightened it by $far")
        assertTrue(near < 0.02f, "the snare's ring lit the nearest rings by $near")
    }

    @Test
    fun aRightHeavyTraceBendsTheTubeRight() {
        val quiet = FloatArray(256) { 0.3f * sin(it * TAU / 32) }
        val loud = FloatArray(256) { 0.6f * sin(it * TAU / 32) }
        val toRight = Pipe.balanceOf(quiet, loud)
        val toLeft = Pipe.balanceOf(loud, quiet)
        val even = Pipe.balanceOf(quiet, quiet.copyOf())
        println("pipe: balance right heavy $toRight, left heavy $toLeft, equal $even")
        assertTrue(toRight > 0f && toRight <= 1f, "a louder right channel balances right, had $toRight")
        assertTrue(toLeft < 0f && toLeft >= -1f, "a louder left channel balances left, had $toLeft")
        assertEquals(0f, even, "equal channels are in balance")

        // The Waveform driver puts its trace on the mono scope and the left channel only, so the frames
        // are left heavy and the tube bends to the left.
        val width = 160
        val height = 90
        val pipe = Pipe()
        var last = IntArray(0)
        RenderHarness.forEachFrameOf(pipe, width, height, 240, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Waveform, step) }) { bitmap, step ->
            if (step == 239) {
                last = IntArray(width * height)
                bitmap.readPixels(last)
            }
        }
        // The far point's star is the brightest light. Where several pixels share that, take their middle.
        var brightest = 0f
        for (pixel in last) brightest = maxOf(brightest, luma(pixel))
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (luma(last[y * width + x]) >= brightest) {
                sumX += x
                sumY += y
                count++
            }
        }
        val starX = sumX / count
        val starY = sumY / count
        println("pipe: bend x ${pipe.bendXValue}, y ${pipe.bendYValue}, brightest pixel at x $starX, y $starY ($count pixels at $brightest)")
        assertTrue(pipe.bendXValue < -0.1f, "a left heavy trace bends the tube left, had ${pipe.bendXValue}")
        assertTrue(starX < width / 2f - 2f, "the far point's star moved left of the middle, was at x $starX")
    }

    @Test
    fun theFarPointFollowsTheCentroid() {
        val pipe = Pipe()
        RenderHarness.forEachFrameOf(pipe, 64, 36, 240, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Timbre, step) }) { _, _ -> }
        println("pipe: bright music puts the far point at x ${pipe.bendXValue}, y ${pipe.bendYValue}")
        assertTrue(pipe.bendXValue > 0.1f, "bright music bends the tube right, had ${pipe.bendXValue}")
        assertTrue(pipe.bendYValue < -0.05f, "bright music lifts the far point, had ${pipe.bendYValue}")
    }

    @Test
    fun silenceStopsTheFlightAndKeepsTheTubeLit() {
        val pipe = Pipe()
        var previous: IntArray? = null
        var change = 0f
        var counted = 0
        var firstInk = 0f
        RenderHarness.forEachFrame(pipe, 160, 90, 180, VizPalette.Prism, RenderHarness.Song.Silence) { bitmap, step ->
            val pixels = IntArray(160 * 90)
            bitmap.readPixels(pixels)
            if (step == 0) firstInk = pixels.count { luma(it) > 0.02f } / pixels.size.toFloat()
            if (step >= 60) {
                previous?.let { old ->
                    change += old.indices.sumOf { abs(luma(old[it]) - luma(pixels[it])).toDouble() }.toFloat() / pixels.size
                    counted++
                }
            }
            previous = pixels
        }
        val mean = change / counted.coerceAtLeast(1)
        println("pipe: silence moved $mean a frame, first frame ink $firstInk")
        assertEquals(0L, pipe.ringsPassed, "no ring leaves the mouth in silence")
        assertTrue(mean < 0.0005f, "the wall stands still in silence, changed $mean")
        assertTrue(firstInk > 0.02f, "the first silent frame still shows the tube, had $firstInk")
    }

    /**
     * The mean light of the nearest rings, out towards the corners, and of the far rings around the
     * middle. Distances are in half heights from the middle, as the shader measures them.
     */
    private fun nearAndFar(pixels: IntArray, width: Int, height: Int): FloatArray {
        var near = 0f
        var nearCount = 0
        var far = 0f
        var farCount = 0
        for (y in 0 until height) for (x in 0 until width) {
            val dx = (x + 0.5f - width / 2f) / (height / 2f)
            val dy = (y + 0.5f - height / 2f) / (height / 2f)
            val distance = sqrt(dx * dx + dy * dy)
            val light = luma(pixels[y * width + x])
            if (distance > 1f) {
                near += light
                nearCount++
            } else if (distance in 0.25f..0.6f) {
                far += light
                farCount++
            }
        }
        return floatArrayOf(near / nearCount, far / farCount)
    }

    private fun luma(pixel: Int): Float =
        (0.2126f * (pixel shr 16 and 0xFF) + 0.7152f * (pixel shr 8 and 0xFF) + 0.0722f * (pixel and 0xFF)) / 255f
}

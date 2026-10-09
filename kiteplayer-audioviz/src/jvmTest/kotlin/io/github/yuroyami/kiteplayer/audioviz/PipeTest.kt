package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Pipe
import kotlin.math.abs
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
    fun aSectionCutsTheLaneAndStartsTheNewShapeOnTheSameFrame() {
        val pipe = Pipe()
        var cutAt = -1
        var morphAt = -1
        var settledAt = -1
        RenderHarness.forEachFrameOf(pipe, 64, 36, 400, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Section, step) }) { _, step ->
            if (cutAt < 0 && (pipe.laneX != 0f || pipe.laneY != 0f)) cutAt = step
            if (morphAt < 0 && pipe.shapeMorph < 1f) morphAt = step
            if (morphAt >= 0 && settledAt < 0 && pipe.shapeMorph >= 1f) settledAt = step
        }
        println("pipe: lane cut at $cutAt, shape change from $morphAt to $settledAt")
        assertTrue(cutAt >= InjectedFrames.STRUCTURE_STEP, "the lane cut waits for the section, came at $cutAt")
        assertEquals(cutAt, morphAt, "the lane cut and the shape change start on the same frame")
        assertTrue(pipe.shapeTo != 0, "the tube changes to another shape")
        assertTrue(settledAt > morphAt, "the shape turns over a cycle, not at once")
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

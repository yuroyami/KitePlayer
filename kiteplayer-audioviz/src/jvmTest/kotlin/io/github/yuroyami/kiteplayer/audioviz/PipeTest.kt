package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Pipe
import kotlin.math.abs
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
    fun oneRingLeavesTheMouthForEverySixteenthNote() {
        val pipe = Pipe()
        var atStart = 0L
        RenderHarness.forEachFrameOf(pipe, 64, 36, 600, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Pulse, step) }) { _, step ->
            if (step == 120) atStart = pipe.ringsPassed
        }
        // 120 beats a minute is eight sixteenth notes a second, over the last eight seconds.
        val passed = pipe.ringsPassed - atStart
        println("pipe: $passed rings in eight seconds at 120 beats a minute")
        assertTrue(abs(passed - 64L) <= 4L, "one ring per sixteenth note is 64 in eight seconds, had $passed")
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

    private fun luma(pixel: Int): Float =
        (0.2126f * (pixel shr 16 and 0xFF) + 0.7152f * (pixel shr 8 and 0xFF) + 0.0722f * (pixel and 0xFF)) / 255f
}

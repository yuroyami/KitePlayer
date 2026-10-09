package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.presets.IrisPetals
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Iris's petals: Kaleidoscope's stereo curve, whose size is the trace's own height under the shared gain. */
class IrisPetalsTest {

    private fun trace(amplitude: Float, cycles: Float, phase: Float = 0f): FloatArray =
        FloatArray(512) { amplitude * sin(2f * PI.toFloat() * cycles * it / 512f + phase) }

    @Test
    fun aLouderTraceGrowsThePetals() {
        val petals = IrisPetals()
        petals.read(trace(0.1f, 3f), trace(0.1f, 3f), gain = 1f)
        val quiet = petals.reach
        petals.read(trace(0.5f, 3f), trace(0.5f, 3f), gain = 1f)
        val loud = petals.reach
        println("petals: reach $quiet at a tenth, $loud at a half")
        assertTrue(loud > quiet * 3f, "five times the trace should reach far further: $quiet against $loud")
    }

    @Test
    fun theSharedGainScalesThePetalsAndNothingRescalesThem() {
        val petals = IrisPetals()
        petals.read(trace(0.2f, 3f), trace(0.2f, 3f, 0.4f), gain = 1f)
        val once = petals.reach
        petals.read(trace(0.2f, 3f), trace(0.2f, 3f, 0.4f), gain = 2f)
        assertEquals(once * 2f, petals.reach, 1e-4f)
    }

    @Test
    fun aTraceBeyondThePetalsLengthIsHeldAtIt() {
        val petals = IrisPetals()
        petals.read(trace(1f, 3f), trace(-1f, 3f), gain = 3f)
        assertEquals(1f, petals.reach, 1e-4f)
        for (index in 0 until IrisPetals.SEED) {
            val out = sqrt(petals.along[index] * petals.along[index] + petals.across[index] * petals.across[index])
            assertTrue(out <= 1.0001f, "point $index reaches $out petal lengths")
        }
    }

    @Test
    fun aMonoTraceLiesOnTheWedgeAxisAndIsCentred() {
        val petals = IrisPetals()
        petals.read(trace(0.4f, 3f), trace(0.4f, 3f), gain = 1f)
        assertTrue(petals.across.all { abs(it) < 1e-6f }, "a mono trace has no side")
        assertTrue(petals.tone.all { abs(it) < 1e-6f }, "a mono trace leans neither way")
    }

    @Test
    fun beforeAnythingIsHeardThePetalsAreASmallStar() {
        val petals = IrisPetals()
        assertTrue(petals.reach < 0.1f, "the first frame shows small petals, reach ${petals.reach}")
    }
}

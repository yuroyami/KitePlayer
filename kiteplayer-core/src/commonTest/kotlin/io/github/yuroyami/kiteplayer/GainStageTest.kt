package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.GainStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Volume, and the ramp that keeps it from clicking.
 *
 * Direct current at full scale is the input for all of this: the output then *is* the gain, sample by
 * sample, so the ramp can be read straight off the buffer instead of inferred.
 */
class GainStageTest {

    private fun stage(rate: Int = 48_000, channels: Int = 1) = GainStage(rate, channels)

    private fun dc(frames: Int, channels: Int = 1) = FloatArray(frames * channels) { 1f }

    @Test
    fun `unity gain leaves the samples alone`() {
        val gain = stage()
        val samples = FloatArray(64) { it.toFloat() }
        gain.apply(samples, 64)

        assertEquals(List(64) { it.toFloat() }, samples.toList())
    }

    @Test
    fun `the ramp default is five milliseconds`() {
        // MASTER_PLAN.md fixes this number, so it is asserted rather than assumed.
        val gain = GainStage(48_000, 1)
        assertEquals(240, gain.rampFrames, "5 ms at 48 kHz is 240 frames")
        assertEquals(1f / 240, gain.slopePerFrame)
    }

    @Test
    fun `a volume change never steps further than the slope`() {
        val gain = stage()
        val samples = dc(1024)
        gain.volume = 0f
        gain.apply(samples, 1024)

        // The first frame is measured against unity, which is where the stage started.
        assertTrue(
            abs(1f - samples[0]) <= gain.slopePerFrame + 1e-7f,
            "the first frame jumped to ${samples[0]} from 1.0",
        )
        for (i in 0 until 1023) {
            assertTrue(
                abs(samples[i + 1] - samples[i]) <= gain.slopePerFrame + 1e-7f,
                "frame $i to ${i + 1} stepped from ${samples[i]} to ${samples[i + 1]}",
            )
        }
    }

    @Test
    fun `the ramp arrives and then holds`() {
        val gain = stage()
        val samples = dc(1024)
        gain.volume = 0.25f
        gain.apply(samples, 1024)

        // A change of three quarters of the range takes three quarters of the ramp.
        assertEquals(0.25f, samples[gain.rampFrames], 1e-6f, "the ramp must be finished by its own length")
        assertEquals(0.25f, samples[1023], 1e-6f, "and it must not overshoot afterwards")
        assertEquals(0.25f, gain.current, 1e-6f)
    }

    @Test
    fun `a mute ramps down and back up`() {
        val gain = stage()
        gain.muted = true
        val down = dc(gain.rampFrames * 2)
        gain.apply(down, gain.rampFrames * 2)
        assertEquals(0f, down[down.size - 1], 1e-6f, "a mute reaches silence")
        assertTrue(down[0] > 0.9f, "and gets there over the ramp rather than at once")

        gain.muted = false
        val up = dc(gain.rampFrames * 2)
        gain.apply(up, gain.rampFrames * 2)
        assertEquals(1f, up[up.size - 1], 1e-6f, "unmuting comes back to the volume that was set")
        assertTrue(up[0] < 0.1f, "from where the mute left it")
    }

    @Test
    fun `a ramp continues across buffer boundaries`() {
        val gain = stage()
        gain.volume = 0f
        // Half the ramp, then the rest. The gain has to pick up where it left off, not restart.
        val first = dc(120)
        gain.apply(first, 120)
        assertEquals(0.5f, first[119], 0.01f, "halfway down after half the ramp")

        val second = dc(120)
        gain.apply(second, 120)
        assertEquals(0f, second[119], 1e-6f, "and the rest of the way on the next buffer")
    }

    @Test
    fun `every channel of a frame gets the same gain`() {
        val gain = stage(channels = 6)
        val samples = dc(64, channels = 6)
        gain.volume = 0.5f
        gain.apply(samples, 64)

        for (frame in 0 until 64) {
            val expected = samples[frame * 6]
            for (channel in 1 until 6) {
                assertEquals(expected, samples[frame * 6 + channel], "frame $frame channel $channel")
            }
        }
    }

    @Test
    fun `a longer ramp is a gentler slope`() {
        val slow = GainStage(48_000, 1, rampDuration = 20.milliseconds)
        assertEquals(960, slow.rampFrames)
        assertTrue(slow.slopePerFrame < stage().slopePerFrame)
    }

    @Test
    fun `a volume outside the range is refused`() {
        val gain = stage()
        assertFailsWith<IllegalArgumentException> { gain.volume = -0.1f }
        assertFailsWith<IllegalArgumentException> { gain.volume = 1.1f }
        assertFailsWith<IllegalArgumentException> { gain.volume = Float.NaN }
        assertEquals(1f, gain.volume, "a refused volume leaves the stage as it was")
    }
}

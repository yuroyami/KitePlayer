package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.LinearResampler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rate conversion, checked on the two properties that matter and one that is easy to get wrong.
 *
 * The ratio has to be exact, because a conversion that is one frame per buffer short runs the clock
 * away from the audio over a long file. The energy has to survive, because a conversion that halves it
 * is a volume bug. And a buffer boundary must not be a signal boundary, because that is the defect
 * this stage exists to avoid: the outputs have to be the same whether the input arrived in one piece
 * or in several.
 */
class LinearResamplerTest {

    private fun resampler(from: Int, to: Int, channels: Int = 1) =
        LinearResampler(sourceRate = from, targetRate = to, channels = channels)

    /** A 440 Hz tone, well below the point where linear interpolation starts to matter. */
    private fun tone(frames: Int, rate: Int, channels: Int = 1, startFrame: Int = 0) =
        FloatArray(frames * channels) { i ->
            val frame = startFrame + i / channels
            sin(2.0 * PI * 440.0 * frame / rate).toFloat()
        }

    private fun meanSquare(samples: FloatArray, values: Int): Double {
        var total = 0.0
        for (i in 0 until values) total += samples[i].toDouble() * samples[i]
        return total / values
    }

    @Test
    fun `441 frames at 44100 become 480 at 48000`() {
        val resampler = resampler(44_100, 48_000)
        val input = tone(441, 44_100)
        val output = FloatArray(resampler.outputCapacityFor(441))

        assertEquals(480, resampler.resample(input, 441, output), "10 ms in is 10 ms out")
    }

    @Test
    fun `the ratio holds buffer after buffer`() {
        val resampler = resampler(44_100, 48_000)
        val output = FloatArray(resampler.outputCapacityFor(441))
        // Ten buffers of exactly 10 ms. A conversion whose position drifts gives 479 or 481 somewhere
        // along here, and over an hour that is the audio clock walking away from the sound.
        repeat(10) { buffer ->
            val input = tone(441, 44_100, startFrame = buffer * 441)
            assertEquals(480, resampler.resample(input, 441, output), "buffer $buffer")
        }
    }

    @Test
    fun `energy survives the conversion`() {
        val resampler = resampler(44_100, 48_000)
        val input = tone(4410, 44_100)
        val output = FloatArray(resampler.outputCapacityFor(4410))
        val produced = resampler.resample(input, 4410, output)

        val before = meanSquare(input, 4410)
        val after = meanSquare(output, produced)
        assertTrue(
            abs(after - before) / before < 0.02,
            "mean square went from $before to $after, which is more than interpolation can explain",
        )
    }

    @Test
    fun `splitting the input across buffers changes nothing`() {
        val whole = resampler(44_100, 48_000)
        val split = resampler(44_100, 48_000)
        val input = tone(441, 44_100)

        val wholeOutput = FloatArray(whole.outputCapacityFor(441))
        val wholeFrames = whole.resample(input, 441, wholeOutput)

        // The same 441 frames, handed over in three pieces. The outputs that fall between the pieces
        // are the ones the carried frame exists for.
        val splitOutput = mutableListOf<Float>()
        val piece = FloatArray(split.outputCapacityFor(147))
        for (chunk in 0 until 3) {
            val part = FloatArray(147) { input[chunk * 147 + it] }
            val frames = split.resample(part, 147, piece)
            for (i in 0 until frames) splitOutput += piece[i]
        }

        assertEquals(wholeFrames, splitOutput.size, "the same input must produce the same frame count")
        for (i in 0 until wholeFrames) {
            assertEquals(
                wholeOutput[i],
                splitOutput[i],
                1e-6f,
                "output frame $i differs, so a buffer boundary left a step in the waveform",
            )
        }
    }

    @Test
    fun `there is no step at a buffer boundary`() {
        val resampler = resampler(44_100, 48_000)
        // A continuous tone in two halves. Restarting the read position at every buffer would put a
        // discontinuity exactly where the halves meet.
        val first = tone(441, 44_100)
        val second = tone(441, 44_100, startFrame = 441)
        val output = FloatArray(resampler.outputCapacityFor(441))

        val tail = mutableListOf<Float>()
        var produced = resampler.resample(first, 441, output)
        for (i in produced - 3 until produced) tail += output[i]
        produced = resampler.resample(second, 441, output)
        for (i in 0 until 3) tail += output[i]

        // At 440 Hz and 48 kHz one step of the tone is at most 0.06. Anything much larger is a splice.
        for (i in 0 until tail.size - 1) {
            assertTrue(
                abs(tail[i + 1] - tail[i]) < 0.1f,
                "the join goes $tail, which is a step rather than a tone",
            )
        }
    }

    @Test
    fun `channels stay in their own lanes`() {
        val resampler = resampler(44_100, 48_000, channels = 2)
        // Left is a constant one, right a constant minus one. Interleaving mistakes show up as
        // anything between the two.
        val input = FloatArray(441 * 2) { if (it % 2 == 0) 1f else -1f }
        val output = FloatArray(resampler.outputCapacityFor(441) * 2)
        val produced = resampler.resample(input, 441, output)

        for (frame in 0 until produced) {
            assertEquals(1f, output[frame * 2], 1e-6f, "left at frame $frame")
            assertEquals(-1f, output[frame * 2 + 1], 1e-6f, "right at frame $frame")
        }
    }

    @Test
    fun `downward conversion keeps its ratio too`() {
        val resampler = resampler(48_000, 44_100)
        val output = FloatArray(resampler.outputCapacityFor(480))
        repeat(4) { buffer ->
            val input = tone(480, 48_000, startFrame = buffer * 480)
            val produced = resampler.resample(input, 480, output)
            assertTrue(
                produced == 441 || produced == 442,
                "480 frames at 48 kHz is 441 at 44.1 kHz, and buffer $buffer gave $produced",
            )
        }
    }

    @Test
    fun `matching rates are a pass through`() {
        assertTrue(resampler(48_000, 48_000).isPassThrough)
        assertTrue(!resampler(44_100, 48_000).isPassThrough)
    }

    @Test
    fun `reset starts the position over`() {
        val resampler = resampler(44_100, 48_000)
        val input = tone(441, 44_100)
        val output = FloatArray(resampler.outputCapacityFor(441))
        val first = FloatArray(480)

        var produced = resampler.resample(input, 441, output)
        output.copyInto(first, 0, 0, produced)

        // A seek. Feeding the same buffer again must give the same answer, because nothing of the old
        // position may survive into the new one.
        resampler.reset()
        produced = resampler.resample(input, 441, output)
        assertEquals(480, produced)
        for (i in 0 until produced) {
            assertEquals(first[i], output[i], 1e-6f, "frame $i after the reset")
        }
    }
}

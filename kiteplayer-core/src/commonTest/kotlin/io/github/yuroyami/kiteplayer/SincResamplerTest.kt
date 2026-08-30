package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SincResampler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rate conversion, checked on what a rate conversion is FOR.
 *
 * Three properties are structural and were always required: the ratio has to be exact, or the clock
 * walks away from the audio over a long file; the energy has to survive, or it is a volume bug; and
 * a buffer boundary must not be a signal boundary, because that is the defect this stage exists to
 * avoid.
 *
 * The fourth is what this class was rewritten for. A tone above the target's Nyquist
 * cannot be represented at the target rate. It has to be REMOVED, not folded down into the middle of
 * the music as a new tone that was never in the recording. Linear interpolation folds it; a windowed
 * sinc kernel removes it, and the last test here is the measurement that tells the two apart.
 */
class SincResamplerTest {

    private fun resampler(from: Int, to: Int, channels: Int = 1) =
        SincResampler(sourceRate = from, targetRate = to, channels = channels)

    private fun tone(frames: Int, rate: Int, hz: Double = 440.0, channels: Int = 1, startFrame: Int = 0) =
        FloatArray(frames * channels) { i ->
            val frame = startFrame + i / channels
            sin(2.0 * PI * hz * frame / rate).toFloat()
        }

    private fun meanSquare(samples: FloatArray, values: Int): Double {
        var total = 0.0
        for (i in 0 until values) total += samples[i].toDouble() * samples[i]
        return total / values
    }

    /**
     * How much of [samples] sits at [hz], as an amplitude, by correlating against that frequency.
     *
     * One bin of a discrete Fourier transform, written out. That is all this needs: the question is
     * always "how much energy landed at exactly this frequency", never "what does the whole spectrum
     * look like".
     */
    private fun amplitudeAt(samples: FloatArray, frames: Int, rate: Int, hz: Double): Double {
        var real = 0.0
        var imaginary = 0.0
        for (i in 0 until frames) {
            val angle = 2.0 * PI * hz * i / rate
            real += samples[i] * kotlin.math.cos(angle)
            imaginary += samples[i] * sin(angle)
        }
        return 2.0 * sqrt(real * real + imaginary * imaginary) / frames
    }

    /** Feeds [input] in one go and returns everything that came out. */
    private fun runAll(resampler: SincResampler, input: FloatArray, frames: Int): FloatArray {
        val output = FloatArray(resampler.outputCapacityFor(frames))
        val produced = resampler.resample(input, frames, output)
        return output.copyOf(produced)
    }

    @Test
    fun `the ratio holds buffer after buffer`() {
        val resampler = resampler(44_100, 48_000)
        val output = FloatArray(resampler.outputCapacityFor(441))
        var produced = 0L
        // Twenty buffers of exactly 10 ms. The first is short by the kernel's lookahead, which is
        // inherent to every windowed filter, so the property is the TOTAL: 200 ms in, 200 ms out.
        repeat(20) { buffer ->
            val input = tone(441, 44_100, startFrame = buffer * 441)
            produced += resampler.resample(input, 441, output)
        }
        val expected = 20L * 480
        assertTrue(
            abs(produced - expected) <= SincResampler.TAPS,
            "200 ms at 44.1 kHz is $expected frames at 48 kHz, got $produced: a conversion whose " +
                "position drifts loses or gains frames steadily, and over an hour that is the audio " +
                "clock walking away from the sound",
        )
    }

    @Test
    fun `energy survives the conversion`() {
        val resampler = resampler(44_100, 48_000)
        val input = tone(44_100, 44_100)
        val produced = runAll(resampler, input, 44_100)
        // Past the kernel's fade-in at the very start, which is silence before the stream and not a
        // property of the conversion.
        val from = SincResampler.TAPS
        val after = meanSquare(produced.copyOfRange(from, produced.size), produced.size - from)
        val before = meanSquare(input, 44_100)
        assertTrue(
            abs(after - before) / before < 0.02,
            "mean square went from $before to $after, which is a level change and not a rate change",
        )
    }

    @Test
    fun `splitting the input across buffers changes nothing`() {
        val whole = resampler(44_100, 48_000)
        val split = resampler(44_100, 48_000)
        val input = tone(4410, 44_100)

        val wholeOutput = runAll(whole, input, 4410)

        // The same frames, handed over in thirty pieces. The outputs that fall between the pieces
        // are exactly the ones the running buffer exists for.
        val splitOutput = mutableListOf<Float>()
        val piece = FloatArray(split.outputCapacityFor(147))
        for (chunk in 0 until 30) {
            val part = FloatArray(147) { input[chunk * 147 + it] }
            val frames = split.resample(part, 147, piece)
            for (i in 0 until frames) splitOutput += piece[i]
        }

        assertEquals(wholeOutput.size, splitOutput.size, "the same input must produce the same frame count")
        for (i in wholeOutput.indices) {
            assertEquals(
                wholeOutput[i],
                splitOutput[i],
                1e-5f,
                "output frame $i differs, so a buffer boundary left a step in the waveform",
            )
        }
    }

    @Test
    fun `channels stay in their own lanes`() {
        val resampler = resampler(44_100, 48_000, channels = 2)
        // Left constant one, right constant minus one. An interleaving mistake shows up as anything
        // between the two; the kernel's own fade-in at the start is skipped.
        val input = FloatArray(4410 * 2) { if (it % 2 == 0) 1f else -1f }
        val output = FloatArray(resampler.outputCapacityFor(4410) * 2)
        val produced = resampler.resample(input, 4410, output)

        for (frame in SincResampler.TAPS until produced) {
            assertEquals(1f, output[frame * 2], 1e-3f, "left at frame $frame")
            assertEquals(-1f, output[frame * 2 + 1], 1e-3f, "right at frame $frame")
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
        val input = tone(4410, 44_100)
        val first = runAll(resampler, input, 4410)

        // A seek. Feeding the same buffer again must give the same answer, because nothing of the
        // old position may survive into the new one.
        resampler.reset()
        val second = runAll(resampler, input, 4410)

        assertEquals(first.size, second.size, "the reset must put the position back exactly")
        for (i in first.indices) {
            assertEquals(first[i], second[i], 1e-6f, "frame $i after the reset")
        }
    }

    @Test
    fun `the drain releases the tail the kernel was holding`() {
        val resampler = resampler(44_100, 48_000)
        val input = tone(4410, 44_100)
        val played = runAll(resampler, input, 4410).size
        val tail = FloatArray(resampler.drainCapacity())
        val drained = resampler.drain(tail)

        assertTrue(drained > 0, "the filter holds half a kernel at the end and it is real audio")
        val expected = 4410L * 48_000 / 44_100
        assertTrue(
            abs((played + drained) - expected) <= 2,
            "with the tail out, $expected frames were owed and ${played + drained} arrived",
        )
    }

    /**
     * The reason this class was rewritten.
     *
     * A 15 kHz tone cannot exist at 16 kHz, whose Nyquist is 8 kHz. A correct conversion removes
     * it. Linear interpolation attenuates it a little and FOLDS the rest down to 1 kHz, right in
     * the middle of speech, where it is plainly audible as a whistle that was never in the
     * recording. This measures the fold directly.
     *
     * Red by putting the old two-tap interpolation back: it leaves roughly a tenth of the tone's
     * amplitude sitting at 1 kHz, tens of decibels above the threshold below.
     */
    @Test
    fun `content above the new Nyquist is removed rather than folded into the music`() {
        val resampler = resampler(48_000, 16_000)
        val input = tone(48_000, 48_000, hz = 15_000.0)
        val produced = runAll(resampler, input, 48_000)
        // Past the fade-in, and a whole number of periods of the alias for a clean measurement.
        val from = SincResampler.TAPS
        val measured = produced.copyOfRange(from, produced.size)

        // 15 kHz sampled at 16 kHz folds to |15000 - 16000| = 1000 Hz.
        val alias = amplitudeAt(measured, measured.size, 16_000, 1_000.0)
        assertTrue(
            alias < 0.01,
            "a 15 kHz tone left $alias of amplitude at 1 kHz after converting to " +
                "16 kHz, which is the tone folded into the middle of the audible band rather than " +
                "filtered out",
        )
    }

    /** The other half: the filter must not eat what it is supposed to keep. */
    @Test
    fun `content the target can carry passes through at its own level`() {
        val resampler = resampler(48_000, 16_000)
        val input = tone(48_000, 48_000, hz = 1_000.0)
        val produced = runAll(resampler, input, 48_000)
        val from = SincResampler.TAPS
        val measured = produced.copyOfRange(from, produced.size)

        val kept = amplitudeAt(measured, measured.size, 16_000, 1_000.0)
        assertTrue(
            kept > 0.95,
            "a 1 kHz tone is far below the new Nyquist and must survive at full amplitude, got $kept",
        )
    }
}

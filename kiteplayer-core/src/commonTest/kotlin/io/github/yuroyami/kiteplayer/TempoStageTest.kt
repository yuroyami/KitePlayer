package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.TempoStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The tempo stage's promises, each tested where it can lie:
 *
 * - the RATIO promise: the output follows the input at the wanted speed, under any chunking,
 * - the PITCH promise: the stretch keeps pitch and the fold moves it with the rate,
 * - the SEAM promise: a change of speed or of pitch law never puts a step in the waveform,
 * - the ATTACK promise: a splice never plays an attack twice or drops it,
 * - the BYPASS promise: at 1.0 the stage is bit-exact and free.
 */
class TempoStageTest {

    private val rate = 48_000
    private val channels = 2

    /** Interleaved stereo sine at [frequency], [seconds] long; the right channel inverted when [opposite]. */
    private fun sine(frequency: Double, seconds: Double, opposite: Boolean = false): FloatArray {
        val frames = (seconds * rate).toInt()
        return FloatArray(frames * channels) { index ->
            val value = (0.4 * sin(2.0 * PI * frequency * (index / channels) / rate)).toFloat()
            if (opposite && index % channels == 1) -value else value
        }
    }

    /** A 1 ms burst every [spacingMs], the same on both channels. */
    private fun clicks(seconds: Double, spacingMs: Int): FloatArray {
        val out = FloatArray((seconds * rate).toInt() * channels)
        var at = rate / 20
        while (at + 48 < out.size / channels - rate / 20) {
            for (k in 0 until 48) {
                val value = (0.9 * exp(-k / 8.0) * if (k % 2 == 0) 1 else -1).toFloat()
                for (channel in 0 until channels) out[(at + k) * channels + channel] = value
            }
            at += rate * spacingMs / 1000
        }
        return out
    }

    /**
     * Feeds [input] in [chunkFrames] buffers, asking [speedAt] for the speed before each one, and
     * returns everything the stage made, the end-of-stream tail included.
     */
    private fun render(
        input: FloatArray,
        chunkFrames: Int = 1_536,
        stage: TempoStage = TempoStage(channels, rate),
        speedAt: (frame: Int) -> Double,
    ): FloatArray {
        val out = ArrayList<Float>()
        var offset = 0
        val total = input.size / channels
        val chunk = FloatArray(chunkFrames * channels)
        while (offset < total) {
            val frames = minOf(chunkFrames, total - offset)
            input.copyInto(chunk, 0, offset * channels, (offset + frames) * channels)
            stage.speed = speedAt(offset)
            val made = stage.process(chunk, frames)
            for (index in 0 until made * channels) out.add(stage.output[index])
            offset += frames
        }
        val tail = stage.finish()
        for (index in 0 until tail * channels) out.add(stage.output[index])
        return out.toFloatArray()
    }

    /** The largest step between two adjacent samples on any channel, ignoring a quarter second at each end. */
    private fun largestStep(samples: FloatArray): Float {
        val frames = samples.size / channels
        var largest = 0f
        for (frame in rate / 4 until frames - rate / 4) {
            for (channel in 0 until channels) {
                largest = maxOf(largest, abs(samples[frame * channels + channel] - samples[(frame - 1) * channels + channel]))
            }
        }
        return largest
    }

    /** Bursts above half scale on the first channel, closer than 10 ms counted once. */
    private fun countBursts(samples: FloatArray): Int {
        var count = 0
        var last = -rate
        for (frame in 0 until samples.size / channels) {
            if (abs(samples[frame * channels]) >= 0.45f) {
                if (frame - last > rate / 100) count++
                last = frame
            }
        }
        return count
    }

    @Test
    fun `finish emits the lookahead the end of a stream leaves stranded`() {
        for (speed in listOf(0.5, 1.25, 2.0, 4.0)) {
            val stage = TempoStage(channels, rate)
            stage.speed = speed
            // A tenth of a second, fed whole, so the stage is left mid-lookahead exactly as the
            // last buffer of a real stream leaves it.
            val input = sine(440.0, 0.1)
            val inputFrames = input.size / channels
            stage.process(input, inputFrames)
            val emittedBeforeFinish = stage.emittedFrames

            val tail = stage.finish()
            assertTrue(tail > 0, "at $speed the stage was holding audio that finish did not emit")
            assertEquals(emittedBeforeFinish + tail, stage.emittedFrames, "the tail counts as emitted at $speed")
            // The output reaches the last input frame. The runs report the ideal line, which the
            // blocks may lead by up to the 20 ms search range and a 20 ms block.
            val last = stage.pieceCount - 1
            val reached = stage.pieceSource(last) + (tail - stage.pieceStart(last)) * stage.pieceSlope(last)
            assertTrue(reached >= inputFrames - rate * 0.04, "at $speed the output stopped at input frame $reached of $inputFrames")
            assertEquals(0, stage.finish(), "a second finish has nothing left to give at $speed")
        }
    }

    @Test
    fun `the ratio holds at every supported speed under adversarial chunking`() {
        val random = Random(7)
        for (speed in listOf(0.25, 0.5, 0.8, 1.25, 1.5, 2.0, 3.0, 4.0)) {
            val stage = TempoStage(channels, rate)
            stage.speed = speed
            val input = sine(440.0, 5.0)
            var offset = 0
            val totalFrames = input.size / channels
            var reached = 0.0
            while (offset < totalFrames) {
                // Chunks from one frame to a fifth of a second, so no convenient buffer size
                // can hide a bookkeeping error.
                val frames = minOf(1 + random.nextInt(9_600), totalFrames - offset)
                val chunk = FloatArray(frames * channels)
                input.copyInto(chunk, 0, offset * channels, (offset + frames) * channels)
                val made = stage.process(chunk, frames)
                if (stage.pieceCount > 0) {
                    val last = stage.pieceCount - 1
                    reached = stage.pieceSource(last) + (made - stage.pieceStart(last)) * stage.pieceSlope(last)
                }
                offset += frames
            }
            val emitted = stage.emittedFrames.toDouble()
            assertTrue(reached > 4.0 * rate, "the stage must have reached most of five seconds at $speed")
            val achieved = reached / emitted
            assertTrue(
                abs(achieved - speed) / speed < 0.005,
                "at $speed the output followed the input at $achieved: $reached input frames for $emitted out",
            )
        }
    }

    @Test
    fun `doubling the tempo does not double the pitch`() {
        val produced = render(sine(440.0, 4.0), chunkFrames = 1_024) { 2.0 }
        assertTrue(produced.size / channels > rate, "two seconds in, one second out, give or take lookahead")
        // Rising zero crossings per second of OUTPUT. A tempo stage keeps them at the input's 440;
        // a resampler masquerading as one would show 880.
        var crossings = 0
        for (frame in rate / 2 until rate / 2 + rate) {
            if (produced[frame * channels] <= 0f && produced[(frame + 1) * channels] > 0f) crossings++
        }
        assertTrue(crossings in 430..450, "expected about 440 rising crossings per output second, counted $crossings")
    }

    @Test
    fun `without pitch correction the pitch moves with the rate`() {
        val stage = TempoStage(channels, rate).also { it.preservePitch = false }
        val produced = render(sine(440.0, 4.0), chunkFrames = 1_024, stage = stage) { 2.0 }
        var crossings = 0
        for (frame in rate / 2 until rate / 2 + rate) {
            if (produced[frame * channels] <= 0f && produced[(frame + 1) * channels] > 0f) crossings++
        }
        assertTrue(crossings in 870..890, "a fold at 2x plays 440 Hz as 880 Hz, counted $crossings")
        val frames = produced.size / channels
        assertTrue(frames in (2 * rate - 200)..(2 * rate + 200), "four seconds at 2x are two seconds, made $frames frames")
    }

    @Test
    fun `unity speed is bit-exact and passes through`() {
        val stage = TempoStage(channels, rate)
        val input = sine(313.0, 0.25)
        val frames = input.size / channels
        val produced = stage.process(input, frames)
        assertEquals(frames, produced)
        assertContentEquals(input, stage.output.copyOf(input.size))
        assertEquals(frames.toLong(), stage.receivedFrames)
        assertEquals(frames.toLong(), stage.emittedFrames)
        assertEquals(1, stage.pieceCount)
        assertEquals(1.0, stage.pieceSlope(0))
        assertTrue(stage.isBypassing, "nothing is held at 1.0")
    }

    @Test
    fun `live speed changes leave no step in the waveform`() {
        // Every change the drift corrector of a watch-together app makes, then the big ones, and
        // back to 1.0, all mid-stream at buffer boundaries. Opposite polarity on the right channel,
        // so a detector that sums the channels would see silence.
        val input = sine(220.0, 27.0, opposite = true)
        val output = render(input) { frame ->
            when (frame / rate) {
                in 0 until 3 -> 1.0
                in 3 until 6 -> 0.995
                in 6 until 9 -> 1.0
                in 9 until 12 -> 1.005
                in 12 until 15 -> 1.0
                in 15 until 18 -> 0.5
                in 18 until 21 -> 2.0
                in 21 until 24 -> 0.75
                else -> 1.0
            }
        }
        val natural = largestStep(input)
        val largest = largestStep(output)
        assertTrue(largest <= natural * 1.05f, "a change put a step of $largest in a tone whose largest step is $natural")
    }

    @Test
    fun `a pitch law change mid-stream leaves no step in the waveform`() {
        val stage = TempoStage(channels, rate)
        val input = sine(220.0, 12.0)
        val out = ArrayList<Float>()
        var offset = 0
        val chunk = FloatArray(1_536 * channels)
        while (offset < input.size / channels) {
            val frames = minOf(1_536, input.size / channels - offset)
            input.copyInto(chunk, 0, offset * channels, (offset + frames) * channels)
            stage.speed = 1.5
            stage.preservePitch = (offset / (2 * rate)) % 2 == 0
            val made = stage.process(chunk, frames)
            for (index in 0 until made * channels) out.add(stage.output[index])
            offset += frames
        }
        // Folding at 1.5 plays the tone at 330 Hz, so its steps grow by half; anything more is a seam.
        val natural = largestStep(input)
        val largest = largestStep(out.toFloatArray())
        assertTrue(largest <= natural * 1.55f, "a law change put a step of $largest where the tone allows ${natural * 1.5f}")
    }

    @Test
    fun `a small slowdown does not cut a smooth ramp`() {
        // There is no period to match in a ramp, so any splice that is not a true crossfade shows.
        // Ten seconds, because at 0.995 the stretch plays straight and splices about every four.
        val frames = rate * 10
        val step = 1f / frames
        val input = FloatArray(frames * channels) { index -> (index / channels) * step - 0.5f }
        val output = render(input) { 0.995 }
        assertTrue(output.size > input.size, "the slowdown must actually lengthen the ramp")
        assertTrue(largestStep(output) <= step * 3, "a splice cut the ramp: largest step ${largestStep(output)}, source step $step")
    }

    @Test
    fun `opposite stereo polarity changes no decision`() {
        val same = sine(220.0, 2.0)
        val opposite = sine(220.0, 2.0, opposite = true)
        for (speed in listOf(0.5, 0.995, 1.005, 2.0)) {
            val reference = render(same) { speed }
            val actual = render(opposite) { speed }
            assertEquals(reference.size, actual.size, "channel polarity changed the block schedule at $speed")
            for (frame in 0 until reference.size / channels) {
                val index = frame * channels
                assertEquals(reference[index], actual[index], "left channel changed at $speed, frame $frame")
                // Plus zero, so the silence after the end reads the same with either sign.
                assertEquals(-reference[index + 1] + 0f, actual[index + 1] + 0f, "right channel lost polarity at $speed, frame $frame")
            }
        }
    }

    @Test
    fun `every attack plays exactly once`() {
        // A burst every 100 ms: a slowdown must not repeat one and a speedup must not drop one.
        val input = clicks(5.0, spacingMs = 100)
        val expected = countBursts(input)
        for (speed in listOf(0.5, 0.75, 1.5, 2.0)) {
            val bursts = countBursts(render(input, chunkFrames = 1_024) { speed })
            assertEquals(expected, bursts, "at $speed the stretch played $bursts bursts for $expected")
        }
    }

    @Test
    fun `every channel moves with the same block`() {
        // Six channels: two copies, one inverted, three unrelated tones. Copies must stay copies.
        val six = 6
        val seconds = 3.0
        val frames = (seconds * rate).toInt()
        val input = FloatArray(frames * six) { index ->
            val frame = index / six
            val time = frame.toDouble() / rate
            when (index % six) {
                0, 1 -> (0.3 * sin(2 * PI * 196.0 * time)).toFloat()
                2 -> (-0.3 * sin(2 * PI * 196.0 * time)).toFloat()
                3 -> (0.2 * sin(2 * PI * 523.0 * time)).toFloat()
                4 -> (0.1 * sin(2 * PI * 61.0 * time)).toFloat()
                else -> (0.05 * sin(2 * PI * 3_000.0 * time)).toFloat()
            }
        }
        for (speed in listOf(0.6, 1.7)) {
            val stage = TempoStage(six, rate).also { it.speed = speed }
            val out = ArrayList<Float>()
            var offset = 0
            while (offset < frames) {
                val count = minOf(1_024, frames - offset)
                val made = stage.process(input.copyOfRange(offset * six, (offset + count) * six), count)
                for (index in 0 until made * six) out.add(stage.output[index])
                offset += count
            }
            for (frame in 0 until out.size / six) {
                val base = frame * six
                assertEquals(out[base], out[base + 1], "two copies parted at $speed, frame $frame")
                assertEquals(-out[base], out[base + 2], "an inverted copy parted at $speed, frame $frame")
            }
        }
    }

    @Test
    fun `reset drops what is held and the counters`() {
        val stage = TempoStage(channels, rate)
        stage.speed = 2.0
        render(sine(440.0, 1.0), stage = stage) { 2.0 }
        stage.reset()
        stage.speed = 1.0
        assertEquals(0L, stage.receivedFrames)
        assertEquals(0L, stage.emittedFrames)
        assertTrue(stage.isBypassing, "a reset stage holds nothing from before the seek")
    }

    @Test
    fun `speeds outside the documented range are refused`() {
        val stage = TempoStage(channels, rate)
        assertFailsWith<IllegalArgumentException> { stage.speed = 0.1 }
        assertFailsWith<IllegalArgumentException> { stage.speed = 5.0 }
        assertFailsWith<IllegalArgumentException> { stage.speed = Double.NaN }
    }
}

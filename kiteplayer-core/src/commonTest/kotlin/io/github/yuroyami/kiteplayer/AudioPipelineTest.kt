package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.AudioPipeline
import io.github.yuroyami.kiteplayer.internal.ChannelMixer
import io.github.yuroyami.kiteplayer.internal.MixLayout
import io.github.yuroyami.kiteplayer.internal.SincResampler
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three stages together: the right channels, at the right rate, at the right volume, in that
 * order.
 *
 * Each stage has its own tests. What is checked here is the composition, which is where the order
 * shows: a gain applied before the rate conversion would be smeared by the interpolation, and a rate
 * conversion applied before the downmix would cost four times the work on a 7.1 stream.
 */
class AudioPipelineTest {

    /** The coefficients unscaled and with the LFE kept, so a level here reads the matrix itself. */
    private val RAW_DOWNMIX = DownmixConfig(normalize = false, includeLfe = true)


    private fun format(channels: Int, rate: Int, mask: Long?) = AudioFormat(
        sampleRate = rate,
        channels = channels,
        sampleFormat = SampleFormat.F32,
        channelLayoutMask = mask,
    )

    private val surround51 = format(6, 48_000, MixLayout.Surround51Side.mask)
    private val stereoDevice = format(2, 48_000, null)

    /** One 5.1 frame with the centre channel alone at full scale. */
    private fun centreOnly(frames: Int) = FloatArray(frames * 6).also {
        for (frame in 0 until frames) it[frame * 6 + 2] = 1f
    }

    /**
     * P0-20. The pipeline's own end-of-stream exit. Only the tempo stage holds anything worth
     * recovering, and before this existed the only way out of it was a reset, which dropped it.
     */
    @Test
    fun `finish pushes out what the tempo stage was still holding`() {
        val pipeline = AudioPipeline(stereoDevice, stereoDevice)
        pipeline.speed = 2.0
        // A tenth of a second, which leaves the stage mid-lookahead exactly as a real last buffer does.
        val frames = stereoDevice.sampleRate / 10
        pipeline.process(FloatArray(frames * 2) { 0.5f }, frames)

        val tail = pipeline.finish()
        assertTrue(tail > 0, "the pipeline was holding audio that finish did not hand back")
        assertEquals(0, pipeline.finish(), "a second finish has nothing left to give")
    }

    @Test
    fun `a downmix with no rate change passes through the mixer and the gain only`() {
        // The RAW policy, so this reads the matrix and not the matrix plus the headroom scaling;
        // what the default policy does to the coefficients is ChannelMixerTest's subject.
        val pipeline = AudioPipeline(surround51, stereoDevice, downmix = RAW_DOWNMIX)
        val produced = pipeline.process(centreOnly(4), 4)

        assertEquals(4, produced, "the rates match, so the frame count cannot change")
        for (frame in 0 until 4) {
            assertEquals(ChannelMixer.MINUS_3_DB, pipeline.output[frame * 2], 1e-6f, "left at $frame")
            assertEquals(ChannelMixer.MINUS_3_DB, pipeline.output[frame * 2 + 1], 1e-6f, "right at $frame")
        }
    }

    @Test
    fun `a downmix and a rate change compose`() {
        val pipeline = AudioPipeline(
            format(6, 44_100, MixLayout.Surround51Side.mask),
            stereoDevice,
            downmix = RAW_DOWNMIX,
        )
        // Two buffers: the first is short by the resampler's lookahead, which every windowed
        // filter has, so the pair is what carries the ratio.
        val firstCount = pipeline.process(centreOnly(441), 441)
        val first = pipeline.output.copyOf(firstCount * 2)
        val steady = pipeline.process(centreOnly(441), 441)

        // The first buffer is short by the kernel's lookahead, once and permanently; every buffer
        // after it carries the exact ratio, which is the property that keeps the clock honest.
        assertEquals(480, steady, "441 frames at 44.1 kHz are 480 at 48 kHz once the filter is primed")
        // A constant through the filter is the same constant, downmixed once. Past the kernel's
        // fade-in at the very start, which is silence before the stream and not the mix.
        for (frame in SincResampler.TAPS until first.size / 2) {
            assertEquals(ChannelMixer.MINUS_3_DB, first[frame * 2], 1e-4f, "left at $frame")
            assertEquals(ChannelMixer.MINUS_3_DB, first[frame * 2 + 1], 1e-4f, "right at $frame")
        }
    }

    @Test
    fun `the gain is applied last`() {
        val pipeline = AudioPipeline(surround51, stereoDevice, downmix = RAW_DOWNMIX)
        pipeline.muted = true
        // Long enough for the ramp to finish inside the buffer.
        val produced = pipeline.process(centreOnly(1024), 1024)

        assertEquals(1024, produced)
        assertEquals(0f, pipeline.output[produced * 2 - 2], 1e-6f, "a mute reaches silence after the mix")
        assertEquals(0f, pipeline.output[produced * 2 - 1], 1e-6f)
        assertTrue(pipeline.output[0] > 0.5f, "and it got there over the ramp, not at once")
    }

    @Test
    fun `matching formats alias the input instead of copying it`() {
        val pipeline = AudioPipeline(stereoDevice, stereoDevice)
        val input = floatArrayOf(0.25f, -0.25f, 0.5f, -0.5f)
        val produced = pipeline.process(input, 2)

        assertEquals(2, produced)
        assertEquals(input.toList(), pipeline.output.toList().take(4), "a pass through changes nothing")
        /* The old never-hand-back pin is inverted: an all-pass-through pipeline now runs
         * ZERO copies, and the output deliberately ALIASES the caller's scratch, whose submit
         * contract consumes it before the next decode reuses it. */
        assertTrue(pipeline.output === input, "an identity pipeline must not copy the buffer")
    }

    @Test
    fun `a real mix still lands in the pipeline's own buffer`() {
        val pipeline = AudioPipeline(surround51, stereoDevice)
        val input = FloatArray(6 * 2) { 0.25f }
        pipeline.process(input, 2)
        assertTrue(pipeline.output !== input, "a downmix cannot alias six channels as two")
    }

    @Test
    fun `a format change is what rebuilding keys on`() {
        val pipeline = AudioPipeline(surround51, stereoDevice)
        pipeline.volume = 0.5f

        assertTrue(pipeline.matches(surround51))
        val changed = format(2, 48_000, MixLayout.Stereo.mask)
        assertTrue(!pipeline.matches(changed), "a decoder that changed format needs a new pipeline")

        val rebuilt = pipeline.rebuiltFor(changed)
        assertTrue(rebuilt.matches(changed))
        assertEquals(0.5f, rebuilt.volume, "a rebuild is not a volume change")
        assertEquals(stereoDevice, rebuilt.targetFormat, "the device did not change")
    }

    @Test
    fun `the layout warning arrives once per pipeline`() {
        val warnings = mutableListOf<PlaybackWarning>()
        val pipeline = AudioPipeline(format(6, 48_000, null), stereoDevice, onWarning = { warnings += it })

        repeat(4) { pipeline.process(centreOnly(64), 64) }

        assertEquals(1, warnings.size, "a guessed layout is reported when the pipeline is built, and once")
    }

    @Test
    fun `a reset drops what the rate conversion carried`() {
        val pipeline = AudioPipeline(format(1, 44_100, MixLayout.Mono.mask), format(1, 48_000, null))
        val input = FloatArray(441) { 1f }

        val first = pipeline.process(input, 441)
        val firstStart = pipeline.output[0]

        // Without the reset the next buffer continues from the carried frame. With it, the stage is
        // where it was at the start of the stream, which is what a seek means.
        pipeline.reset()
        assertEquals(first, pipeline.process(input, 441), "the same input gives the same count again")
        assertEquals(firstStart, pipeline.output[0], "and the same first frame")
    }

    @Test
    fun `an empty buffer is not a special case for the caller`() {
        val pipeline = AudioPipeline(surround51, stereoDevice)
        assertEquals(0, pipeline.process(FloatArray(0), 0))
    }

    @Test
    fun `preservePitch false plays speed by resampling and the pitch moves with the rate`() {
        val mono = format(1, 48_000, MixLayout.Mono.mask)
        val device = format(1, 48_000, null)
        // Four seconds of a 440 Hz sine, fed in decoder-sized buffers.
        val seconds = 4
        val input = FloatArray(48_000 * seconds) { index ->
            kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * index / 48_000.0).toFloat()
        }

        fun crossingsPerOutputSecond(preservePitch: Boolean): Pair<Int, Int> {
            val pipeline = AudioPipeline(mono, device, preservePitch = preservePitch)
            pipeline.speed = 2.0
            val produced = mutableListOf<Float>()
            var offset = 0
            while (offset < input.size) {
                val chunk = minOf(1024, input.size - offset)
                val emitted = pipeline.process(input.copyOfRange(offset, offset + chunk), chunk)
                for (i in 0 until emitted) produced += pipeline.output[i]
                offset += chunk
            }
            // Rising crossings inside one exact second of output, past the first half second
            // where a tempo stage's initial splices are densest. The same window the tempo
            // stage's own pitch test counts, so the two tests cannot disagree by arithmetic.
            var crossings = 0
            val start = 48_000 / 2
            for (i in start until minOf(start + 48_000, produced.size - 1)) {
                if (produced[i] <= 0f && produced[i + 1] > 0f) crossings++
            }
            return crossings to produced.size
        }

        // Both modes emit half the frames: speed is real on the same time axis either way.
        val (resampledPitch, resampledFrames) = crossingsPerOutputSecond(preservePitch = false)
        val (preservedPitch, preservedFrames) = crossingsPerOutputSecond(preservePitch = true)
        val half = 48_000 * seconds / 2
        assertTrue(
            resampledFrames in (half - 4_800)..(half + 4_800),
            "2x by resampling must emit about half the frames, emitted $resampledFrames of ${input.size}",
        )
        assertTrue(
            preservedFrames in (half - 4_800)..(half + 4_800),
            "2x by tempo must emit about half the frames, emitted $preservedFrames of ${input.size}",
        )

        // The whole point of the switch: the resampled route doubles the pitch, the tempo route keeps it.
        assertTrue(
            resampledPitch in 792..968,
            "at 2x without pitch correction 440 Hz becomes about 880, counted $resampledPitch",
        )
        assertTrue(
            preservedPitch in 396..484,
            "at 2x with pitch correction 440 Hz stays about 440, counted $preservedPitch",
        )
    }
}

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.AudioPipeline
import io.github.yuroyami.kiteplayer.internal.ChannelMixer
import io.github.yuroyami.kiteplayer.internal.MixLayout
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

    @Test
    fun `a downmix with no rate change passes through the mixer and the gain only`() {
        val pipeline = AudioPipeline(surround51, stereoDevice)
        val produced = pipeline.process(centreOnly(4), 4)

        assertEquals(4, produced, "the rates match, so the frame count cannot change")
        for (frame in 0 until 4) {
            assertEquals(ChannelMixer.MINUS_3_DB, pipeline.output[frame * 2], 1e-6f, "left at $frame")
            assertEquals(ChannelMixer.MINUS_3_DB, pipeline.output[frame * 2 + 1], 1e-6f, "right at $frame")
        }
    }

    @Test
    fun `a downmix and a rate change compose`() {
        val pipeline = AudioPipeline(format(6, 44_100, MixLayout.Surround51Side.mask), stereoDevice)
        val produced = pipeline.process(centreOnly(441), 441)

        assertEquals(480, produced, "441 frames at 44.1 kHz are 480 at 48 kHz")
        // Constant input through a linear interpolation is the same constant, downmixed once.
        for (frame in 1 until produced) {
            assertEquals(ChannelMixer.MINUS_3_DB, pipeline.output[frame * 2], 1e-6f, "left at $frame")
            assertEquals(ChannelMixer.MINUS_3_DB, pipeline.output[frame * 2 + 1], 1e-6f, "right at $frame")
        }
    }

    @Test
    fun `the gain is applied last`() {
        val pipeline = AudioPipeline(surround51, stereoDevice)
        pipeline.muted = true
        // Long enough for the ramp to finish inside the buffer.
        val produced = pipeline.process(centreOnly(1024), 1024)

        assertEquals(1024, produced)
        assertEquals(0f, pipeline.output[produced * 2 - 2], 1e-6f, "a mute reaches silence after the mix")
        assertEquals(0f, pipeline.output[produced * 2 - 1], 1e-6f)
        assertTrue(pipeline.output[0] > 0.5f, "and it got there over the ramp, not at once")
    }

    @Test
    fun `matching formats still produce the pipeline's own buffer`() {
        val pipeline = AudioPipeline(stereoDevice, stereoDevice)
        val input = floatArrayOf(0.25f, -0.25f, 0.5f, -0.5f)
        val produced = pipeline.process(input, 2)

        assertEquals(2, produced)
        assertEquals(input.toList(), pipeline.output.toList().take(4), "a pass through changes nothing")
        assertTrue(pipeline.output !== input, "the caller's array is never handed back")
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

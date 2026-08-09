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
}

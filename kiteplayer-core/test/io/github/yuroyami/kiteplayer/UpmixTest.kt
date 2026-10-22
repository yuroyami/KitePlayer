@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.ChannelMixer
import io.github.yuroyami.kiteplayer.internal.MixLayout
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * [UpmixMode.Surround]: a mono or stereo source filling a surround device with the matrix that enum
 * documents, and [UpmixMode.Off] leaving it as it was.
 */
class UpmixTest {

    private fun format(channels: Int, mask: Long?) =
        AudioFormat(sampleRate = RATE, channels = channels, sampleFormat = SampleFormat.F32, channelLayoutMask = mask)

    private val stereo = format(2, MixLayout.Stereo.mask)
    private val mono = format(1, MixLayout.Mono.mask)

    /** Mixes [frames] frames of one constant [frame] and returns the last output frame. */
    private fun settled(mixer: ChannelMixer, frame: FloatArray, targetChannels: Int, frames: Int = RATE / 10): List<Float> {
        val input = FloatArray(frames * frame.size) { frame[it % frame.size] }
        val output = FloatArray(frames * targetChannels)
        mixer.mix(input, output, frames)
        return output.copyOfRange((frames - 1) * targetChannels, frames * targetChannels).toList()
    }

    private fun assertNear(expected: List<Float>, actual: List<Float>, what: String) {
        assertEquals(expected.size, actual.size, what)
        for (i in expected.indices) {
            assertTrue(abs(expected[i] - actual[i]) <= 1e-3f, "$what: channel $i is ${actual[i]}, expected ${expected[i]} ($actual)")
        }
    }

    @Test
    fun `a stereo block on a 5 point 1 device gets the documented matrix`() {
        val mixer = ChannelMixer(stereo, format(6, MixLayout.Surround51.mask), upmix = UpmixMode.Surround)
        // FL FR FC LFE BL BR. The low-frequency channel is read after its filter settled on DC.
        assertNear(
            listOf(0.8f, 0.2f, 0.70710678f, 0.5f, 0.3f, -0.3f),
            settled(mixer, floatArrayOf(0.8f, 0.2f), 6),
            "stereo (0.8, 0.2) on 5.1",
        )
    }

    @Test
    fun `a mono block fills all six channels`() {
        val mixer = ChannelMixer(mono, format(6, MixLayout.Surround51.mask), upmix = UpmixMode.Surround)
        assertNear(
            listOf(0.6f, 0.6f, 0.6f * 0.70710678f, 0.3f, 0.21f, 0.21f),
            settled(mixer, floatArrayOf(0.6f), 6),
            "mono 0.6 on 5.1",
        )
    }

    @Test
    fun `a 7 point 1 device plays the difference from both surround pairs`() {
        val mixer = ChannelMixer(stereo, format(8, MixLayout.Surround71.mask), upmix = UpmixMode.Surround)
        // FL FR FC LFE BL BR SL SR.
        assertNear(
            listOf(0.8f, 0.2f, 0.70710678f, 0.5f, 0.3f, -0.3f, 0.3f, -0.3f),
            settled(mixer, floatArrayOf(0.8f, 0.2f), 8),
            "stereo (0.8, 0.2) on 7.1",
        )
    }

    @Test
    fun `a six channel device that named no mask gets side surrounds`() {
        // A count-only device is taken as the conventional layout for its count: FL FR FC LFE SL SR.
        val mixer = ChannelMixer(stereo, format(6, mask = null), upmix = UpmixMode.Surround)
        assertNear(
            listOf(0.8f, 0.2f, 0.70710678f, 0.5f, 0.3f, -0.3f),
            settled(mixer, floatArrayOf(0.8f, 0.2f), 6),
            "stereo on a six channel device without a mask",
        )
    }

    @Test
    fun `the low frequency channel is low passed and the centre is not`() {
        val mixer = ChannelMixer(stereo, format(6, MixLayout.Surround51.mask), upmix = UpmixMode.Surround)
        val frames = RATE / 10
        val input = FloatArray(frames * 2) { i -> (0.5 * sin(2.0 * PI * 5_000.0 * (i / 2) / RATE)).toFloat() }
        val output = FloatArray(frames * 6)
        mixer.mix(input, output, frames)
        fun rms(channel: Int): Double {
            var sum = 0.0
            for (frame in frames / 2 until frames) sum += output[frame * 6 + channel].toDouble().let { it * it }
            return sqrt(sum / (frames - frames / 2))
        }
        val centre = rms(2)
        val lfe = rms(3)
        // A 5 kHz tone in both channels: the centre carries 0.707 of the sum, the LFE almost none of it.
        assertTrue(centre > 0.4, "the centre carried $centre of a 5 kHz tone")
        assertTrue(lfe < 0.002, "a 5 kHz tone reached the low-frequency channel at $lfe, so it was not low passed")
    }

    @Test
    fun `reset forgets what the low pass carried`() {
        val mixer = ChannelMixer(stereo, format(6, MixLayout.Surround51.mask), upmix = UpmixMode.Surround)
        settled(mixer, floatArrayOf(0.8f, 0.2f), 6)
        val afterDc = FloatArray(6)
        mixer.mix(floatArrayOf(0f, 0f), afterDc, frames = 1)
        assertTrue(afterDc[3] > 0.4f, "without a reset the filter should still ring from the DC, read ${afterDc[3]}")

        settled(mixer, floatArrayOf(0.8f, 0.2f), 6)
        mixer.reset()
        val afterReset = FloatArray(6)
        mixer.mix(floatArrayOf(0f, 0f), afterReset, frames = 1)
        assertEquals(0f, afterReset[3], "the low-frequency channel carried audio across a reset")
    }

    @Test
    fun `a six channel source is never upmixed`() {
        val source = format(6, MixLayout.Surround51.mask)
        val target = format(8, MixLayout.Surround71.mask)
        val frame = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
        val off = settled(ChannelMixer(source, target, upmix = UpmixMode.Off), frame, 8, frames = 4)
        val surround = settled(ChannelMixer(source, target, upmix = UpmixMode.Surround), frame, 8, frames = 4)
        assertEquals(off, surround, "a surround source must mix the same with and without the upmix")
    }

    @Test
    fun `off leaves the four other channels at zero`() {
        val mixer = ChannelMixer(stereo, format(6, MixLayout.Surround51.mask), upmix = UpmixMode.Off)
        // Near rather than equal: on JavaScript a Float is a 64-bit number and the array holds 32 bits (#537).
        assertNear(listOf(0.8f, 0.2f, 0f, 0f, 0f, 0f), settled(mixer, floatArrayOf(0.8f, 0.2f), 6, frames = 4), "off")
    }

    @Test
    fun `off is the default`() {
        assertEquals(UpmixMode.Off, AudioConfig().upmix)
        val mixer = ChannelMixer(stereo, format(6, MixLayout.Surround51.mask))
        assertNear(listOf(0.8f, 0.2f, 0f, 0f, 0f, 0f), settled(mixer, floatArrayOf(0.8f, 0.2f), 6, frames = 4), "the default")
    }

    @Test
    fun `the setting reaches the pipeline from AudioConfig`() = runTest {
        suspend fun centrePeak(upmix: UpmixMode): Float {
            val harness = CoreHarness(
                this,
                script = MediaScript(durationUs = 2_000_000),
                config = PlayerConfig(audio = AudioConfig(upmix = upmix)),
                sinkAccepts = AudioFormat(RATE, 6, SampleFormat.F32, channelLayoutMask = MixLayout.Surround51.mask),
            )
            harness.openWithRenderer()
            harness.core.play()
            harness.run(500.milliseconds)
            val front = harness.sink.channelPeak(0)
            val centre = harness.sink.channelPeak(2)
            harness.close()
            assertTrue(front > 0f, "the device heard nothing from the front, so the comparison is empty")
            return centre
        }
        assertEquals(0f, centrePeak(UpmixMode.Off), "without the upmix the centre stays silent")
        assertTrue(centrePeak(UpmixMode.Surround) > 0f, "with the upmix the centre stayed silent")
    }

    private companion object {
        const val RATE = 48_000
    }
}

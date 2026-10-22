package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.ChannelMixer
import io.github.yuroyami.kiteplayer.internal.MixLayout
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dialogue level (#442): a 5.1 centre folded into two speakers is raised or lowered, a centre a
 * 5.1 device keeps is not, and a change while playing ramps.
 */
class DialogueLevelTest {

    private fun format(channels: Int, mask: Long?) = AudioFormat(48_000, channels, SampleFormat.F32, channelLayoutMask = mask)

    private val surround = format(6, MixLayout.Surround51.mask)

    /** One frame of 5.1 with speech in the centre and an effect in the front left. */
    private fun frame(speech: Float, effect: Float) = floatArrayOf(effect, 0f, speech, 0f, 0f, 0f)

    private fun mix(mixer: ChannelMixer, input: FloatArray, frames: Int, channels: Int): FloatArray =
        FloatArray(frames * channels).also { mixer.mix(input, it, frames) }

    @Test
    fun aFoldedCentreIsRaisedAndLoweredAndTheFrontsAreNot() {
        val stereo = format(2, null)
        val raised = ChannelMixer(surround, stereo).apply { setDialogueLevel(6f) }
        val out = mix(raised, frame(speech = 0.1f, effect = 0f), 1, 2)
        val sixDb = 10f.pow(6f / 20f)
        assertEquals(0.1f * ChannelMixer.MINUS_3_DB * sixDb, out[0], 1e-6f)
        assertEquals(out[0], out[1], 1e-6f)
        val effect = mix(raised, frame(speech = 0f, effect = 0.5f), 1, 2)
        assertEquals(0.5f, effect[0], 1e-6f, "the dialogue level moved a front speaker")
        val lowered = ChannelMixer(surround, stereo).apply { setDialogueLevel(-12f) }
        assertEquals(0.1f * ChannelMixer.MINUS_3_DB * 10f.pow(-12f / 20f), mix(lowered, frame(0.1f, 0f), 1, 2)[0], 1e-6f)
    }

    @Test
    fun aCentreSpeakerKeepsItsCentre() {
        val mixer = ChannelMixer(format(8, MixLayout.Surround71.mask), surround).apply { setDialogueLevel(9f) }
        val input = FloatArray(8).also { it[2] = 0.2f }
        assertEquals(0.2f, mix(mixer, input, 1, 6)[2], 1e-6f, "a centre the device has was scaled")
    }

    @Test
    fun zeroLeavesTheDownmixAsItWas() {
        val stereo = format(2, null)
        val plain = ChannelMixer(surround, stereo)
        val zero = ChannelMixer(surround, stereo).apply { setDialogueLevel(0f) }
        val input = FloatArray(6 * 64) { (it % 7) / 10f }
        assertTrue(mix(plain, input, 64, 2).contentEquals(mix(zero, input, 64, 2)))
    }

    @Test
    fun aChangeWhilePlayingRampsOverTenMilliseconds() {
        val mixer = ChannelMixer(surround, format(2, null))
        val frames = 1_000
        val input = FloatArray(6 * frames).also { samples -> for (at in 0 until frames) samples[at * 6 + 2] = 0.1f }
        mix(mixer, input, frames, 2)
        mixer.setDialogueLevel(12f)
        val out = mix(mixer, input, frames, 2)
        val left = FloatArray(frames) { out[it * 2] }
        val steps = left.toList().zipWithNext { a, b -> abs(b - a) }
        val whole = 0.1f * ChannelMixer.MINUS_3_DB * (10f.pow(12f / 20f) - 1f)
        assertTrue(steps.max() < whole / 100, "the level jumped by ${steps.max()} of $whole in one frame")
        assertEquals(0.1f * ChannelMixer.MINUS_3_DB * 10f.pow(12f / 20f), left.last(), 1e-6f, "the ramp never arrived")
        assertTrue(left[479] == left.last() && left[478] < left.last(), "the ramp did not take 480 frames")
    }
}

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.ChannelMixer
import io.github.yuroyami.kiteplayer.internal.MixLayout
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** -3 dB, the level centre, LFE and surrounds enter a stereo downmix at. */
private const val M: Float = ChannelMixer.MINUS_3_DB

private const val TOLERANCE: Float = 1e-6f

private fun format(channels: Int, mask: Long?, rate: Int = 48_000) = AudioFormat(
    sampleRate = rate,
    channels = channels,
    sampleFormat = SampleFormat.F32,
    channelLayoutMask = mask,
)

/** Stereo as a device reports it: two channels, and no mask, because the count is the authority. */
private val stereoDevice = format(channels = 2, mask = null)

/**
 * One named layout and the stereo pair each of its channels must produce on its own.
 *
 * The pairs are the matrix rows read down instead of across, which is the form an impulse test needs:
 * put 1 in one channel, and this is the whole answer.
 */
private class LayoutCase(val layout: MixLayout, val expected: List<Pair<Float, Float>>) {
    init {
        require(expected.size == layout.channels) {
            "${layout.label} has ${layout.channels} channels and ${expected.size} expected pairs"
        }
    }
}

/**
 * The downmix, one channel at a time.
 *
 * An impulse on a single input channel is the strongest test a matrix has: it reads one column of the
 * matrix directly, so a coefficient in the wrong place cannot hide behind another channel's
 * contribution. Every named layout is here with the ITU coefficients written out by hand, which is
 * the point: the expectations are the specification and not a second copy of the implementation.
 */
class ChannelMixerTest {

    private val cases = listOf(
        // FC into both, at unity. A mono source is not quieter than a stereo one.
        LayoutCase(MixLayout.Mono, listOf(1f to 1f)),
        // FL FR
        LayoutCase(MixLayout.Stereo, listOf(1f to 0f, 0f to 1f)),
        // FL FR LFE
        LayoutCase(MixLayout.Surround21, listOf(1f to 0f, 0f to 1f, M to M)),
        // FL FR BL BR
        LayoutCase(MixLayout.Quad, listOf(1f to 0f, 0f to 1f, M to 0f, 0f to M)),
        // FL FR FC BL BR
        LayoutCase(MixLayout.Surround50, listOf(1f to 0f, 0f to 1f, M to M, M to 0f, 0f to M)),
        // FL FR FC LFE BL BR
        LayoutCase(
            MixLayout.Surround51,
            listOf(1f to 0f, 0f to 1f, M to M, M to M, M to 0f, 0f to M),
        ),
        // FL FR FC LFE SL SR: the same matrix as 5.1 back, and a layout of its own all the same
        LayoutCase(
            MixLayout.Surround51Side,
            listOf(1f to 0f, 0f to 1f, M to M, M to M, M to 0f, 0f to M),
        ),
        // FL FR FC LFE BC SL SR
        LayoutCase(
            MixLayout.Surround61,
            listOf(1f to 0f, 0f to 1f, M to M, M to M, M to M, M to 0f, 0f to M),
        ),
        // FL FR FC LFE BL BR SL SR
        LayoutCase(
            MixLayout.Surround71,
            listOf(1f to 0f, 0f to 1f, M to M, M to M, M to 0f, 0f to M, M to 0f, 0f to M),
        ),
    )

    @Test
    fun `an impulse on each channel of each named layout mixes to its exact stereo pair`() {
        for (case in cases) {
            val warnings = mutableListOf<PlaybackWarning>()
            val mixer = ChannelMixer(
                source = format(case.layout.channels, case.layout.mask),
                target = stereoDevice,
                onWarning = { warnings += it },
            )

            for (channel in 0 until case.layout.channels) {
                val input = FloatArray(case.layout.channels)
                input[channel] = 1f
                val output = FloatArray(2)
                mixer.mix(input, output, frames = 1)

                val (left, right) = case.expected[channel]
                assertEquals(left, output[0], TOLERANCE, "${case.layout.label} channel $channel into left")
                assertEquals(right, output[1], TOLERANCE, "${case.layout.label} channel $channel into right")
            }

            assertEquals(
                emptyList(),
                warnings,
                "${case.layout.label} came from a mask this build models, so nothing is being guessed",
            )
        }
    }

    @Test
    fun `every named layout is covered by a case`() {
        assertEquals(
            MixLayout.entries.toSet(),
            cases.map { it.layout }.toSet(),
            "a layout with no impulse case is a matrix nothing checks",
        )
    }

    @Test
    fun `interleaving is preserved across several frames`() {
        val mixer = ChannelMixer(
            source = format(6, MixLayout.Surround51Side.mask),
            target = stereoDevice,
        )
        // Two frames of 5.1: the first is centre only, the second is front left only.
        val input = floatArrayOf(
            0f, 0f, 1f, 0f, 0f, 0f,
            1f, 0f, 0f, 0f, 0f, 0f,
        )
        val output = FloatArray(4)
        mixer.mix(input, output, frames = 2)

        assertEquals(M, output[0], TOLERANCE)
        assertEquals(M, output[1], TOLERANCE)
        assertEquals(1f, output[2], TOLERANCE)
        assertEquals(0f, output[3], TOLERANCE)
    }

    @Test
    fun `an unknown mask passes the first channels through and warns once`() {
        val warnings = mutableListOf<PlaybackWarning>()
        // A four channel layout of front left, front right, front centre and low frequency. Real, and
        // not one of the named nine, so there is no matrix for it.
        val mixer = ChannelMixer(
            source = format(4, mask = 0xFL),
            target = stereoDevice,
            onWarning = { warnings += it },
        )

        val output = FloatArray(2)
        mixer.mix(floatArrayOf(1f, 2f, 3f, 4f), output, frames = 1)

        assertEquals(1f, output[0], TOLERANCE, "the first channel passes through")
        assertEquals(2f, output[1], TOLERANCE, "and the second, in source order")
        assertEquals(1, warnings.size, "the fallback is reported exactly once")
        val warning = warnings.single() as PlaybackWarning.ChannelLayoutUnknown
        assertEquals(4, warning.channels)
        assertTrue(warning.message.contains("0xf"), "the message must name the mask: ${warning.message}")
    }

    @Test
    fun `a mask that disagrees with the channel count is not trusted`() {
        val warnings = mutableListOf<PlaybackWarning>()
        // The 5.1 side mask over four channels. One of the two is wrong and there is no way to know
        // which, so mixing by the mask would put the surrounds in whatever the last channels are.
        ChannelMixer(
            source = format(4, mask = MixLayout.Surround51Side.mask),
            target = stereoDevice,
            onWarning = { warnings += it },
        )

        assertEquals(1, warnings.size)
        assertTrue(
            warnings.single().message.contains("0x60f"),
            "the message must name the mask it refused: ${warnings.single().message}",
        )
    }

    @Test
    fun `no mask at all is guessed from the channel count and reported`() {
        val warnings = mutableListOf<PlaybackWarning>()
        val mixer = ChannelMixer(
            source = format(6, mask = null),
            target = stereoDevice,
            onWarning = { warnings += it },
        )

        // Centre only. A guess that fell back to the first two channels would give silence here,
        // which is how a downmix loses the dialogue.
        val output = FloatArray(2)
        mixer.mix(floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f), output, frames = 1)

        assertEquals(M, output[0], TOLERANCE)
        assertEquals(M, output[1], TOLERANCE)
        assertEquals(1, warnings.size, "a guess is reported, once")
        assertTrue(
            warnings.single().message.contains("5.1 side"),
            "the message must say what was assumed: ${warnings.single().message}",
        )
    }

    @Test
    fun `matching channel counts copy instead of mixing`() {
        val warnings = mutableListOf<PlaybackWarning>()
        val mixer = ChannelMixer(
            source = format(6, MixLayout.Surround51.mask),
            target = format(6, mask = null),
            onWarning = { warnings += it },
        )

        val input = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val output = FloatArray(6)
        mixer.mix(input, output, frames = 1)

        assertTrue(mixer.isPassThrough, "six channels into six is a copy")
        assertEquals(input.toList(), output.toList(), "a device that took the layout gets it unchanged")
        assertEquals(emptyList(), warnings, "nothing was guessed and nothing was dropped")
    }

    @Test
    fun `a wider target leaves the channels it has no source for silent`() {
        val warnings = mutableListOf<PlaybackWarning>()
        val mixer = ChannelMixer(
            source = format(2, MixLayout.Stereo.mask),
            target = format(4, mask = null),
            onWarning = { warnings += it },
        )

        val output = FloatArray(4) { Float.NaN }
        mixer.mix(floatArrayOf(1f, 2f), output, frames = 1)

        assertEquals(listOf(1f, 2f, 0f, 0f), output.toList())
        assertEquals(1, warnings.size, "channels the source cannot fill are a degradation, so it is said")
    }
}

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.ChannelMixer
import io.github.yuroyami.kiteplayer.internal.MixLayout
import kotlin.math.abs
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** -3 dB, the level centre, LFE and surrounds enter a stereo downmix at. */
private const val M: Float = ChannelMixer.MINUS_3_DB

private const val TOLERANCE: Float = 1e-6f

/**
 * The coefficients exactly as the specification writes them: LFE folded in, nothing scaled.
 *
 * The impulse cases below are the ITU matrix written out by hand, so they are checked against the
 * matrix itself rather than against the matrix plus a policy. What the DEFAULT policy then does to
 * those coefficients has its own tests further down (audit 15.3.2).
 */
private val RAW = DownmixConfig(normalize = false, includeLfe = true)

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

    // ── The fold to a smaller surround target (SOL-P8 remainder). ──────────────────

    @Test
    fun `seven point one folds into five point one instead of truncating the sides away`() {
        // 7.1: FL FR FC LFE BL BR SL SR into 5.1: FL FR FC LFE BL BR. Before the fold existed
        // this was a truncating pass-through and the side surrounds vanished entirely.
        val mixer = ChannelMixer(
            source = format(channels = 8, mask = MixLayout.Surround71.mask),
            target = format(channels = 6, mask = MixLayout.Surround51.mask),
            policy = RAW,
        )
        assertTrue(!mixer.isPassThrough, "8 into 6 must mix, not truncate")

        // An impulse on SL (channel 6) must land in BL (output 4), and nowhere else.
        val input = FloatArray(8).also { it[6] = 1f }
        val output = FloatArray(6)
        mixer.mix(input, output, 1)
        assertTrue(output[4] > 0.5f, "side-left content never reached the back-left speaker: ${output.toList()}")
        for (channel in listOf(0, 1, 2, 3, 5)) {
            assertTrue(abs(output[channel]) < TOLERANCE, "side-left leaked into channel $channel")
        }

        // Direct speakers pass straight through: an impulse on FC stays FC.
        val centre = FloatArray(8).also { it[2] = 1f }
        val centreOut = FloatArray(6)
        mixer.mix(centre, centreOut, 1)
        assertEquals(1f, centreOut[2], TOLERANCE, "the centre channel did not pass through the fold")
    }

    @Test
    fun `normalize bounds a folded output exactly as it bounds the stereo downmix`() {
        // The DEFAULT policy deliberately does not normalize (FFmpeg and mpv parity; the float
        // pipeline cannot clip). When a caller asks for the bound, the fold must honour it the
        // same way the stereo matrix does.
        val mixer = ChannelMixer(
            source = format(channels = 8, mask = MixLayout.Surround71.mask),
            target = format(channels = 6, mask = MixLayout.Surround51.mask),
            policy = DownmixConfig(normalize = true),
        )
        // Full scale on every input channel: no output sample may exceed full scale.
        val input = FloatArray(8) { 1f }
        val output = FloatArray(6)
        mixer.mix(input, output, 1)
        for (channel in 0 until 6) {
            assertTrue(output[channel] <= 1f + TOLERANCE, "channel $channel clipped: ${output[channel]}")
        }
        // And the unnormalized default really does sum the merged surrounds, like FFmpeg.
        val raw = ChannelMixer(
            source = format(channels = 8, mask = MixLayout.Surround71.mask),
            target = format(channels = 6, mask = MixLayout.Surround51.mask),
        )
        val rawOut = FloatArray(6)
        raw.mix(input, rawOut, 1)
        assertEquals(2f, rawOut[4], TOLERANCE, "the default fold must keep FFmpeg's unnormalized sum")
    }

    @Test
    fun `a six channel device with no mask still receives the fold`() {
        // Android reports 5.1 by count when no mask reaches the format; the conventional layout
        // for six channels must carry the fold rather than fall back to truncation.
        val mixer = ChannelMixer(
            source = format(channels = 8, mask = MixLayout.Surround71.mask),
            target = format(channels = 6, mask = null),
        )
        assertTrue(!mixer.isPassThrough, "an unmasked six channel device truncated the sides away")
    }

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
                policy = RAW,
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
            policy = RAW,
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
            policy = RAW,
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

    // ---------------------------------------------------------------------------------------------
    // The policy the default applies to those coefficients (audit 15.3.2).
    // ---------------------------------------------------------------------------------------------

    /**
     * What normalisation is for, and what turning it on buys.
     *
     * Every channel of a 5.1 mix at full scale at once is the worst case a real film reaches on a
     * loud transient. Without normalisation the front row sums past full scale and a device taking
     * integer samples clips it into a crackle. Red by ignoring `policy.normalize` in `downmix`.
     */
    @Test
    fun `normalisation makes it impossible to drive the output past full scale`() {
        val mixer = ChannelMixer(
            source = format(6, MixLayout.Surround51.mask),
            target = stereoDevice,
            policy = DownmixConfig(normalize = true),
        )
        val output = FloatArray(2)
        mixer.mix(FloatArray(6) { 1f }, output, frames = 1)

        assertTrue(
            abs(output[0]) <= 1f && abs(output[1]) <= 1f,
            "a downmix that can exceed full scale clips at the device: got ${output.toList()}",
        )
    }

    /**
     * And the DEFAULT, which is FFmpeg's: louder, matching the reference recordings the whole
     * audio path is compared against, and able to exceed full scale on a loud passage.
     */
    @Test
    fun `the default follows ffmpeg and can exceed full scale`() {
        val mixer = ChannelMixer(
            source = format(6, MixLayout.Surround51.mask),
            target = stereoDevice,
        )
        val output = FloatArray(2)
        mixer.mix(FloatArray(6) { 1f }, output, frames = 1)

        assertTrue(
            output[0] > 1f,
            "the unnormalised matrix is the louder mpv behaviour and it must still be reachable",
        )
    }

    /**
     * The LFE channel is a subwoofer feed, not a bass instrument. Red by defaulting includeLfe on.
     */
    @Test
    fun `the low frequency effects channel is left out of a stereo downmix by default`() {
        val mixer = ChannelMixer(format(6, MixLayout.Surround51.mask), stereoDevice)
        val output = FloatArray(2)
        // LFE alone, at full scale.
        mixer.mix(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f), output, frames = 1)

        assertEquals(0f, output[0], TOLERANCE, "LFE must not reach the left speaker")
        assertEquals(0f, output[1], TOLERANCE, "nor the right one")
    }

    /** And it is a policy and not a rule: a caller that wants the content back can have it. */
    @Test
    fun `the low frequency effects channel can be folded in on request`() {
        val mixer = ChannelMixer(
            format(6, MixLayout.Surround51.mask),
            stereoDevice,
            policy = DownmixConfig(normalize = false, includeLfe = true),
        )
        val output = FloatArray(2)
        mixer.mix(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f), output, frames = 1)

        assertEquals(M, output[0], TOLERANCE)
        assertEquals(M, output[1], TOLERANCE)
    }

    // ---------------------------------------------------------------------------------------------
    // Equal counts, different speakers (audit 15.3.4).
    // ---------------------------------------------------------------------------------------------

    /**
     * Equal counts are matched by SPEAKER, and a device the source cannot fill exactly still gets
     * the surround content rather than silence.
     *
     * 5.1 with side surrounds into a device wanting 5.1 with back surrounds is the pair the audit
     * names. Both carry the same six speakers under two names and, as it happens, in the same
     * native order, so the right answer here is still the content unmoved: what must NOT happen is
     * the surround channels going silent because the target's speaker names did not match.
     *
     * Red by removing the side-and-back equivalence from `reorder`, which drops both surrounds.
     */
    @Test
    fun `equal counts are matched by speaker and never leave the surrounds silent`() {
        val mixer = ChannelMixer(
            source = format(6, MixLayout.Surround51Side.mask),
            target = format(6, MixLayout.Surround51.mask),
        )
        val output = FloatArray(6)
        mixer.mix(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), output, frames = 1)

        assertEquals(
            listOf(1f, 2f, 3f, 4f, 5f, 6f),
            output.toList(),
            "side and back surrounds carry the same content under two names; a device with back " +
                "speakers must play the side mix, not go quiet",
        )
    }

    /**
     * A device that named NO layout is still a straight copy, which is what a count-only sink is.
     *
     * Worth pinning because it is the common case and because the reorder must not fire on a guess:
     * the mixer keys on what both sides actually declared, and a device that declared nothing has
     * declared nothing.
     */
    @Test
    fun `equal counts with a device that named no layout stay a copy`() {
        val mixer = ChannelMixer(
            source = format(6, MixLayout.Surround51Side.mask),
            target = format(6, mask = null),
        )
        assertTrue(mixer.isIdentity, "there is nothing to reorder against")
    }

    /** A device that named the SAME layout is still a straight copy, with no matrix at all. */
    @Test
    fun `equal counts with the same layout stay a copy`() {
        val mixer = ChannelMixer(
            source = format(6, MixLayout.Surround51.mask),
            target = format(6, MixLayout.Surround51.mask),
        )
        assertTrue(mixer.isIdentity, "the same six speakers in the same order need no work at all")
    }
}

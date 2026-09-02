package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.ReplayGainTags
import io.github.yuroyami.kiteplayer.internal.parseGainDb
import io.github.yuroyami.kiteplayer.internal.parsePeak
import io.github.yuroyami.kiteplayer.internal.parseReplayGain
import io.github.yuroyami.kiteplayer.internal.replayGainLinear
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the loudness the encoder already measured.
 *
 * Almost every music file carries one: FLAC and Ogg write `REPLAYGAIN_TRACK_GAIN` as a Vorbis
 * comment, MP3 writes it as an ID3v2 TXXX frame, and Opus writes `R128_TRACK_GAIN` instead, in a
 * different unit against a different reference. The player read none of them, so a quiet album
 * played quiet and a loud one blew the listener's ears off, and every application that cared had
 * to parse container tags itself.
 *
 * The parsing is pure and lives here. The clamp is the part worth reading twice: a positive gain
 * applied to a file whose peak is already near full scale would clip, so the gain is reduced until
 * the peak fits under the ceiling. That is why a tag can ask for +6 dB and get less.
 */
class ReplayGainTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.001f, message: String = "") {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$message expected $expected, was $actual",
        )
    }

    @Test
    fun `a ReplayGain value parses with its unit and its sign`() {
        assertClose(-6.54f, parseGainDb("-6.54 dB", r128 = false)!!)
        assertClose(2f, parseGainDb("+2 dB", r128 = false)!!)
        assertClose(-6.54f, parseGainDb("-6.54", r128 = false)!!)
        // Encoders disagree about spacing and case, and every one of these is real.
        assertClose(-9.2f, parseGainDb("-9.20dB", r128 = false)!!)
        assertClose(-9.2f, parseGainDb("  -9.20 DB  ", r128 = false)!!)
    }

    @Test
    fun `an R128 value is a fixed-point integer against a different reference`() {
        // Q7.8, so 256 units is 1 dB, and it targets -23 LUFS where ReplayGain targets -18, so a
        // conversion that forgets the 5 dB plays every Opus file five decibels too quiet.
        assertClose(5f, parseGainDb("0", r128 = true)!!)
        assertClose(0f, parseGainDb("-1280", r128 = true)!!)
        assertClose(6f, parseGainDb("256", r128 = true)!!)
    }

    @Test
    fun `nonsense parses to nothing rather than to zero`() {
        // Zero would be a gain of exactly unity, which is indistinguishable from a real measurement
        // saying the file needs no change. Null lets the caller fall back deliberately.
        assertNull(parseGainDb("", r128 = false))
        assertNull(parseGainDb("loud", r128 = false))
        assertNull(parseGainDb("dB", r128 = false))
        assertNull(parseGainDb("NaN", r128 = false))
        assertNull(parseGainDb("Infinity", r128 = false))
        assertNull(parseGainDb("2.5", r128 = true))
    }

    @Test
    fun `a peak parses as a plain number and refuses the impossible`() {
        assertClose(0.999969f, parsePeak("0.999969")!!)
        assertClose(1.14f, parsePeak("1.14")!!)
        assertNull(parsePeak("-0.5"))
        assertNull(parsePeak("nonsense"))
    }

    @Test
    fun `tags are found whatever case the container wrote them in`() {
        val tags = parseReplayGain(
            container = mapOf(
                "REPLAYGAIN_TRACK_GAIN" to "-6.54 dB",
                "replaygain_album_gain" to "-7.00 dB",
                "ReplayGain_Track_Peak" to "0.98",
            ),
            stream = emptyMap(),
        )
        assertClose(-6.54f, tags.trackGainDb!!)
        assertClose(-7f, tags.albumGainDb!!)
        assertClose(0.98f, tags.trackPeak!!)
        assertNull(tags.albumPeak)
    }

    @Test
    fun `a stream's own tag wins over the container's`() {
        // FFmpeg surfaces ReplayGain on the audio stream for some containers and on the format for
        // others. When both carry one the stream is the more specific answer.
        val tags = parseReplayGain(
            container = mapOf("REPLAYGAIN_TRACK_GAIN" to "-3 dB"),
            stream = mapOf("REPLAYGAIN_TRACK_GAIN" to "-9 dB"),
        )
        assertClose(-9f, tags.trackGainDb!!)
    }

    @Test
    fun `the mode picks which measurement is used`() {
        val tags = ReplayGainTags(trackGainDb = -6f, albumGainDb = -12f, trackPeak = null, albumPeak = null)
        assertClose(
            0.5012f,
            replayGainLinear(tags, ReplayGainMode.Track, preampDb = 0f, fallbackDb = 0f, ceiling = 1f),
        )
        assertClose(
            0.2512f,
            replayGainLinear(tags, ReplayGainMode.Album, preampDb = 0f, fallbackDb = 0f, ceiling = 1f),
        )
        assertEquals(
            1f,
            replayGainLinear(tags, ReplayGainMode.Off, preampDb = 0f, fallbackDb = 0f, ceiling = 1f),
        )
    }

    @Test
    fun `a missing measurement falls back to the album's and then to the configured value`() {
        val trackOnly = ReplayGainTags(trackGainDb = -6f, albumGainDb = null, trackPeak = null, albumPeak = null)
        assertClose(
            0.5012f,
            replayGainLinear(trackOnly, ReplayGainMode.Album, preampDb = 0f, fallbackDb = 0f, ceiling = 1f),
        )
        val nothing = ReplayGainTags(null, null, null, null)
        assertClose(
            0.5012f,
            replayGainLinear(nothing, ReplayGainMode.Track, preampDb = 0f, fallbackDb = -6f, ceiling = 1f),
        )
    }

    @Test
    fun `the preamp is added to whatever the tag asked for`() {
        val tags = ReplayGainTags(trackGainDb = -6f, albumGainDb = null, trackPeak = null, albumPeak = null)
        assertClose(
            1f,
            replayGainLinear(tags, ReplayGainMode.Track, preampDb = 6f, fallbackDb = 0f, ceiling = 1f),
        )
    }

    @Test
    fun `a gain that would clip is reduced until the peak fits`() {
        // The whole reason peaks are in the standard. +6 dB over a file peaking at 0.9 would reach
        // 1.79, so the gain is cut to exactly what leaves the peak at the ceiling.
        val tags = ReplayGainTags(trackGainDb = 6f, albumGainDb = null, trackPeak = 0.9f, albumPeak = null)
        val gain = replayGainLinear(tags, ReplayGainMode.Track, preampDb = 0f, fallbackDb = 0f, ceiling = 1f)
        assertClose(1f / 0.9f, gain)
        assertClose(1f, gain * 0.9f, message = "the clamped gain must land the peak exactly on the ceiling:")
    }

    @Test
    fun `a raised ceiling lets more of the gain through`() {
        // With a boost allowed, the same file may use the headroom the ceiling opens.
        val tags = ReplayGainTags(trackGainDb = 6f, albumGainDb = null, trackPeak = 0.9f, albumPeak = null)
        val gain = replayGainLinear(tags, ReplayGainMode.Track, preampDb = 0f, fallbackDb = 0f, ceiling = 2f)
        assertClose(1.9953f, gain, tolerance = 0.001f)
    }

    @Test
    fun `an attenuation is never held back by a peak`() {
        // Turning something down cannot clip, so the clamp must not interfere with it.
        val tags = ReplayGainTags(trackGainDb = -12f, albumGainDb = null, trackPeak = 1f, albumPeak = null)
        assertClose(
            0.2512f,
            replayGainLinear(tags, ReplayGainMode.Track, preampDb = 0f, fallbackDb = 0f, ceiling = 1f),
        )
    }

    @Test
    fun `album mode clamps against the album's own peak`() {
        val tags = ReplayGainTags(trackGainDb = null, albumGainDb = 6f, trackPeak = 0.5f, albumPeak = 0.95f)
        val gain = replayGainLinear(tags, ReplayGainMode.Album, preampDb = 0f, fallbackDb = 0f, ceiling = 1f)
        assertClose(1f / 0.95f, gain)
    }
}

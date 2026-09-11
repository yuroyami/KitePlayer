package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.AudioTap
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The feed between the player's tap and the picture: sound in, analyses out, each stamped with the
 * time its audio plays at, and nothing stale carried across a seek.
 */
class AudioVizFeedTest {

    @Test
    fun `sound becomes analyses stamped with the time it plays at`() {
        val feed = AudioVizFeed()
        feed.hear(fromMicros = 5_000_000, seconds = 0.5f)
        val newest = assertNotNull(feed.timeline.newest(), "half a second of sound gave no analysis")
        // Half a second in, less the half window each analysis is centred on.
        assertTrue(newest.ptsMicros in 5_400_000..5_500_000, "the newest analysis is stamped ${newest.ptsMicros}")
        assertTrue(newest.level > 0f, "the analysis of a drum loop is silent")
        assertNotNull(feed.timeline.at(5_250_000), "a moment inside what was heard has no analysis")
    }

    @Test
    fun `a discontinuity drops what came before even when the new sound is close in time`() {
        val feed = AudioVizFeed()
        feed.hear(fromMicros = 0, seconds = 1f)
        feed.onDiscontinuity()
        // A seek ten milliseconds back: too small a step for the timestamps alone to give it away.
        feed.hear(fromMicros = 990_000, seconds = 0.3f)
        assertNull(feed.timeline.at(500_000), "an analysis from before the seek survived it")
        val newest = assertNotNull(feed.timeline.newest())
        assertTrue(newest.ptsMicros in 990_000..1_300_000, "the newest analysis is stamped ${newest.ptsMicros}")
    }

    @Test
    fun `a jump in time starts the analysis again even with no discontinuity`() {
        val feed = AudioVizFeed()
        feed.hear(fromMicros = 0, seconds = 1f)
        // A loop back to the start, with no flush between the two passes.
        feed.hear(fromMicros = 0, seconds = 0.5f)
        val newest = assertNotNull(feed.timeline.newest())
        assertTrue(newest.ptsMicros in 400_000..500_000, "the newest analysis is stamped ${newest.ptsMicros}")
    }

    @Test
    fun `a new sample rate gets an analysis that counts time at that rate`() {
        val feed = AudioVizFeed()
        feed.hear(fromMicros = 0, seconds = 0.5f, sampleRate = 44_100)
        feed.hear(fromMicros = 500_000, seconds = 1f, sampleRate = 48_000)
        val newest = assertNotNull(feed.timeline.newest())
        // Still counting at 44.1 kHz, the last analysis would be stamped near 1.56 s.
        assertTrue(newest.ptsMicros in 1_400_000..1_500_000, "the newest analysis is stamped ${newest.ptsMicros}")
    }
}

/**
 * Hands [seconds] of the drum loop to the tap the way the player does: stereo blocks of 1024
 * frames in one reused array, each stamped with the time it plays at.
 */
internal fun AudioTap.hear(fromMicros: Long, seconds: Float, sampleRate: Int = 44_100) {
    val mono = SyntheticSong.drumLoop(seconds, sampleRate = sampleRate)
    val format = AudioFormat(sampleRate, 2, SampleFormat.F32)
    val block = FloatArray(BLOCK_FRAMES * 2)
    var start = 0
    while (start < mono.size) {
        val frames = minOf(BLOCK_FRAMES, mono.size - start)
        for (i in 0 until frames) {
            block[2 * i] = mono[start + i]
            block[2 * i + 1] = mono[start + i]
        }
        onAudio(Pts(fromMicros + start * 1_000_000L / sampleRate), block, frames, format)
        start += frames
    }
}

private const val BLOCK_FRAMES = 1024

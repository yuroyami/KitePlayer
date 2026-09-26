package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * An audio-only precise seek lands on the first sample it keeps, not on the start of the decoded
 * buffer that straddles the target (#292).
 *
 * The buffers are 1024 frames at 48 kHz, 21.333 ms each, so a 10 ms target sits inside the first
 * one and 480 of its frames come before the target.
 */
class AudioSeekLandingTest {

    private class Block(val pts: Pts, val frames: Int)

    /** Every block after the last cut, which is what the latest seek produced. */
    private class RecordingTap : AudioTap {
        val blocks = mutableListOf<Block>()

        override fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
            blocks += Block(pts, frames)
        }

        override fun onDiscontinuity() {
            blocks.clear()
        }
    }

    @Test
    fun aPreciseAudioSeekLandsOnTheFirstSampleItKeeps() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 2_000_000, hasVideo = false, sampleRate = 48_000, audioBufferFrames = 1024),
        )
        val tap = RecordingTap()
        KitePlayer(harness.core).attachAudioTap(tap)
        harness.openWithRenderer()
        harness.run(100.milliseconds)

        val result = harness.core.seek(Pts(10_000), SeekMode.Precise)
        val landed = assertIs<SeekResult.Applied>(result, "the seek did not apply: $result").landedAt
        assertEquals(10_000L, landed.micros, "the seek reported the start of the untrimmed buffer")
        harness.run(100.milliseconds)

        val first = assertNotNull(tap.blocks.firstOrNull(), "no audio reached the tap after the seek")
        assertEquals(10_000L, first.pts.micros, "the first block after the seek starts before the target")
        assertEquals(1024 - 480, first.frames, "the 480 frames before the target were not trimmed")
        harness.close()
    }
}

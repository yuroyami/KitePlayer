package io.github.yuroyami.kiteplayer.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The deadline and head-extension arithmetic of S1.c.4 step 6, pinned as pure functions, plus
 * the one pairing rule that makes the arithmetic meaningful. `SystemClock` itself cannot run on
 * a host JVM, which is exactly why the sink reads time through the seam and why these tests need
 * no device.
 */
class AndroidAudioClockTest {

    @Test
    fun `the timestamp deadline is nanoTime plus the duration of the frames still queued past it`() {
        /* 48 kHz: 480 frames are exactly 10 ms. Submitted 1000, timestamp at frame 520, block
         * of 0: the last already-submitted frame lands 10 ms after the timestamped instant. */
        assertEquals(
            1_000_000L + 10_000_000L,
            AudioTrackSink.timestampDeadline(
                timestampNanos = 1_000_000L,
                timestampFrames = 520,
                submittedFrames = 1_000,
                requestedFrames = 0,
                sampleRate = 48_000,
            ),
        )
        /* The requested block extends the same line. */
        assertEquals(
            1_000_000L + 20_000_000L,
            AudioTrackSink.timestampDeadline(
                timestampNanos = 1_000_000L,
                timestampFrames = 520,
                submittedFrames = 1_000,
                requestedFrames = 480,
                sampleRate = 48_000,
            ),
        )
    }

    @Test
    fun `frames to nanos is exact at the boundary rates and refuses a zero rate`() {
        assertEquals(1_000_000_000L, AudioTrackSink.framesToNanos(48_000, 48_000))
        assertEquals(0L, AudioTrackSink.framesToNanos(0, 48_000))
        assertEquals(0L, AudioTrackSink.framesToNanos(500, 0))
        /* No overflow inside a plausible session: a week of 192 kHz still fits a Long. */
        val weekFrames = 192_000L * 60 * 60 * 24 * 7
        assertTrue(AudioTrackSink.framesToNanos(weekFrames, 192_000) > 0)
    }

    @Test
    fun `the backend pairs the AudioTimestamp time base with the AudioTrack sink`() {
        assertSame(AndroidMonotonicClock, AndroidOutputBackend.clock)
        assertEquals("AudioTrack", AndroidOutputBackend.audioSink.name)
        assertEquals(null, AndroidOutputBackend.videoRenderer)
    }
}

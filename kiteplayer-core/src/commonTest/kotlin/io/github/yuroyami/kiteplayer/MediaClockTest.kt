package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.MediaClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MediaClockTest {

    @Test
    fun `a fresh clock has no reading`() {
        val clock = MediaClock(TestClock())
        assertNull(clock.nowOrNull(), "a clock that was never anchored must read null, not zero")
        assertFalse(clock.isValid)
    }

    @Test
    fun `an anchored clock advances with wall time`() {
        val wall = TestClock()
        val clock = MediaClock(wall)
        clock.set(pts(10_000), Generation.Initial)

        assertEquals(pts(10_000), clock.nowOrNull())
        wall.advance(500.milliseconds)
        assertEquals(pts(10_500), clock.nowOrNull())
        wall.advance(1.seconds)
        assertEquals(pts(11_500), clock.nowOrNull())
    }

    @Test
    fun `double speed consumes media time twice as fast`() {
        val wall = TestClock()
        val clock = MediaClock(wall)
        clock.set(pts(0), Generation.Initial)
        clock.speed = 2.0

        wall.advance(1.seconds)
        assertEquals(pts(2_000), clock.nowOrNull())
        wall.advance(1.seconds)
        assertEquals(pts(4_000), clock.nowOrNull())
    }

    @Test
    fun `half speed consumes media time half as fast`() {
        val wall = TestClock()
        val clock = MediaClock(wall)
        clock.set(pts(0), Generation.Initial)
        clock.speed = 0.5

        wall.advance(2.seconds)
        assertEquals(pts(1_000), clock.nowOrNull())
    }

    @Test
    fun `changing speed applies from that moment, not retroactively`() {
        val wall = TestClock()
        val clock = MediaClock(wall)
        clock.set(pts(0), Generation.Initial)

        // One second at normal speed.
        wall.advance(1.seconds)
        assertEquals(pts(1_000), clock.nowOrNull())

        // Then double speed for one second. The first second must not be recounted.
        clock.speed = 2.0
        wall.advance(1.seconds)
        assertEquals(
            pts(3_000),
            clock.nowOrNull(),
            "a speed change must re-anchor, otherwise the elapsed interval is rescaled retroactively",
        )
    }

    @Test
    fun `a paused clock does not advance and resumes without losing the pause`() {
        val wall = TestClock()
        val clock = MediaClock(wall)
        clock.set(pts(5_000), Generation.Initial)

        wall.advance(200.milliseconds)
        clock.pause()
        assertEquals(pts(5_200), clock.nowOrNull())

        // Ten seconds of wall time pass while paused. None of it is media time.
        wall.advance(10.seconds)
        assertEquals(pts(5_200), clock.nowOrNull())

        clock.resume()
        assertEquals(pts(5_200), clock.nowOrNull())
        wall.advance(300.milliseconds)
        assertEquals(
            pts(5_500),
            clock.nowOrNull(),
            "the paused interval must not count as elapsed media time after resuming",
        )
    }

    @Test
    fun `pausing twice and resuming twice is harmless`() {
        val wall = TestClock()
        val clock = MediaClock(wall)
        clock.set(pts(1_000), Generation.Initial)

        clock.pause()
        clock.pause()
        wall.advance(1.seconds)
        clock.resume()
        clock.resume()

        wall.advance(500.milliseconds)
        assertEquals(pts(1_500), clock.nowOrNull())
    }

    @Test
    fun `invalidate makes the clock unreadable until it is anchored again`() {
        val wall = TestClock()
        val clock = MediaClock(wall)
        clock.set(pts(1_000), Generation.Initial)

        clock.invalidate()
        assertNull(clock.nowOrNull(), "an invalidated clock must read null so callers cannot use a stale value")
        assertFalse(clock.isValid)

        clock.set(pts(9_000), Generation.Initial.next())
        assertEquals(pts(9_000), clock.nowOrNull())
        assertTrue(clock.isValid)
        assertEquals(Generation(1), clock.generation)
    }

    @Test
    fun `anchoring at an explicit instant back-dates the reading`() {
        val wall = TestClock(startNanos = 1_000_000_000)
        val clock = MediaClock(wall)

        // This is the audio case: a buffer whose last frame becomes audible 80 ms from now carries
        // timestamp 2.000 s, so at the current instant the audible timestamp is 1.920 s.
        val deadline = wall.nanos() + 80.milliseconds.inWholeNanoseconds
        clock.setAt(pts(2_000), Generation.Initial, deadline)

        assertEquals(
            pts(1_920),
            clock.nowOrNull(),
            "anchoring in the future must read back as the past, which is how device latency is removed",
        )
        wall.advance(80.milliseconds)
        assertEquals(pts(2_000), clock.nowOrNull())
    }

    @Test
    fun `speed must be positive`() {
        val clock = MediaClock(TestClock())
        assertFailsWith<IllegalArgumentException> { clock.speed = 0.0 }
        assertFailsWith<IllegalArgumentException> { clock.speed = -1.0 }
    }

    @Test
    fun `a snapshot is a consistent read`() {
        val wall = TestClock()
        val clock = MediaClock(wall)
        clock.set(pts(4_000), Generation(7))
        clock.speed = 1.5

        val snapshot = clock.snapshot()
        assertEquals(pts(4_000), snapshot.pts)
        assertEquals(Generation(7), snapshot.generation)
        assertEquals(1.5, snapshot.speed)
        assertTrue(snapshot.isValid)

        // The snapshot does not move when the clock does.
        wall.advance(1.seconds)
        assertEquals(pts(4_000), snapshot.pts)
        assertEquals(pts(5_500), clock.nowOrNull())
    }

    @Test
    fun `long playback does not accumulate rounding error`() {
        val wall = TestClock()
        val clock = MediaClock(wall)
        clock.set(pts(0), Generation.Initial)

        // Three hours in 10 ms steps. A design that re-anchored on every read, or kept a scaled
        // accumulator, would drift measurably here. Reading is a pure function of the anchor, so it
        // cannot.
        repeat(3 * 60 * 60 * 100) { wall.advance(10.milliseconds) }

        assertEquals(pts(3 * 60 * 60 * 1_000), clock.nowOrNull())
    }
}

package io.github.yuroyami.kiteplayer.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * When a platform must be told again. Every platform walks the position on by itself from the last
 * position and rate it was given, so steady playback is no news. A seek, a stall, or a change of
 * phase, rate or buttons is.
 */
class MediaSessionPushTest {

    private fun state(
        phase: MediaSessionPhase = MediaSessionPhase.Playing,
        position: Duration = 10.seconds,
        speed: Double = 1.0,
        hasNext: Boolean = false,
    ) = MediaSessionState(
        phase = phase,
        position = position,
        duration = 60.seconds,
        speed = speed,
        canSeek = true,
        hasVideo = false,
        title = "A Holiday",
        artist = null,
        album = null,
        hasNext = hasNext,
        hasPrevious = false,
    )

    @Test
    fun `the first state is always pushed`() {
        assertTrue(state().needsPush(previous = null, elapsed = Duration.ZERO))
    }

    @Test
    fun `steady playback is not pushed`() {
        assertFalse(state(position = 11_200.milliseconds).needsPush(state(), elapsed = 1.seconds))
    }

    @Test
    fun `a forward seek while playing is pushed`() {
        assertTrue(state(position = 25.seconds).needsPush(state(), elapsed = 1.seconds))
    }

    @Test
    fun `a backward seek while playing is pushed`() {
        assertTrue(state(position = 9.seconds).needsPush(state(), elapsed = 1.seconds))
    }

    @Test
    fun `a stall the platform cannot see is pushed`() {
        // Told "playing at 10 s" three seconds ago, the platform now shows 13 s. The truth is 10 s.
        assertTrue(state(position = 10.seconds).needsPush(state(), elapsed = 3.seconds))
    }

    @Test
    fun `any move while paused is pushed and time alone is not`() {
        val paused = state(phase = MediaSessionPhase.Paused)
        assertTrue(paused.copy(position = 10_500.milliseconds).needsPush(paused, elapsed = 1.seconds))
        assertFalse(paused.needsPush(paused, elapsed = 5.seconds))
    }

    @Test
    fun `a phase change is pushed even at the same position`() {
        assertTrue(state(phase = MediaSessionPhase.Paused).needsPush(state(), elapsed = Duration.ZERO))
        assertTrue(state(phase = MediaSessionPhase.Buffering).needsPush(state(), elapsed = Duration.ZERO))
    }

    @Test
    fun `a rate change is pushed`() {
        assertTrue(state(speed = 1.5).needsPush(state(), elapsed = Duration.ZERO))
    }

    @Test
    fun `a button change is pushed`() {
        assertTrue(state(hasNext = true).needsPush(state(), elapsed = Duration.ZERO))
    }

    @Test
    fun `the allowance grows with the rate`() {
        // The same 2.5 s gap is a stale sample at four times speed and a real jump at normal speed.
        assertFalse(state(speed = 4.0, position = 11_500.milliseconds).needsPush(state(speed = 4.0), 1.seconds))
        assertTrue(state(position = 8_500.milliseconds).needsPush(state(), 1.seconds))
        assertTrue(driftTolerance(4.0) > driftTolerance(1.0))
    }
}

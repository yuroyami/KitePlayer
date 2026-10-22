package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A paused player tells the sender of a live stream that it paused, keeps the session alive while
 * it waits, and plays from the live edge when it plays again (#441). A session the sender ended
 * anyway is opened again on play rather than failing.
 *
 * The scripted sender keeps a session for two seconds without a request, as an RTSP camera keeps
 * one for its timeout, and the pauses here are longer than that. The live edge is read as the
 * sender's time less the player's position; the bound is [LiveDelayTest]'s, the default ready
 * duration of a second and the half second the catch-up lets build, plus one frame.
 */
class LivePauseTest {

    private fun camera(
        pause: Boolean = true,
        endsAtUs: Long? = null,
        resumeRefused: Boolean = false,
    ) = MediaScript(
        durationUs = 120_000_000,
        live = true,
        seekable = false,
        livePause = pause,
        liveSessionTimeoutUs = TIMEOUT_US,
        liveSessionEndsAtUs = endsAtUs,
        liveResumeRefused = resumeRefused,
    )

    private fun CoreHarness.senderUs(): Long = (clock.nanos() - source.liveOriginNanos) / 1_000

    /** How far the player is behind the sender, in microseconds. */
    private fun CoreHarness.delayUs(): Long = senderUs() - core.position().inWholeMicroseconds

    private fun CoreHarness.status(): PlaybackStatus = core.snapshots.value.status

    private fun CoreHarness.failure(): String = "status ${status()}, events ${events.takeLast(8)}"

    @Test
    fun aPauseLongerThanTheSessionTimeoutIsKeptAliveAndPlaysOnAtTheLiveEdge() = runTest {
        val harness = CoreHarness(this, script = camera())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3.seconds)
        harness.core.pause()
        harness.run(PAUSE)
        val source = harness.source
        assertTrue(source.pauseReadingCalls >= 4, "the sender was told ${source.pauseReadingCalls} times in a pause of $PAUSE")
        harness.core.play()
        harness.run(3.seconds)
        assertEquals(PlaybackStatus.Playing, harness.status(), harness.failure())
        assertEquals(1, harness.backend.sessions.size, "the stream was opened again")
        assertEquals(1, source.resumeReadingCalls)
        val delayUs = harness.delayUs()
        assertTrue(delayUs in 0..CEILING_US, "the player plays ${delayUs / 1000} ms behind the sender")
        harness.close()
    }

    /** A short pause resumes the same way: from the live edge, with the session it had. */
    @Test
    fun aShortPauseAlsoPlaysOnAtTheLiveEdge() = runTest {
        val harness = CoreHarness(this, script = camera())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3.seconds)
        harness.core.pause()
        harness.run(500.milliseconds)
        harness.core.play()
        harness.run(3.seconds)
        assertEquals(PlaybackStatus.Playing, harness.status(), harness.failure())
        assertEquals(1, harness.backend.sessions.size, "the stream was opened again")
        assertEquals(1, harness.source.resumeReadingCalls)
        assertTrue(harness.delayUs() in 0..CEILING_US, "the player plays ${harness.delayUs() / 1000} ms behind the sender")
        harness.close()
    }

    /**
     * A sender with no notion of a pause is asked once and then read as before: the reads fill the
     * buffer, and play goes on from where the pause left it, which is the pause's length behind.
     */
    @Test
    fun aSenderThatCannotPauseIsReadAsBefore() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 120_000_000, live = true, seekable = false),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3.seconds)
        harness.core.pause()
        val pausedAt = harness.core.position()
        harness.run(PAUSE)
        harness.core.play()
        harness.run(200.milliseconds)
        val source = harness.source
        assertEquals(1, source.pauseReadingCalls, "the sender was asked to pause ${source.pauseReadingCalls} times")
        assertEquals(0, source.resumeReadingCalls)
        assertEquals(PlaybackStatus.Playing, harness.status(), harness.failure())
        val moved = harness.core.position() - pausedAt
        assertTrue(moved in 0.milliseconds..500.milliseconds, "play went on $moved from where the pause left it")
        harness.close()
    }

    /** The sender ends the session in the middle of the pause, so a keepalive fails. */
    @Test
    fun aSessionThatEndedDuringThePauseIsOpenedAgainOnPlay() = runTest {
        val harness = CoreHarness(this, script = camera(endsAtUs = 5_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3.seconds)
        harness.core.pause()
        harness.run(PAUSE)
        assertEquals(PlaybackStatus.Paused, harness.status(), harness.failure())
        harness.core.play()
        harness.run(3.seconds)
        assertEquals(PlaybackStatus.Playing, harness.status(), harness.failure())
        assertEquals(2, harness.backend.sessions.size, "the stream was not opened again")
        assertTrue(
            harness.events.any { it is PlayerEvent.Warning && it.warning is PlaybackWarning.SourceReconnecting },
            "nothing said the stream was opened again: ${harness.events.takeLast(8)}",
        )
        assertTrue(harness.delayUs() in 0..CEILING_US, "the player plays ${harness.delayUs() / 1000} ms behind the sender")
        harness.close()
    }

    /** The keepalives went through, and the sender refuses the play all the same. */
    @Test
    fun aRefusedResumeOpensTheStreamAgain() = runTest {
        val harness = CoreHarness(this, script = camera(resumeRefused = true))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3.seconds)
        harness.core.pause()
        harness.run(PAUSE)
        harness.core.play()
        harness.run(3.seconds)
        assertEquals(PlaybackStatus.Playing, harness.status(), harness.failure())
        assertEquals(2, harness.backend.sessions.size, "the stream was not opened again")
        harness.close()
    }

    /** A player opened and left paused holds the sender too, from the end of the open. */
    @Test
    fun aPlayerThatNeverPlayedKeepsTheSessionAliveToo() = runTest {
        val harness = CoreHarness(this, script = camera())
        harness.openWithRenderer()
        harness.run(PAUSE)
        harness.core.play()
        harness.run(3.seconds)
        assertEquals(PlaybackStatus.Playing, harness.status(), harness.failure())
        assertEquals(1, harness.backend.sessions.size, "the stream was opened again")
        assertTrue(harness.delayUs() in 0..CEILING_US, "the player plays ${harness.delayUs() / 1000} ms behind the sender")
        harness.close()
    }

    private companion object {
        /** The scripted sender's session timeout. */
        const val TIMEOUT_US = 2_000_000L

        /** Longer than [TIMEOUT_US], so only a keepalive keeps the session. */
        val PAUSE = 6_000_000L.microseconds

        /** The ready duration and the half second the player lets build, plus one frame. */
        const val CEILING_US = 1_000_000L + 500_000L + 40_000L
    }
}

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Skip silence through the whole engine (#429). The scripted sound is a steady level with digital
 * silence where a script says, and the device counts what it plays. A three second pause plays as a
 * fifth of a second, the clock reads past it at once and ends on the item's length, an item with a
 * picture is never cut, and a queue moves on with no gap whether its first item ends in a short
 * pause or a long one.
 */
class SkipSilenceTest {

    private val rate = 48_000

    private suspend fun CoreHarness.runUntil(limit: Duration, condition: () -> Boolean): Duration {
        var waited = Duration.ZERO
        while (!condition()) {
            check(waited < limit) { "still waiting after $limit" }
            run(10.milliseconds)
            waited += 10.milliseconds
        }
        return waited
    }

    private fun CoreHarness.skipSilence(on: Boolean) =
        core.post(CoreCommand.SetSkipSilence(on, CompletableDeferred()))

    private fun secondsPlayed(harness: CoreHarness): Double = harness.sink.framesPlayed.toDouble() / rate

    @Test
    fun aThreeSecondPauseIsCutAndTheClockFollowsIt() = runTest {
        val script = MediaScript(durationUs = 13_000_000, hasVideo = false, audioSilentUs = listOf(5_000_000L until 8_000_000L))
        val harness = CoreHarness(this, script = script)
        harness.open()
        harness.skipSilence(true)
        harness.core.play()
        harness.run(6_500.milliseconds)
        assertEquals(true, harness.core.snapshots.value.skipSilence)
        val position = harness.core.position().inWholeMilliseconds
        assertTrue(position in 9_100..9_500, "six and a half seconds in, past the cut, the clock read $position ms")
        val took = 6_500.milliseconds + harness.runUntil(10.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }
        assertTrue(took in 10.seconds..10.6.seconds, "13 s with a 3 s pause took $took")
        assertEquals(10.2, secondsPlayed(harness), 0.05, "the device played ${secondsPlayed(harness)} s")
        val end = harness.core.position().inWholeMilliseconds
        assertTrue(end >= 12_900, "the clock ended at $end ms of 13 s")
        assertEquals(0L, harness.core.stats.value.audioUnderruns, "a cut ran the device dry")
        harness.close()
    }

    private suspend fun heardAtTwiceTheSpeed(skip: Boolean, scope: kotlinx.coroutines.test.TestScope): Double {
        val script = MediaScript(durationUs = 13_000_000, hasVideo = false, audioSilentUs = listOf(5_000_000L until 8_000_000L))
        val harness = CoreHarness(scope, script = script)
        harness.open()
        harness.skipSilence(skip)
        harness.core.setSpeed(2.0)
        harness.core.play()
        harness.runUntil(10.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }
        val end = harness.core.position().inWholeMilliseconds
        assertTrue(end >= 12_900, "the clock ended at $end ms of 13 s")
        val heard = secondsPlayed(harness)
        harness.close()
        return heard
    }

    @Test
    fun atTwiceTheSpeedThePauseIsCutAsItIsHeard() = runTest {
        val plain = heardAtTwiceTheSpeed(skip = false, this)
        val skipped = heardAtTwiceTheSpeed(skip = true, this)
        // The pause is heard as one and a half seconds at 2x, and kept to a fifth of a second.
        assertEquals(1.3, plain - skipped, 0.05, "at 2x the item was heard for $plain s, and $skipped s with the pause cut")
    }

    @Test
    fun offEverySampleIsPlayed() = runTest {
        val script = MediaScript(durationUs = 13_000_000, hasVideo = false, audioSilentUs = listOf(5_000_000L until 8_000_000L))
        val harness = CoreHarness(this, script = script)
        harness.open()
        harness.core.play()
        val took = harness.runUntil(16.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }
        assertTrue(took >= 13.seconds, "with skip silence off the item took $took")
        assertEquals(13.0, secondsPlayed(harness), 0.05)
        harness.close()
    }

    @Test
    fun anItemWithAPictureIsNotCut() = runTest {
        val script = MediaScript(durationUs = 6_000_000, audioSilentUs = listOf(2_000_000L until 4_000_000L))
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.skipSilence(true)
        harness.core.play()
        harness.runUntil(9.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }
        assertEquals(6.0, secondsPlayed(harness), 0.05, "the picture's sound was cut to ${secondsPlayed(harness)} s")
        harness.close()
    }

    private suspend fun queueEndingIn(silenceUs: Long, scope: kotlinx.coroutines.test.TestScope): CoreHarness {
        val script = MediaScript(
            durationUs = 3_000_000,
            hasVideo = false,
            audioSilentUs = listOf(3_000_000L - silenceUs until 3_000_000L),
        )
        val harness = CoreHarness(scope, script = script)
        harness.skipSilence(true)
        harness.core.openQueue(listOf(MediaItem("scripted://first"), MediaItem("scripted://second")), 0)
        harness.core.play()
        harness.runUntil(5.seconds) { harness.core.snapshots.value.queueIndex == 1 }
        assertEquals(listOf("open", "resume", "start"), harness.sink.calls, "the queue did not join gaplessly")
        assertEquals(0L, harness.core.stats.value.audioUnderruns, "the device ran dry across the join")
        harness.runUntil(5.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }
        return harness
    }

    @Test
    fun aQueueWhoseFirstItemEndsInAShortPauseMovesOnWithNoGap() = runTest {
        val harness = queueEndingIn(silenceUs = 150_000, this)
        assertEquals(6.0, secondsPlayed(harness), 0.03, "a pause shorter than the longest kept was cut")
        harness.close()
    }

    @Test
    fun aQueueWhoseFirstItemEndsInALongPauseMovesOnWithNoGap() = runTest {
        val harness = queueEndingIn(silenceUs = 1_000_000, this)
        // The second item's own pause is cut too, at its end.
        assertEquals(6.0 - 2 * 0.8, secondsPlayed(harness), 0.05, "the two items played ${secondsPlayed(harness)} s")
        harness.close()
    }
}

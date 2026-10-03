package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A length that is only an estimate, as FFmpeg guesses one from the bit rate, cuts no seek and is
 * replaced by what really plays (#422). A stated length behaves as before.
 */
class EstimatedDurationTest {

    /** Lets virtual time pass in small steps until [condition] holds, or [limit] has passed. */
    private suspend fun CoreHarness.runUntil(limit: Duration, condition: () -> Boolean): Boolean {
        var waited = Duration.ZERO
        while (!condition()) {
            if (waited >= limit) return false
            run(10.milliseconds)
            waited += 10.milliseconds
        }
        return true
    }

    @Test
    fun aSeekPastAnUnderestimateLandsWhereItAsked() = runTest {
        // Says 2 s, plays 8 s, as an ADTS file that starts loud says 11 s and plays 60.
        val script = MediaScript(durationUs = 8_000_000, declaredDurationUs = 2_000_000, durationIsEstimate = true)
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        assertTrue(harness.core.snapshots.value.durationIsEstimate)
        assertEquals(2.seconds, harness.core.snapshots.value.duration)

        harness.core.seek(Pts(5_000_000), SeekMode.Precise)
        val position = harness.core.position()
        assertTrue(position in 4.9.seconds..5.1.seconds, "the seek was cut at the estimate: $position")
        harness.close()
    }

    @Test
    fun theLengthFollowsPlaybackPastAnUnderestimateAndIsRealAtTheEnd() = runTest {
        val script = MediaScript(durationUs = 6_000_000, declaredDurationUs = 2_000_000, durationIsEstimate = true, hasVideo = false)
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.play()
        assertTrue(harness.runUntil(5.seconds) { harness.core.position() > 4.seconds })
        val playing = harness.core.snapshots.value
        assertTrue(playing.duration!! >= 3.seconds, "the length stayed at the estimate while playback passed it: ${playing.duration}")
        assertFalse(playing.durationIsEstimate, "a length playback has passed is not a guess any more")

        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended })
        val ended = harness.core.snapshots.value
        val real = ended.duration!!
        assertTrue(real in 5.8.seconds..6.2.seconds, "the ended length is not the real one: $real")
        assertFalse(ended.durationIsEstimate)
        harness.close()
    }

    @Test
    fun anOverestimateShrinksToTheRealLengthAtTheEnd() = runTest {
        // Says 100 s, plays 3 s, as a quiet ADTS start says 1,578 s and plays 60.
        val script = MediaScript(durationUs = 3_000_000, declaredDurationUs = 100_000_000, durationIsEstimate = true, hasVideo = false)
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.play()
        assertTrue(harness.runUntil(6.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended })
        val real = harness.core.snapshots.value.duration!!
        assertTrue(real in 2.8.seconds..3.2.seconds, "the ended length is not the real one: $real")
        harness.close()
    }

    @Test
    fun aStartPositionPastAnUnderestimateIsHonoured() = runTest {
        val script = MediaScript(durationUs = 8_000_000, declaredDurationUs = 2_000_000, durationIsEstimate = true)
        val harness = CoreHarness(this, script = script)
        harness.attachRenderer()
        harness.core.open(MediaItem("scripted://media", startPosition = 5.seconds))
        harness.run(200.milliseconds)
        val position = harness.core.position()
        assertTrue(position in 4.9.seconds..5.1.seconds, "the start position was refused at the estimate: $position")
        assertTrue(
            harness.core.warningHistory().none { it.warning is PlaybackWarning.StartPositionIgnored },
            "the start position was reported ignored",
        )
        harness.close()
    }

    @Test
    fun aQueueOfOverestimatedItemsStillJoinsWithoutAGap() = runTest {
        val script = MediaScript(durationUs = 3_000_000, declaredDurationUs = 100_000_000, durationIsEstimate = true, hasVideo = false)
        val harness = CoreHarness(this, script = script)
        harness.core.openQueue(listOf(MediaItem("scripted://first"), MediaItem("scripted://second")), 0)
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.preloadedIndex == 1 }, "the second item never preloaded")
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.queueIndex == 1 }, "the queue never moved on")
        assertEquals(listOf("open", "resume", "start"), harness.sink.calls, "the join stopped the device")
        assertTrue(
            harness.core.warningHistory().none { it.warning is PlaybackWarning.GaplessFallback },
            "the join fell back",
        )
        harness.close()
    }

    @Test
    fun aStatedLengthStillCutsASeek() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        assertFalse(harness.core.snapshots.value.durationIsEstimate)
        harness.core.seek(Pts(30_000_000), SeekMode.Precise)
        assertTrue(harness.core.position() <= 4.seconds, "a stated length no longer cut the seek: ${harness.core.position()}")
        harness.close()
    }
}

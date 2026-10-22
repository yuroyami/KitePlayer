package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A file with a picture, played with video turned off, as an app does in the background: the
 * parked video lane throws its packets away as they arrive, so its queue is always empty, and it
 * must count as neither unready nor starved.
 */
class ParkedVideoTest {

    @Test
    fun aParkedVideoLaneNeitherDelaysTheOpenNorStarvesTheAudio() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 60_000_000),
            // A short read-ahead budget, so the demuxer meets it within the test.
            config = PlayerConfig(videoEnabled = false, buffer = BufferPolicy(totalDuration = 5.seconds)),
        )
        val openedAt = harness.clock.nanos()
        harness.openWithRenderer()
        val openTook = (harness.clock.nanos() - openedAt).nanoseconds
        assertTrue(openTook < 2.seconds, "the open waited $openTook for a picture that never comes")

        harness.core.play()
        var last = harness.core.position()
        var biggestStep = Duration.ZERO
        repeat(100) {
            harness.run(100.milliseconds)
            val position = harness.core.position()
            biggestStep = maxOf(biggestStep, position - last)
            last = position
        }
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        assertTrue(biggestStep < 300.milliseconds, "the position jumped by $biggestStep: the audio was cut")
        assertTrue(last in 9.seconds..11.seconds, "ten seconds of play reached $last")
        val warnings = harness.core.warningHistory().map { it.warning }
        assertTrue(warnings.none { it is PlaybackWarning.PathologicalInterleaving }, "the relief cut the audio: $warnings")
        assertTrue(warnings.none { it is PlaybackWarning.StartupIncomplete }, "the open waited for a picture: $warnings")
        harness.close()
    }

    @Test
    fun aSeekWithVideoParkedLeavesBufferingAtOnce() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 60_000_000),
            config = PlayerConfig(videoEnabled = false),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        harness.core.seek(Pts(30_000_000), SeekMode.Precise)
        harness.run(1.seconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status, "the player stayed in Buffering")
        assertTrue(harness.core.position() in 30.seconds..32.seconds, "the seek landed at ${harness.core.position()}")
        harness.close()
    }
}

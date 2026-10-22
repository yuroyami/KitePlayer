package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Live speed changes on a file with sound and picture: the player never leaves Playing, and the
 * picture stays in step with the sound through each change, including while the audio already
 * buffered still plays the old rate (#373).
 */
class SpeedChangeSyncTest {

    @Test
    fun thePictureFollowsTheSoundThroughLiveSpeedChanges() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 30_000_000),
            config = PlayerConfig(statsInterval = 100.milliseconds),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)

        var worstDrift = Duration.ZERO
        val dropsBefore = harness.core.stats.value.droppedFramesLate
        for (speed in listOf(1.005, 2.0, 0.5, 0.995, 1.0)) {
            harness.core.setSpeed(speed)
            repeat(15) {
                harness.run(100.milliseconds)
                assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status, "a change to $speed left Playing")
                val drift = harness.core.stats.value.avDrift
                if (abs(drift.inWholeMicroseconds) > abs(worstDrift.inWholeMicroseconds)) worstDrift = drift
            }
        }
        // The schedule corrects by whole frames past 40 ms, so a picture that follows the sound
        // stays inside that.
        assertTrue(abs(worstDrift.inWholeMilliseconds) <= 45, "the picture drifted $worstDrift from the sound")
        val drops = harness.core.stats.value.droppedFramesLate - dropsBefore
        assertTrue(drops <= 3, "the changes cost $drops late frames")
        assertTrue(
            harness.core.warningHistory().none { it.warning is PlaybackWarning.CommandRefused },
            "no speed change is refused",
        )
        harness.close()
    }
}

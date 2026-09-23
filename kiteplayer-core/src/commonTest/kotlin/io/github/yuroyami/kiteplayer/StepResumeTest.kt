package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Playback after frame steps restarts at the picture.
 *
 * A step moves the picture and not the sound. Resumed as it stands, the sound would restart where
 * playback paused and the picture would stand still until the sound caught up with it.
 */
class StepResumeTest {

    private fun CoreHarness.onScreenUs(): Long = renderer!!.timestamps.last().micros

    /** Plays one second, pauses, and steps [steps] frames in [direction]. Returns the picture. */
    private suspend fun CoreHarness.pausedAfterSteps(direction: StepDirection, steps: Int): Long {
        openWithRenderer()
        core.play()
        run(1.seconds)
        core.pause()
        run(100.milliseconds)
        repeat(steps) {
            core.stepFrame(direction)
            run(50.milliseconds)
        }
        return onScreenUs()
    }

    /** Plays for [duration], asserting every 10 ms that the position never reads before [pictureUs]. */
    private suspend fun CoreHarness.assertPlaysOnFrom(pictureUs: Long, duration: kotlin.time.Duration) {
        val steps = (duration / 10.milliseconds).toInt()
        repeat(steps) {
            run(10.milliseconds)
            val position = core.position().inWholeMicroseconds
            assertTrue(position >= pictureUs, "the position went back to $position from the picture at $pictureUs")
        }
        assertTrue(onScreenUs() > pictureUs, "the picture must move on after play, and it stayed at ${onScreenUs()}")
    }

    @Test
    fun `play after forward steps restarts the sound at the picture`() = runTest {
        val harness = CoreHarness(this)
        val picture = harness.pausedAfterSteps(StepDirection.Forward, steps = 5)
        val seeksBefore = harness.source.seeks

        harness.core.play()
        harness.assertPlaysOnFrom(picture, 300.milliseconds)

        assertEquals(seeksBefore + 1, harness.source.seeks, "one seek puts the sound at the picture")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun `play after one forward step does not seek`() = runTest {
        // One 40 ms frame is inside the sync law's tolerance, so the schedule absorbs it.
        val harness = CoreHarness(this)
        harness.pausedAfterSteps(StepDirection.Forward, steps = 1)
        val seeksBefore = harness.source.seeks

        harness.core.play()
        harness.run(300.milliseconds)

        assertEquals(seeksBefore, harness.source.seeks, "a one-frame lead needs no seek")
        harness.close()
    }

    @Test
    fun `play after a backward step and forward steps restarts the sound at the picture`() = runTest {
        // The backward step flushed the sound, so its clock has no reading to compare with the
        // picture, and the sound would restart one frame after where the backward step landed.
        val harness = CoreHarness(this)
        harness.pausedAfterSteps(StepDirection.Backward, steps = 1)
        repeat(5) {
            harness.core.stepFrame(StepDirection.Forward)
            harness.run(50.milliseconds)
        }
        val picture = harness.onScreenUs()

        harness.core.play()
        harness.assertPlaysOnFrom(picture, 300.milliseconds)
        harness.close()
    }
}

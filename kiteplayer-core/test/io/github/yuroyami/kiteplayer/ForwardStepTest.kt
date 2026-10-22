package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * A forward step shows the next frame of the media, however late the schedule runs.
 *
 * The frames alternate between 40 and 17 ms apart. A schedule that runs late finds a short frame
 * already past its time, and the playback rule for a late frame is to drop it.
 */
class ForwardStepTest {

    private val frames = List(20) { index -> index / 2 * 57_000L + index % 2 * 40_000L }

    private val script = MediaScript(
        durationUs = 1_200_000,
        videoTimestampsUs = frames,
        videoKeyframesUs = setOf(0L),
    )

    @Test
    fun `each forward step shows the next frame when every present runs late`() = runTest {
        // A renderer that needs 80 ms for each frame puts every step behind its own schedule.
        val harness = CoreHarness(this, script = script, renderer = RecordingRenderer(presentDuration = 80.milliseconds))
        harness.openWithRenderer()

        repeat(STEPS) {
            harness.core.stepFrame(StepDirection.Forward)
            harness.run(200.milliseconds)
        }

        assertEquals(
            frames.take(STEPS + 1),
            harness.renderer!!.timestamps.map { it.micros },
            "each step must show the next frame, and a step may never drop one",
        )
        harness.close()
    }

    private companion object {
        const val STEPS = 8
    }
}

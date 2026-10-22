@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A backward step lands on the last frame before the one on screen.
 *
 * The frames sit at 0, 33, 50, 100 and 133 ms, with keyframes at 0 and 100 ms. No two gaps are
 * equal, so a step by an average period lands between frames, and the walk back from 100 ms has to
 * cross a keyframe to reach 50 ms.
 */
class BackwardStepTest {

    private val script = MediaScript(
        durationUs = 166_000,
        videoTimestampsUs = listOf(0L, 33_000L, 50_000L, 100_000L, 133_000L),
        videoKeyframesUs = setOf(0L, 100_000L),
    )

    private fun CoreHarness.onScreenUs(): Long = renderer!!.timestamps.last().micros

    private suspend fun CoreHarness.pausedAtTheSecondKeyframe() {
        openWithRenderer()
        core.seek(Pts(100_000), SeekMode.Precise)
        run(100.milliseconds)
        assertEquals(100_000L, onScreenUs(), "the walk starts on the keyframe at 100 ms")
    }

    @Test
    fun `each backward step shows the frame before and the first frame refuses`() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.pausedAtTheSecondKeyframe()

        val shown = mutableListOf<Long>()
        val reported = mutableListOf<Long>()
        repeat(3) {
            harness.core.stepFrame(StepDirection.Backward)
            harness.run(100.milliseconds)
            shown += harness.onScreenUs()
            reported += harness.core.position().inWholeMicroseconds
        }

        assertEquals(listOf(50_000L, 33_000L, 0L), shown, "each step shows the frame before the last one")
        assertEquals(shown, reported, "the reported position is the frame on screen")
        assertFailsWith<IllegalStateException>("the first frame has nothing before it") {
            harness.core.stepFrame(StepDirection.Backward)
        }
        harness.run(100.milliseconds)
        assertEquals(0L, harness.core.position().inWholeMicroseconds, "a refused step moves nothing")
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status, "stepping stays paused")
        harness.close()
    }

    @Test
    fun `a forward step after two backward steps shows the frame they left`() = runTest {
        // The frame a backward landing stops at stays at the head of the queue, so stepping forward
        // again needs no seek and shows exactly the frame the viewer came from.
        val harness = CoreHarness(this, script = script)
        harness.pausedAtTheSecondKeyframe()

        harness.core.stepFrame(StepDirection.Backward)
        harness.run(100.milliseconds)
        harness.core.stepFrame(StepDirection.Backward)
        harness.run(100.milliseconds)
        assertEquals(33_000L, harness.onScreenUs())
        val seeksBefore = harness.source.seeks

        harness.core.stepFrame(StepDirection.Forward)
        harness.run(100.milliseconds)

        assertEquals(50_000L, harness.onScreenUs(), "forward from 33 ms is 50 ms again")
        assertEquals(seeksBefore, harness.source.seeks, "a forward step takes the queued frame and does not seek")
        harness.close()
    }

    @Test
    fun `a slow decoder still lands the step on a picture and not on the sound`() = runTest {
        // The sound lands at the target, which is after the frame a backward step wants, so only a
        // picture can answer the step, however long the decoder takes to reach it.
        val faults = FaultPlan()
        val harness = CoreHarness(this, script = script, faults = faults)
        harness.pausedAtTheSecondKeyframe()
        faults.videoDecodeReceiveDelay = 300.milliseconds

        harness.core.stepFrame(StepDirection.Backward)
        harness.run(100.milliseconds)

        assertEquals(50_000L, harness.onScreenUs())
        assertEquals(50_000L, harness.core.position().inWholeMicroseconds)
        harness.close()
    }

    @Test
    fun `a stop during a backward step fails the step as stopped and not as the first frame`() = runTest {
        val faults = FaultPlan()
        val harness = CoreHarness(this, script = script, faults = faults)
        harness.pausedAtTheSecondKeyframe()
        faults.videoDecodeReceiveDelay = 300.milliseconds

        val step = async { runCatching { harness.core.stepFrame(StepDirection.Backward) } }
        harness.run(400.milliseconds)
        harness.core.stop()
        harness.run(2.seconds)

        val failure = assertNotNull(step.await().exceptionOrNull(), "a step that never landed must not succeed")
        assertIs<IllegalStateException>(failure)
        assertTrue("first frame" !in failure.message.orEmpty(), "the step was stopped, not refused: ${failure.message}")
        harness.close()
    }

    @Test
    fun `a backward step behind a waiting seek steps back from that seek's target`() = runTest {
        // The waiting seek is the timeline the caller asked for. Stepping back from the picture it
        // is about to replace would throw the seek away and land somewhere nobody asked for.
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        assertEquals(0L, harness.onScreenUs(), "the open shows the first frame")

        harness.core.seekLater(Pts(100_000), SeekMode.Precise)
        harness.core.stepFrame(StepDirection.Backward)
        harness.run(100.milliseconds)

        assertEquals(50_000L, harness.onScreenUs(), "one frame before the seek's target")
        assertEquals(50_000L, harness.core.position().inWholeMicroseconds)
        harness.close()
    }

    @Test
    fun `after playing and pausing each step reports the frame it shows`() = runTest {
        // Paused after playing, the audio clock still holds a reading from where playback stopped.
        // The picture is what a step moves, so the reported position must follow the picture.
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        harness.core.pause()
        harness.run(100.milliseconds)
        val paused = harness.onScreenUs()

        harness.core.stepFrame(StepDirection.Forward)
        harness.run(100.milliseconds)
        assertEquals(paused + 40_000L, harness.onScreenUs(), "a forward step shows the next frame")
        assertEquals(harness.onScreenUs(), harness.core.position().inWholeMicroseconds, "and reports it")

        harness.core.stepFrame(StepDirection.Backward)
        harness.run(100.milliseconds)
        assertEquals(paused, harness.onScreenUs(), "a backward step shows the frame before it again")
        assertEquals(paused, harness.core.position().inWholeMicroseconds, "and reports it")
        harness.close()
    }

    @Test
    fun `a backward step refuses a source that cannot seek`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(seekable = false))
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        assertFailsWith<UnsupportedOperationException> { harness.core.stepFrame(StepDirection.Backward) }
        harness.close()
    }

    @Test
    fun `a backward step refuses while playing`() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(50.milliseconds)
        assertFailsWith<IllegalStateException> { harness.core.stepFrame(StepDirection.Backward) }
        harness.close()
    }
}
